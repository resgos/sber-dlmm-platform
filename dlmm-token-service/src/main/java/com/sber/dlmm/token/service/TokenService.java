package com.sber.dlmm.token.service;

import com.sber.dlmm.common.dto.PageResponse;
import com.sber.dlmm.common.enums.TokenType;
import com.sber.dlmm.common.exception.IdempotencyConflictException;
import com.sber.dlmm.common.exception.InsufficientBalanceException;
import com.sber.dlmm.common.exception.TokenNotFoundException;
import com.sber.dlmm.token.dto.BalanceResponse;
import com.sber.dlmm.token.dto.BurnRequest;
import com.sber.dlmm.token.dto.CreateTokenRequest;
import com.sber.dlmm.token.dto.MintRequest;
import com.sber.dlmm.token.dto.TokenResponse;
import com.sber.dlmm.token.dto.TransferRequest;
import com.sber.dlmm.token.entity.Token;
import com.sber.dlmm.token.entity.UserBalance;
import com.sber.dlmm.token.event.TokenBurnedEvent;
import com.sber.dlmm.token.event.TokenCreatedEvent;
import com.sber.dlmm.token.event.TokenMintedEvent;
import com.sber.dlmm.token.event.TokenTransferredEvent;
import com.sber.dlmm.token.outbox.OutboxService;
import com.sber.dlmm.token.repository.TokenRepository;
import com.sber.dlmm.token.repository.UserBalanceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class TokenService {

    private static final Logger log = LoggerFactory.getLogger(TokenService.class);
    private static final String TOPIC = "token-events";

    private final TokenRepository tokenRepository;
    private final UserBalanceRepository userBalanceRepository;
    // Direct KafkaTemplate use was replaced with the transactional outbox
    // — Kafka send no longer happens inline with the balance mutation,
    // which closed the lost-event window. See OutboxService /
    // OutboxDispatcher for the new path.
    private final OutboxService outbox;
    private final Set<String> processedIdempotencyKeys = ConcurrentHashMap.newKeySet();

    public TokenService(TokenRepository tokenRepository,
                        UserBalanceRepository userBalanceRepository,
                        OutboxService outbox) {
        this.tokenRepository = tokenRepository;
        this.userBalanceRepository = userBalanceRepository;
        this.outbox = outbox;
    }

    @Transactional
    public TokenResponse createToken(CreateTokenRequest req, UUID adminUserId) {
        if (tokenRepository.existsBySymbol(req.symbol())) {
            throw new IllegalArgumentException("Token with symbol " + req.symbol() + " already exists");
        }
        if (req.tokenType() == TokenType.EQUITY_TOKEN &&
                (req.underlyingAsset() == null || req.underlyingAsset().isBlank())) {
            throw new IllegalArgumentException("EQUITY_TOKEN requires underlyingAsset");
        }

        Token token = new Token();
        token.setName(req.name());
        token.setSymbol(req.symbol());
        token.setDecimals(req.decimals());
        token.setTotalSupply(0);
        token.setMaxSupply(req.maxSupply());
        token.setTokenType(req.tokenType());
        token.setUnderlyingAsset(req.underlyingAsset());
        token.setPriceOracleId(req.priceOracleId());
        token.setMintable(req.mintable());
        token.setBurnable(req.burnable());
        token.setActive(true);
        token.setCreatedBy(adminUserId);

        token = tokenRepository.save(token);

        log.info("Token created: id={}, symbol={}", token.getId(), token.getSymbol());

        outbox.append("token", token.getId().toString(), "TokenCreated", TOPIC,
                new TokenCreatedEvent(token.getId(), token.getSymbol(),
                        token.getTokenType(), adminUserId, LocalDateTime.now()));

        return toTokenResponse(token);
    }

    @Transactional(readOnly = true)
    public PageResponse<TokenResponse> getAllTokens(int page, int size) {
        PageRequest pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<Token> tokenPage = tokenRepository.findAll(pageable);

        List<TokenResponse> content = tokenPage.getContent().stream()
                .map(this::toTokenResponse)
                .toList();

        return new PageResponse<>(content, tokenPage.getNumber(), tokenPage.getSize(),
                tokenPage.getTotalElements(), tokenPage.getTotalPages());
    }

    @Transactional(readOnly = true)
    public TokenResponse getToken(UUID tokenId) {
        Token token = findTokenOrThrow(tokenId);
        return toTokenResponse(token);
    }

    /**
     * Bulk lookup by ids — used by pool-engine to resolve every token symbol
     * in /pools listing with a single network round-trip instead of N+1
     * GET /tokens/{id} calls. Order of the returned list is undefined; callers
     * key by id.
     */
    @Transactional(readOnly = true)
    public java.util.List<TokenResponse> getTokensByIds(java.util.Collection<UUID> ids) {
        if (ids == null || ids.isEmpty()) return java.util.List.of();
        return tokenRepository.findAllById(ids).stream()
                .map(this::toTokenResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public TokenResponse getTokenBySymbol(String symbol) {
        Token token = tokenRepository.findBySymbol(symbol)
                .orElseThrow(() -> new TokenNotFoundException("Token not found with symbol: " + symbol));
        return toTokenResponse(token);
    }

    @Transactional
    public BalanceResponse mint(MintRequest req, UUID adminUserId) {
        Token token = findTokenOrThrow(req.tokenId());

        if (!token.isMintable()) {
            throw new IllegalStateException("Token " + token.getSymbol() + " is not mintable");
        }
        if (!token.isActive()) {
            throw new IllegalStateException("Token " + token.getSymbol() + " is paused");
        }
        if (token.getMaxSupply() > 0 && token.getTotalSupply() + req.amount() > token.getMaxSupply()) {
            throw new IllegalStateException("Minting would exceed max supply of " + token.getMaxSupply());
        }

        token.setTotalSupply(token.getTotalSupply() + req.amount());
        tokenRepository.save(token);

        UserBalance balance = userBalanceRepository.findByUserIdAndTokenId(req.toUserId(), req.tokenId())
                .orElse(null);

        if (balance == null) {
            balance = new UserBalance();
            balance.setUserId(req.toUserId());
            balance.setTokenId(req.tokenId());
            balance.setAvailable(0);
            balance.setLocked(0);
            userBalanceRepository.save(balance);
        }

        int updated = userBalanceRepository.creditAvailable(req.toUserId(), req.tokenId(), req.amount());
        if (updated == 0) {
            throw new IllegalStateException("Failed to credit balance");
        }

        balance = userBalanceRepository.findByUserIdAndTokenId(req.toUserId(), req.tokenId())
                .orElseThrow();

        log.info("Minted {} of {} to user {}", req.amount(), token.getSymbol(), req.toUserId());

        outbox.append("token", token.getId().toString(), "TokenMinted", TOPIC,
                new TokenMintedEvent(token.getId(), req.toUserId(), req.amount(),
                        token.getTotalSupply(), LocalDateTime.now()));

        return toBalanceResponse(balance, token.getSymbol());
    }

    @Transactional
    public BalanceResponse burn(BurnRequest req, UUID adminUserId) {
        Token token = findTokenOrThrow(req.tokenId());

        if (!token.isBurnable()) {
            throw new IllegalStateException("Token " + token.getSymbol() + " is not burnable");
        }

        int deducted = userBalanceRepository.deductAvailable(req.fromUserId(), req.tokenId(), req.amount());
        if (deducted == 0) {
            throw new InsufficientBalanceException(
                    "Insufficient available balance for user " + req.fromUserId());
        }

        token.setTotalSupply(token.getTotalSupply() - req.amount());
        tokenRepository.save(token);

        UserBalance balance = userBalanceRepository.findByUserIdAndTokenId(req.fromUserId(), req.tokenId())
                .orElseThrow();

        log.info("Burned {} of {} from user {}", req.amount(), token.getSymbol(), req.fromUserId());

        outbox.append("token", token.getId().toString(), "TokenBurned", TOPIC,
                new TokenBurnedEvent(token.getId(), req.fromUserId(), req.amount(),
                        token.getTotalSupply(), LocalDateTime.now()));

        return toBalanceResponse(balance, token.getSymbol());
    }

    @Transactional(readOnly = true)
    public List<BalanceResponse> getUserBalances(UUID userId) {
        List<UserBalance> balances = userBalanceRepository.findByUserId(userId);
        return balances.stream()
                .map(b -> {
                    String symbol = tokenRepository.findById(b.getTokenId())
                            .map(Token::getSymbol)
                            .orElse("UNKNOWN");
                    return toBalanceResponse(b, symbol);
                })
                .toList();
    }

    @Transactional(readOnly = true)
    public BalanceResponse getUserTokenBalance(UUID userId, UUID tokenId) {
        Token token = findTokenOrThrow(tokenId);
        UserBalance balance = userBalanceRepository.findByUserIdAndTokenId(userId, tokenId)
                .orElse(null);

        if (balance == null) {
            return new BalanceResponse(userId, tokenId, token.getSymbol(), 0, 0, 0);
        }
        return toBalanceResponse(balance, token.getSymbol());
    }

    @Transactional
    public void transfer(TransferRequest req) {
        if (req.idempotencyKey() != null && !req.idempotencyKey().isBlank()) {
            if (!processedIdempotencyKeys.add(req.idempotencyKey())) {
                throw new IdempotencyConflictException(
                        "Transfer with idempotency key " + req.idempotencyKey() + " already processed");
            }
        }

        Token token = findTokenOrThrow(req.tokenId());

        if (!token.isActive()) {
            throw new IllegalStateException("Token " + token.getSymbol() + " is paused");
        }

        int deducted = userBalanceRepository.deductAvailable(req.fromUserId(), req.tokenId(), req.amount());
        if (deducted == 0) {
            throw new InsufficientBalanceException(
                    "Insufficient available balance for user " + req.fromUserId());
        }

        UserBalance toBalance = userBalanceRepository.findByUserIdAndTokenId(req.toUserId(), req.tokenId())
                .orElse(null);
        if (toBalance == null) {
            toBalance = new UserBalance();
            toBalance.setUserId(req.toUserId());
            toBalance.setTokenId(req.tokenId());
            toBalance.setAvailable(0);
            toBalance.setLocked(0);
            userBalanceRepository.save(toBalance);
        }

        int credited = userBalanceRepository.creditAvailable(req.toUserId(), req.tokenId(), req.amount());
        if (credited == 0) {
            throw new IllegalStateException("Failed to credit recipient balance");
        }

        log.info("Transferred {} of {} from {} to {}", req.amount(), token.getSymbol(),
                req.fromUserId(), req.toUserId());

        outbox.append("token", token.getId().toString(), "TokenTransferred", TOPIC,
                new TokenTransferredEvent(token.getId(), req.fromUserId(), req.toUserId(),
                        req.amount(), req.idempotencyKey(), LocalDateTime.now()));
    }

    /**
     * Internal: deduct token amount from a user's available balance.
     * Used by pool-engine during swap (debit caller's input token) and
     * add-liquidity (debit deposited tokens). Throws InsufficientBalanceException
     * if balance is insufficient — the caller should treat that as a hard fail.
     */
    @Transactional
    public void deductInternal(UUID userId, UUID tokenId, long amount) {
        Token token = findTokenOrThrow(tokenId);
        if (!token.isActive()) {
            throw new IllegalStateException("Token " + token.getSymbol() + " is paused");
        }
        int deducted = userBalanceRepository.deductAvailable(userId, tokenId, amount);
        if (deducted == 0) {
            throw new InsufficientBalanceException(
                    "Insufficient available balance for user " + userId + " token " + token.getSymbol());
        }
        // Outbox: every swap leg goes through here. Previously these mutations
        // were Kafka-silent — notification-service never knew about them.
        // Now downstream projections (transaction ledger, user notification,
        // analytics) get a durable event per balance change.
        outbox.append("balance", userId + ":" + tokenId, "BalanceDeducted", TOPIC,
                new BalanceMutatedEvent(userId, tokenId, token.getSymbol(),
                        -amount, "deduct", LocalDateTime.now()));
        log.info("Internal deduct: user={} token={} amount={}", userId, token.getSymbol(), amount);
    }

    /**
     * Internal: credit token amount to a user's available balance.
     * Used by pool-engine during swap (credit caller's output token) and
     * remove-liquidity (return withdrawn tokens). Creates the balance row
     * if it doesn't exist yet.
     */
    @Transactional
    public void creditInternal(UUID userId, UUID tokenId, long amount) {
        Token token = findTokenOrThrow(tokenId);
        UserBalance balance = userBalanceRepository.findByUserIdAndTokenId(userId, tokenId)
                .orElse(null);
        if (balance == null) {
            balance = new UserBalance();
            balance.setUserId(userId);
            balance.setTokenId(tokenId);
            balance.setAvailable(0);
            balance.setLocked(0);
            userBalanceRepository.save(balance);
        }
        int credited = userBalanceRepository.creditAvailable(userId, tokenId, amount);
        if (credited == 0) {
            throw new IllegalStateException("Failed to credit balance for user " + userId);
        }
        outbox.append("balance", userId + ":" + tokenId, "BalanceCredited", TOPIC,
                new BalanceMutatedEvent(userId, tokenId, token.getSymbol(),
                        amount, "credit", LocalDateTime.now()));
        log.info("Internal credit: user={} token={} amount={}", userId, token.getSymbol(), amount);
    }

    /**
     * Event payload for the swap-critical balance mutations (deduct/credit).
     * Amount is signed: positive = credit, negative = deduct. Co-located here
     * so the TokenService stays the single source of truth for what gets
     * written to the outbox; if downstream consumers need a richer schema
     * we'll lift this into a shared module.
     */
    public record BalanceMutatedEvent(
            UUID userId,
            UUID tokenId,
            String symbol,
            long signedAmount,
            String operation,
            LocalDateTime occurredAt
    ) {}

    @Transactional
    public TokenResponse pauseToken(UUID tokenId) {
        Token token = findTokenOrThrow(tokenId);
        token.setActive(false);
        tokenRepository.save(token);
        log.info("Token paused: id={}, symbol={}", token.getId(), token.getSymbol());
        return toTokenResponse(token);
    }

    @Transactional
    public TokenResponse unpauseToken(UUID tokenId) {
        Token token = findTokenOrThrow(tokenId);
        token.setActive(true);
        tokenRepository.save(token);
        log.info("Token unpaused: id={}, symbol={}", token.getId(), token.getSymbol());
        return toTokenResponse(token);
    }

    private Token findTokenOrThrow(UUID tokenId) {
        return tokenRepository.findById(tokenId)
                .orElseThrow(() -> new TokenNotFoundException("Token not found: " + tokenId));
    }

    private TokenResponse toTokenResponse(Token token) {
        return new TokenResponse(
                token.getId(),
                token.getName(),
                token.getSymbol(),
                token.getDecimals(),
                token.getTotalSupply(),
                token.getMaxSupply(),
                token.getTokenType(),
                token.getUnderlyingAsset(),
                token.isMintable(),
                token.isBurnable(),
                token.isActive(),
                token.getCreatedAt()
        );
    }

    private BalanceResponse toBalanceResponse(UserBalance balance, String symbol) {
        return new BalanceResponse(
                balance.getUserId(),
                balance.getTokenId(),
                symbol,
                balance.getAvailable(),
                balance.getLocked(),
                balance.getAvailable() + balance.getLocked()
        );
    }
}
