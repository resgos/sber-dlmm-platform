package com.sber.dlmm.transaction.service;

import com.sber.dlmm.transaction.entity.OtcBlockTrade;
import com.sber.dlmm.transaction.repository.OtcBlockTradeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Sprint 9 #6.1 — pins the OTC state machine.
 *
 * <p>Covers: legal transitions, illegal transitions (must throw with
 * specific status info), terminal-state immutability, quote expiry
 * blocks acceptance, validation rules (same-side parties, zero amounts).
 */
@ExtendWith(MockitoExtension.class)
class OtcDeskServiceTest {

    private static final UUID ADMIN = UUID.randomUUID();
    private static final UUID INITIATOR = UUID.randomUUID();
    private static final UUID COUNTERPARTY = UUID.randomUUID();
    private static final UUID SRUB = UUID.randomUUID();
    private static final UUID USDT = UUID.randomUUID();

    @Mock private OtcBlockTradeRepository repository;

    @InjectMocks
    private OtcDeskService otcDeskService;

    private OtcBlockTrade existing;

    @BeforeEach
    void setUp() {
        // Generic save() echo with id set — mimics JPA @GeneratedValue.
        lenient().when(repository.save(any(OtcBlockTrade.class))).thenAnswer(inv -> {
            OtcBlockTrade t = inv.getArgument(0);
            if (t.getId() == null) t.setId(UUID.randomUUID());
            return t;
        });
    }

    private OtcBlockTrade tradeInStatus(OtcBlockTrade.Status status) {
        OtcBlockTrade t = OtcBlockTrade.builder()
                .id(UUID.randomUUID())
                .initiatorUserId(INITIATOR)
                .counterpartyUserId(COUNTERPARTY)
                .createdByAdminId(ADMIN)
                .tokenInId(SRUB).tokenOutId(USDT)
                .amountIn(10_000_000L)
                .status(status)
                .quoteExpiresAt(LocalDateTime.now().plusHours(1))
                .build();
        // Lenient: validation-first transitions (e.g. quote with past expiry)
        // throw before reaching the find — so the findById stub would be
        // flagged as Unnecessary in Mockito strict mode.
        lenient().when(repository.findById(t.getId())).thenReturn(Optional.of(t));
        return t;
    }

    // ── create ──

    @Nested
    @DisplayName("create")
    class CreateTests {

        @Test
        @DisplayName("happy path: REQUESTED state, fields persisted")
        void happy() {
            OtcBlockTrade trade = otcDeskService.create(
                    INITIATOR, COUNTERPARTY, SRUB, USDT, 10_000_000L, "Sber Treasury → MOEX clearing", ADMIN);

            assertThat(trade.getStatus()).isEqualTo(OtcBlockTrade.Status.REQUESTED);
            assertThat(trade.getInitiatorUserId()).isEqualTo(INITIATOR);
            assertThat(trade.getCounterpartyUserId()).isEqualTo(COUNTERPARTY);
            assertThat(trade.getCreatedByAdminId()).isEqualTo(ADMIN);
            assertThat(trade.getAmountIn()).isEqualTo(10_000_000L);
            assertThat(trade.getNotes()).contains("Sber Treasury");
        }

        @Test
        @DisplayName("rejects initiator == counterparty")
        void selfTradeRejected() {
            assertThatThrownBy(() ->
                    otcDeskService.create(INITIATOR, INITIATOR, SRUB, USDT, 100L, null, ADMIN))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Initiator and counterparty must differ");
        }

        @Test
        @DisplayName("rejects same token in and out")
        void sameTokenRejected() {
            assertThatThrownBy(() ->
                    otcDeskService.create(INITIATOR, COUNTERPARTY, SRUB, SRUB, 100L, null, ADMIN))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Token in and out must differ");
        }

        @Test
        @DisplayName("rejects non-positive amount")
        void zeroAmountRejected() {
            assertThatThrownBy(() ->
                    otcDeskService.create(INITIATOR, COUNTERPARTY, SRUB, USDT, 0L, null, ADMIN))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() ->
                    otcDeskService.create(INITIATOR, COUNTERPARTY, SRUB, USDT, -100L, null, ADMIN))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    // ── quote (REQUESTED → QUOTED) ──

    @Nested
    @DisplayName("quote: REQUESTED → QUOTED")
    class QuoteTests {

        @Test
        @DisplayName("happy path: quote attached, expiry set")
        void happy() {
            OtcBlockTrade trade = tradeInStatus(OtcBlockTrade.Status.REQUESTED);
            LocalDateTime expiry = LocalDateTime.now().plusMinutes(30);

            OtcBlockTrade result = otcDeskService.quote(trade.getId(), 95_000L, 9_500_000L, expiry);

            assertThat(result.getStatus()).isEqualTo(OtcBlockTrade.Status.QUOTED);
            assertThat(result.getAmountOut()).isEqualTo(95_000L);
            assertThat(result.getQuotedPriceMicro()).isEqualTo(9_500_000L);
            assertThat(result.getQuoteExpiresAt()).isEqualTo(expiry);
            assertThat(result.getQuotedAt()).isNotNull();
        }

        @Test
        @DisplayName("blocks transition from non-REQUESTED state")
        void fromQuotedRejected() {
            OtcBlockTrade trade = tradeInStatus(OtcBlockTrade.Status.QUOTED);
            assertThatThrownBy(() -> otcDeskService.quote(
                    trade.getId(), 100L, 1_000_000L, LocalDateTime.now().plusHours(1)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("required REQUESTED");
        }

        @Test
        @DisplayName("rejects past expiry")
        void pastExpiryRejected() {
            OtcBlockTrade trade = tradeInStatus(OtcBlockTrade.Status.REQUESTED);
            assertThatThrownBy(() -> otcDeskService.quote(
                    trade.getId(), 100L, 1_000_000L, LocalDateTime.now().minusMinutes(1)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("quoteExpiresAt must be in the future");
        }
    }

    // ── accept (QUOTED → ACCEPTED) ──

    @Nested
    @DisplayName("accept: QUOTED → ACCEPTED")
    class AcceptTests {

        @Test
        @DisplayName("happy path")
        void happy() {
            OtcBlockTrade trade = tradeInStatus(OtcBlockTrade.Status.QUOTED);
            OtcBlockTrade result = otcDeskService.accept(trade.getId());
            assertThat(result.getStatus()).isEqualTo(OtcBlockTrade.Status.ACCEPTED);
        }

        @Test
        @DisplayName("blocks acceptance after quote expiry")
        void expiredQuoteRejected() {
            OtcBlockTrade trade = tradeInStatus(OtcBlockTrade.Status.QUOTED);
            trade.setQuoteExpiresAt(LocalDateTime.now().minusMinutes(1));
            assertThatThrownBy(() -> otcDeskService.accept(trade.getId()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Quote expired");
        }

        @Test
        @DisplayName("blocks transition from non-QUOTED state")
        void fromRequestedRejected() {
            OtcBlockTrade trade = tradeInStatus(OtcBlockTrade.Status.REQUESTED);
            assertThatThrownBy(() -> otcDeskService.accept(trade.getId()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("required QUOTED");
        }
    }

    // ── settle (ACCEPTED → SETTLED) ──

    @Nested
    @DisplayName("settle: ACCEPTED → SETTLED")
    class SettleTests {

        @Test
        @DisplayName("happy path: txId + settledAt recorded")
        void happy() {
            OtcBlockTrade trade = tradeInStatus(OtcBlockTrade.Status.ACCEPTED);
            UUID txId = UUID.randomUUID();
            OtcBlockTrade result = otcDeskService.settle(trade.getId(), txId);
            assertThat(result.getStatus()).isEqualTo(OtcBlockTrade.Status.SETTLED);
            assertThat(result.getSettlementTxId()).isEqualTo(txId);
            assertThat(result.getSettledAt()).isNotNull();
        }

        @Test
        @DisplayName("blocks transition from non-ACCEPTED state")
        void fromQuotedRejected() {
            OtcBlockTrade trade = tradeInStatus(OtcBlockTrade.Status.QUOTED);
            assertThatThrownBy(() -> otcDeskService.settle(trade.getId(), UUID.randomUUID()))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("requires non-null txId")
        void txIdRequired() {
            assertThatThrownBy(() -> otcDeskService.settle(UUID.randomUUID(), null))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    // ── cancel ──

    @Nested
    @DisplayName("cancel")
    class CancelTests {

        @Test
        @DisplayName("allows cancel from REQUESTED with reason appended to notes")
        void fromRequested() {
            OtcBlockTrade trade = tradeInStatus(OtcBlockTrade.Status.REQUESTED);
            trade.setNotes("original note");
            OtcBlockTrade result = otcDeskService.cancel(trade.getId(), "counterparty changed mind");
            assertThat(result.getStatus()).isEqualTo(OtcBlockTrade.Status.CANCELLED);
            assertThat(result.getNotes()).contains("original note");
            assertThat(result.getNotes()).contains("counterparty changed mind");
        }

        @Test
        @DisplayName("allows cancel from QUOTED")
        void fromQuoted() {
            OtcBlockTrade trade = tradeInStatus(OtcBlockTrade.Status.QUOTED);
            OtcBlockTrade result = otcDeskService.cancel(trade.getId(), null);
            assertThat(result.getStatus()).isEqualTo(OtcBlockTrade.Status.CANCELLED);
        }

        @Test
        @DisplayName("blocks cancel from ACCEPTED — must settle (or compensate)")
        void fromAcceptedRejected() {
            OtcBlockTrade trade = tradeInStatus(OtcBlockTrade.Status.ACCEPTED);
            assertThatThrownBy(() -> otcDeskService.cancel(trade.getId(), null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("only REQUESTED or QUOTED");
        }

        @Test
        @DisplayName("blocks cancel from terminal states")
        void fromSettledRejected() {
            OtcBlockTrade trade = tradeInStatus(OtcBlockTrade.Status.SETTLED);
            assertThatThrownBy(() -> otcDeskService.cancel(trade.getId(), null))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    // ── isTerminal helper ──

    @Test
    @DisplayName("isTerminal: SETTLED / REJECTED / EXPIRED / CANCELLED are terminal; others not")
    void isTerminalCorrect() {
        assertThat(OtcDeskService.isTerminal(OtcBlockTrade.Status.SETTLED)).isTrue();
        assertThat(OtcDeskService.isTerminal(OtcBlockTrade.Status.REJECTED)).isTrue();
        assertThat(OtcDeskService.isTerminal(OtcBlockTrade.Status.EXPIRED)).isTrue();
        assertThat(OtcDeskService.isTerminal(OtcBlockTrade.Status.CANCELLED)).isTrue();
        assertThat(OtcDeskService.isTerminal(OtcBlockTrade.Status.REQUESTED)).isFalse();
        assertThat(OtcDeskService.isTerminal(OtcBlockTrade.Status.QUOTED)).isFalse();
        assertThat(OtcDeskService.isTerminal(OtcBlockTrade.Status.ACCEPTED)).isFalse();
    }
}
