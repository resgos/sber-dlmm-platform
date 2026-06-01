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
import com.sber.dlmm.common.outbox.OutboxService;
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

/**
 * Core service for the token catalog and per-user balances — the heart of
 * {@code dlmm-token-service}.
 *
 * <p><b>Responsibilities:</b>
 * <ul>
 *   <li><b>Catalog</b>: create/list/lookup tokens, pause/unpause, and supply
 *       cap enforcement on mint.</li>
 *   <li><b>Balances</b>: admin mint/burn/transfer, and the internal
 *       {@link #deductInternal}/{@link #creditInternal} pair that pool-engine
 *       calls per swap/liquidity leg.</li>
 * </ul>
 *
 * <p><b>Eventing — transactional outbox:</b> every state change appends to the
 * {@link OutboxService} <i>inside</i> the business transaction rather than sending
 * to Kafka inline. This closed the lost-event window where a balance mutation
 * could commit while its Kafka send failed (see the field comment on
 * {@link #outbox}). A separate dispatcher drains the outbox to the
 * {@code token-events} topic.
 *
 * <p><b>Amount scale (#14):</b> every token {@code amount} here is a raw integer
 * where 1 token = 10000 raw units (4 platform decimals). Supplies, balances and
 * mint/burn/transfer amounts are all in these raw units. Prices, basis points and
 * ratios are NOT scaled (this service deals only in quantities).
 *
 * <p><b>Idempotency invariant:</b> transfers carrying an {@code idempotencyKey}
 * are deduplicated in-process via {@link #processedIdempotencyKeys}. This is a
 * single-instance, in-memory guard (see {@link #transfer}) — it does not survive
 * a restart and is not shared across instances.
 *
 * <p>Collaborators: {@link TokenRepository}, {@link UserBalanceRepository}
 * (whose atomic {@code creditAvailable}/{@code deductAvailable} UPDATEs are the
 * concurrency-safe primitives this service is built on), {@link OutboxService}.
 */
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

    /**
     * @param tokenRepository       token catalog persistence
     * @param userBalanceRepository per-user balance persistence (atomic credit/deduct UPDATEs)
     * @param outbox                transactional outbox the domain events are appended to
     */
    public TokenService(TokenRepository tokenRepository,
                        UserBalanceRepository userBalanceRepository,
                        OutboxService outbox) {
        this.tokenRepository = tokenRepository;
        this.userBalanceRepository = userBalanceRepository;
        this.outbox = outbox;
    }

    /**
     * Create a new token in the catalog with zero supply.
     *
     * <p>Enforces symbol uniqueness and the per-type {@code underlyingAsset}
     * requirement (delegated to {@link TokenType#requiresUnderlyingAsset()} so the
     * price-oracle can always resolve backed tokens), then persists the row and
     * emits a {@link TokenCreatedEvent}. {@code totalSupply} starts at 0 — tokens
     * are minted into existence afterwards via {@link #mint}.
     *
     * @param req         token definition (symbol, decimals, type, caps, flags)
     * @param adminUserId admin creating the token (stored as {@code createdBy} and in the event)
     * @return the created token as a response DTO
     * @throws IllegalArgumentException if the symbol already exists, or the type
     *         requires an {@code underlyingAsset} that is missing/blank
     */
    @Transactional
    public TokenResponse createToken(CreateTokenRequest req, UUID adminUserId) {
        if (tokenRepository.existsBySymbol(req.symbol())) {
            throw new IllegalArgumentException("Token with symbol " + req.symbol() + " already exists");
        }
        // Sprint 9-DS-r4 (TD-7) — extended TokenType values now
        // properly enforced. Previously only EQUITY_TOKEN required an
        // underlyingAsset; FIAT_BACKED, COMMODITY_BACKED, INDEX_TOKEN
        // (Sprint 2 extended catalog) silently passed validation
        // even without one, leaving the price-oracle unable to resolve
        // them. The policy now lives on the enum itself
        // ({@link TokenType#requiresUnderlyingAsset}).
        if (req.tokenType() != null && req.tokenType().requiresUnderlyingAsset() &&
                (req.underlyingAsset() == null || req.underlyingAsset().isBlank())) {
            throw new IllegalArgumentException(req.tokenType() + " requires underlyingAsset");
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

    /**
     * Page through the full token catalog, newest first.
     *
     * @param page zero-based page index
     * @param size page size
     * @return a page of token DTOs plus paging metadata
     */
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

    /**
     * @param tokenId token id
     * @return the token DTO
     * @throws TokenNotFoundException if no token exists for {@code tokenId}
     */
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

    /**
     * @param symbol token ticker symbol (e.g. {@code SRUB})
     * @return the token DTO
     * @throws TokenNotFoundException if no token exists with that symbol
     */
    @Transactional(readOnly = true)
    public TokenResponse getTokenBySymbol(String symbol) {
        Token token = tokenRepository.findBySymbol(symbol)
                .orElseThrow(() -> new TokenNotFoundException("Token not found with symbol: " + symbol));
        return toTokenResponse(token);
    }

    /**
     * Mint new units of a token to a user, increasing total supply.
     *
     * <p>Guards: the token must be mintable, active (not paused), and the mint must
     * not push {@code totalSupply} past {@code maxSupply} (when a cap is set). Bumps
     * supply, lazily creates the recipient's balance row, credits it atomically via
     * {@link UserBalanceRepository#creditAvailable}, and emits a
     * {@link TokenMintedEvent}.
     *
     * @param req         mint request (token id, recipient, raw-unit amount)
     * @param adminUserId admin performing the mint (currently for audit/caller context)
     * @return the recipient's updated balance
     * @throws TokenNotFoundException if the token does not exist
     * @throws IllegalStateException  if the token is not mintable, is paused, the
     *         mint would exceed max supply, or the credit unexpectedly fails
     */
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

    /**
     * Burn units of a token from a user, decreasing total supply.
     *
     * <p>The deduct happens first (atomic {@link UserBalanceRepository#deductAvailable})
     * so an insufficient balance fails before any supply change; then supply is
     * decremented and a {@link TokenBurnedEvent} emitted.
     *
     * @param req         burn request (token id, holder, raw-unit amount)
     * @param adminUserId admin performing the burn (currently for audit/caller context)
     * @return the holder's updated balance
     * @throws TokenNotFoundException        if the token does not exist
     * @throws IllegalStateException         if the token is not burnable
     * @throws InsufficientBalanceException  if the holder's available balance is below the amount
     */
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

    /**
     * List every non-empty balance row a user holds, each resolved to its token
     * symbol ({@code "UNKNOWN"} if the token row has since vanished).
     *
     * @param userId the holder
     * @return one {@link BalanceResponse} per token the user has a balance row for
     */
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

    /**
     * Get a user's balance for one token. A user with no balance row yields a
     * zeroed response (rather than an error) so callers can render "0" uniformly.
     *
     * @param userId  the holder
     * @param tokenId the token
     * @return the balance, or an all-zero balance if no row exists
     * @throws TokenNotFoundException if the token itself does not exist
     */
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

    /**
     * Move a token amount between two users (peer-to-peer transfer).
     *
     * <p>If an {@code idempotencyKey} is supplied it is claimed in-process first
     * (see {@link #processedIdempotencyKeys}) so a duplicate request is rejected
     * rather than re-applied. The token must be active; the sender is debited
     * atomically before the recipient is credited (lazily creating the recipient
     * row), and a {@link TokenTransferredEvent} is emitted.
     *
     * @param req transfer request (token, from/to users, raw-unit amount, optional idempotency key)
     * @throws IdempotencyConflictException if the idempotency key was already processed
     * @throws TokenNotFoundException       if the token does not exist
     * @throws IllegalStateException        if the token is paused, or the credit unexpectedly fails
     * @throws InsufficientBalanceException if the sender's available balance is below the amount
     */
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
     *
     * @param userId  the user being debited
     * @param tokenId the token being debited
     * @param amount  raw-unit amount to deduct (1 token = 10000 units)
     * @throws TokenNotFoundException        if the token does not exist
     * @throws IllegalStateException         if the token is paused
     * @throws InsufficientBalanceException  if available balance is below {@code amount}
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
     *
     * @param userId  the user being credited
     * @param tokenId the token being credited
     * @param amount  raw-unit amount to credit (1 token = 10000 units)
     * @throws TokenNotFoundException if the token does not exist
     * @throws IllegalStateException  if the credit unexpectedly fails after row creation
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

    /**
     * Pause a token: sets {@code active=false}, which makes mint and transfer
     * reject it (an admin kill-switch). Idempotent in effect.
     *
     * @param tokenId token to pause
     * @return the updated token DTO
     * @throws TokenNotFoundException if the token does not exist
     */
    @Transactional
    public TokenResponse pauseToken(UUID tokenId) {
        Token token = findTokenOrThrow(tokenId);
        token.setActive(false);
        tokenRepository.save(token);
        log.info("Token paused: id={}, symbol={}", token.getId(), token.getSymbol());
        return toTokenResponse(token);
    }

    /**
     * Unpause a token: sets {@code active=true}, re-enabling mint/transfer.
     *
     * @param tokenId token to unpause
     * @return the updated token DTO
     * @throws TokenNotFoundException if the token does not exist
     */
    @Transactional
    public TokenResponse unpauseToken(UUID tokenId) {
        Token token = findTokenOrThrow(tokenId);
        token.setActive(true);
        tokenRepository.save(token);
        log.info("Token unpaused: id={}, symbol={}", token.getId(), token.getSymbol());
        return toTokenResponse(token);
    }

    /**
     * Lookup-or-throw helper that every catalog/balance method routes through so
     * a missing token surfaces a uniform {@link TokenNotFoundException}.
     *
     * @param tokenId token id
     * @return the token entity
     * @throws TokenNotFoundException if the token does not exist
     */
    private Token findTokenOrThrow(UUID tokenId) {
        return tokenRepository.findById(tokenId)
                .orElseThrow(() -> new TokenNotFoundException("Token not found: " + tokenId));
    }

    /**
     * Maps a {@link Token} entity to its API {@link TokenResponse} DTO.
     *
     * @param token entity to map
     * @return the response DTO
     */
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

    /**
     * Maps a {@link UserBalance} entity to a {@link BalanceResponse}, deriving the
     * total as {@code available + locked}. All three amounts are raw token units.
     *
     * @param balance balance entity to map
     * @param symbol  the token's symbol (resolved by the caller)
     * @return the response DTO
     */
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
