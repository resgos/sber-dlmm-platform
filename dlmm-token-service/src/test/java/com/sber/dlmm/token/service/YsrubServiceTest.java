package com.sber.dlmm.token.service;

import com.sber.dlmm.common.exception.InsufficientBalanceException;
import com.sber.dlmm.common.outbox.OutboxService;
import com.sber.dlmm.token.entity.Token;
import com.sber.dlmm.token.entity.YsrubReserveMovement;
import com.sber.dlmm.token.repository.TokenRepository;
import com.sber.dlmm.token.repository.UserBalanceRepository;
import com.sber.dlmm.token.repository.YsrubReserveMovementRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Sprint 9 #7.1 — pin the YSRUB mint/burn contract.
 *
 * <p>Covers: idempotency short-circuit, insufficient-balance fail,
 * reserve-shortfall fail-loud, outbox event shape, ratio math
 * (1:1 today, hard-coded so the test surfaces any regression).
 */
@ExtendWith(MockitoExtension.class)
class YsrubServiceTest {

    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID SRUB_ID = UUID.fromString("b0000000-0000-0000-0000-000000000001");
    private static final UUID YSRUB_ID = UUID.fromString("b0000000-0000-0000-0000-000000000007");
    private static final UUID RESERVE_ID = UUID.fromString("a0000000-0000-0000-0000-000000000099");

    @Mock private TokenRepository tokenRepository;
    @Mock private UserBalanceRepository userBalanceRepository;
    @Mock private YsrubReserveMovementRepository reserveRepository;
    @Mock private OutboxService outbox;

    @InjectMocks
    private YsrubService ysrubService;

    private Token srubToken;
    private Token ysrubToken;

    @BeforeEach
    void setUp() {
        srubToken = new Token();
        srubToken.setId(SRUB_ID);
        srubToken.setSymbol("SRUB");
        ysrubToken = new Token();
        ysrubToken.setId(YSRUB_ID);
        ysrubToken.setSymbol("YSRUB");
        ysrubToken.setTotalSupply(0);
    }

    private void stubResolveTokens() {
        // Lenient: not every test path consumes all three stubs (early-exit
        // tests like idempotency / insufficient-balance never reach the
        // findById call). Strict-mode UnnecessaryStubbing would fail those.
        lenient().when(tokenRepository.findBySymbol("SRUB")).thenReturn(Optional.of(srubToken));
        lenient().when(tokenRepository.findBySymbol("YSRUB")).thenReturn(Optional.of(ysrubToken));
        lenient().when(tokenRepository.findById(YSRUB_ID)).thenReturn(Optional.of(ysrubToken));
    }

    // ── mint ──

    @Test
    @DisplayName("mint: happy path — SRUB deducted, YSRUB credited 1:1, totalSupply bumped, outbox fired")
    void mintHappyPath() {
        stubResolveTokens();
        when(reserveRepository.findByIdempotencyKey("key-1")).thenReturn(Optional.empty());
        // user has 10_000 SRUB; deduct succeeds
        when(userBalanceRepository.deductAvailable(USER_ID, SRUB_ID, 10_000L)).thenReturn(1);
        when(userBalanceRepository.creditAvailable(RESERVE_ID, SRUB_ID, 10_000L)).thenReturn(1);
        when(userBalanceRepository.creditAvailable(USER_ID, YSRUB_ID, 10_000L)).thenReturn(1);
        // Mock JPA's @GeneratedValue: save() returns the entity with a
        // freshly-set id so subsequent .getId().toString() (outbox aggregate
        // key) doesn't NPE.
        when(reserveRepository.save(any())).thenAnswer(inv -> {
            YsrubReserveMovement m = inv.getArgument(0);
            if (m.getId() == null) m.setId(UUID.randomUUID());
            return m;
        });
        when(reserveRepository.totalReserveSrub()).thenReturn(10_000L);

        YsrubReserveMovement result = ysrubService.mint(USER_ID, 10_000L, "key-1");

        assertThat(result.getDirection()).isEqualTo(YsrubReserveMovement.Direction.DEPOSIT);
        assertThat(result.getSrubAmount()).isEqualTo(10_000L);
        assertThat(result.getYsrubAmount()).isEqualTo(10_000L);   // 1:1 in Sprint 9
        assertThat(result.getRatioMicro()).isEqualTo(1_000_000L);

        // YSRUB token totalSupply bumped
        assertThat(ysrubToken.getTotalSupply()).isEqualTo(10_000L);

        // Outbox event fired with the right shape
        verify(outbox).append(eq("ysrub"), anyString(), eq("YsrubMinted"), eq("token-events"), any());
    }

    @Test
    @DisplayName("mint: idempotency — same key returns existing movement, no double-deduct")
    void mintIdempotency() {
        YsrubReserveMovement existing = YsrubReserveMovement.builder()
                .id(UUID.randomUUID())
                .userId(USER_ID)
                .direction(YsrubReserveMovement.Direction.DEPOSIT)
                .srubAmount(5000L).ysrubAmount(5000L).ratioMicro(1_000_000L)
                .idempotencyKey("dup-key")
                .build();
        when(reserveRepository.findByIdempotencyKey("dup-key")).thenReturn(Optional.of(existing));

        YsrubReserveMovement result = ysrubService.mint(USER_ID, 5000L, "dup-key");

        assertThat(result).isSameAs(existing);
        // Critical: no balance touched on idempotency hit
        verify(userBalanceRepository, never()).deductAvailable(any(), any(), anyLong());
        verifyNoInteractions(outbox);
    }

    @Test
    @DisplayName("mint: insufficient SRUB balance → InsufficientBalanceException")
    void mintInsufficientBalance() {
        stubResolveTokens();
        when(reserveRepository.findByIdempotencyKey("key-2")).thenReturn(Optional.empty());
        when(userBalanceRepository.deductAvailable(USER_ID, SRUB_ID, 50_000L)).thenReturn(0);

        assertThatThrownBy(() -> ysrubService.mint(USER_ID, 50_000L, "key-2"))
                .isInstanceOf(InsufficientBalanceException.class)
                .hasMessageContaining("Insufficient SRUB available");

        // No reserve credit, no YSRUB credit, no outbox
        verify(userBalanceRepository, never()).creditAvailable(eq(RESERVE_ID), any(), anyLong());
        verifyNoInteractions(outbox);
    }

    @Test
    @DisplayName("mint: amount must be positive")
    void mintRejectsZeroOrNegative() {
        assertThatThrownBy(() -> ysrubService.mint(USER_ID, 0L, "key-x"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("srubAmount must be positive");
        assertThatThrownBy(() -> ysrubService.mint(USER_ID, -1L, "key-x"))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(userBalanceRepository, reserveRepository, outbox);
    }

    // ── burn ──

    @Test
    @DisplayName("burn: happy path — YSRUB deducted, SRUB returned 1:1, totalSupply decremented")
    void burnHappyPath() {
        stubResolveTokens();
        ysrubToken.setTotalSupply(20_000L); // pre-existing supply
        when(reserveRepository.findByIdempotencyKey("burn-1")).thenReturn(Optional.empty());
        when(userBalanceRepository.deductAvailable(USER_ID, YSRUB_ID, 8_000L)).thenReturn(1);
        when(userBalanceRepository.deductAvailable(RESERVE_ID, SRUB_ID, 8_000L)).thenReturn(1);
        when(userBalanceRepository.creditAvailable(USER_ID, SRUB_ID, 8_000L)).thenReturn(1);
        // Mock JPA's @GeneratedValue: save() returns the entity with a
        // freshly-set id so subsequent .getId().toString() (outbox aggregate
        // key) doesn't NPE.
        when(reserveRepository.save(any())).thenAnswer(inv -> {
            YsrubReserveMovement m = inv.getArgument(0);
            if (m.getId() == null) m.setId(UUID.randomUUID());
            return m;
        });
        when(reserveRepository.totalReserveSrub()).thenReturn(12_000L);

        YsrubReserveMovement result = ysrubService.burn(USER_ID, 8_000L, "burn-1");

        assertThat(result.getDirection()).isEqualTo(YsrubReserveMovement.Direction.WITHDRAWAL);
        assertThat(result.getSrubAmount()).isEqualTo(8_000L);
        assertThat(result.getYsrubAmount()).isEqualTo(8_000L);
        assertThat(ysrubToken.getTotalSupply()).isEqualTo(12_000L);   // 20_000 - 8_000

        verify(outbox).append(eq("ysrub"), anyString(), eq("YsrubBurned"), eq("token-events"), any());
    }

    @Test
    @DisplayName("burn: insufficient YSRUB → InsufficientBalanceException, reserve untouched")
    void burnInsufficientUserBalance() {
        stubResolveTokens();
        when(reserveRepository.findByIdempotencyKey("burn-2")).thenReturn(Optional.empty());
        when(userBalanceRepository.deductAvailable(USER_ID, YSRUB_ID, 100_000L)).thenReturn(0);

        assertThatThrownBy(() -> ysrubService.burn(USER_ID, 100_000L, "burn-2"))
                .isInstanceOf(InsufficientBalanceException.class)
                .hasMessageContaining("Insufficient YSRUB available");

        // Reserve must NOT be touched on user-balance failure
        verify(userBalanceRepository, never()).deductAvailable(eq(RESERVE_ID), any(), anyLong());
        verifyNoInteractions(outbox);
    }

    @Test
    @DisplayName("burn: reserve shortfall fails LOUD — accounting drift signal")
    void burnReserveShortfallFailsLoud() {
        stubResolveTokens();
        when(reserveRepository.findByIdempotencyKey("burn-3")).thenReturn(Optional.empty());
        when(userBalanceRepository.deductAvailable(USER_ID, YSRUB_ID, 5_000L)).thenReturn(1);
        // Reserve doesn't have the SRUB — 1:1 invariant violated, must fail loud
        when(userBalanceRepository.deductAvailable(RESERVE_ID, SRUB_ID, 5_000L)).thenReturn(0);

        assertThatThrownBy(() -> ysrubService.burn(USER_ID, 5_000L, "burn-3"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("YSRUB RESERVE SHORTFALL");

        // User SRUB never credited, outbox never fires
        verify(userBalanceRepository, never()).creditAvailable(eq(USER_ID), eq(SRUB_ID), anyLong());
        verifyNoInteractions(outbox);
    }

    @Test
    @DisplayName("burn: idempotency — same key returns existing movement")
    void burnIdempotency() {
        YsrubReserveMovement existing = YsrubReserveMovement.builder()
                .id(UUID.randomUUID())
                .userId(USER_ID)
                .direction(YsrubReserveMovement.Direction.WITHDRAWAL)
                .srubAmount(3000L).ysrubAmount(3000L).ratioMicro(1_000_000L)
                .build();
        when(reserveRepository.findByIdempotencyKey("burn-dup")).thenReturn(Optional.of(existing));

        YsrubReserveMovement result = ysrubService.burn(USER_ID, 3000L, "burn-dup");

        assertThat(result).isSameAs(existing);
        verify(userBalanceRepository, never()).deductAvailable(any(), any(), anyLong());
    }

    // ── outbox payload pinning ──

    @Test
    @DisplayName("mint: outbox event payload includes ratio + reserve total for compliance replay")
    void mintEventPayloadShape() {
        stubResolveTokens();
        when(reserveRepository.findByIdempotencyKey(anyString())).thenReturn(Optional.empty());
        when(userBalanceRepository.deductAvailable(any(), any(), anyLong())).thenReturn(1);
        when(userBalanceRepository.creditAvailable(any(), any(), anyLong())).thenReturn(1);
        // Mock JPA's @GeneratedValue: save() returns the entity with a
        // freshly-set id so subsequent .getId().toString() (outbox aggregate
        // key) doesn't NPE.
        when(reserveRepository.save(any())).thenAnswer(inv -> {
            YsrubReserveMovement m = inv.getArgument(0);
            if (m.getId() == null) m.setId(UUID.randomUUID());
            return m;
        });
        when(reserveRepository.totalReserveSrub()).thenReturn(100_000L);

        ysrubService.mint(USER_ID, 25_000L, "key-payload");

        ArgumentCaptor<Object> payloadCap = ArgumentCaptor.forClass(Object.class);
        verify(outbox).append(eq("ysrub"), anyString(), eq("YsrubMinted"),
                eq("token-events"), payloadCap.capture());

        // Payload is YsrubMintedEvent record — pin a couple of fields via toString
        // (records have predictable toString shape with field names).
        String payload = payloadCap.getValue().toString();
        assertThat(payload).contains("srubAmount=25000");
        assertThat(payload).contains("ysrubAmount=25000");
        assertThat(payload).contains("ratioMicro=1000000");
        assertThat(payload).contains("totalReserveSrubAfter=100000");
    }
}
