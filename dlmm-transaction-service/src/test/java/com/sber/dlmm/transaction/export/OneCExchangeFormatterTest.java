package com.sber.dlmm.transaction.export;

import com.sber.dlmm.common.enums.TransactionStatus;
import com.sber.dlmm.common.enums.TransactionType;
import com.sber.dlmm.transaction.entity.Transaction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 5 #5.11 — pins the 1CClientBankExchange v1.03 output shape.
 * Pure static helper, no Spring / JPA needed.
 *
 * <p>These tests intentionally check the EXACT text-level details (line
 * endings, section markers, key spellings) because 1С silently rejects
 * files on a single-character typo and we'd find out at the accountant's
 * 1С wizard, not in CI.
 */
class OneCExchangeFormatterTest {

    private static final UUID USER = UUID.fromString("a3f8c912-1234-5678-9abc-def012345678");
    private static final UUID POOL = UUID.fromString("b1234567-89ab-cdef-0123-456789abcdef");
    private static final LocalDateTime CREATED = LocalDateTime.of(2026, 5, 19, 12, 0, 0);

    private Transaction makeSwap(long amountIn, long fee) {
        return Transaction.builder()
                .id(UUID.fromString("c7d8e9f0-1234-5678-9abc-def012345678"))
                .txType(TransactionType.SWAP)
                .status(TransactionStatus.CONFIRMED)
                .userId(USER)
                .poolId(POOL)
                .amountIn(amountIn)
                .amountOut(amountIn * 95 / 100)  // simulated 5% spread
                .feeAmount(fee)
                .idempotencyKey("idem-key-001")
                .createdAt(CREATED)
                .confirmedAt(CREATED.plusSeconds(2))
                .build();
    }

    @Test
    @DisplayName("Header has mandatory 1CClientBankExchange marker + spec fields")
    void headerHasMandatoryFields() {
        String out = OneCExchangeFormatter.format(USER, List.of(), null, null, CREATED);

        assertTrue(out.startsWith("1CClientBankExchange\r\n"),
                "1С rejects file without the magic first line");
        assertTrue(out.contains("ВерсияФормата=1.03\r\n"));
        assertTrue(out.contains("Кодировка=Windows\r\n"));
        assertTrue(out.contains("Отправитель=DLMM Platform\r\n"));
        assertTrue(out.contains("ДатаСоздания=19.05.2026\r\n"));
        assertTrue(out.contains("ВремяСоздания=12:00:00\r\n"));
    }

    @Test
    @DisplayName("Footer always ends with КонецФайла")
    void endsWithKonetsFayla() {
        String out = OneCExchangeFormatter.format(USER, List.of(), null, null, CREATED);
        assertTrue(out.endsWith("КонецФайла\r\n"),
                "1С rejects file without КонецФайла terminator");
    }

    @Test
    @DisplayName("CRLF line endings used (Windows convention required by 1С)")
    void crlfLineEndings() {
        String out = OneCExchangeFormatter.format(USER, List.of(), null, null, CREATED);
        // 1С on Windows expects CRLF — Unix LF only WILL be silently rejected.
        // Count CRLF occurrences against total \n — must be equal.
        long crlfCount = out.lines().count();
        long crCount = out.chars().filter(c -> c == '\r').count();
        assertEquals(crlfCount, crCount,
                "every line must end with \\r\\n (no bare LF allowed)");
    }

    @Test
    @DisplayName("SWAP transaction becomes СекцияДокумент=Платежное поручение")
    void swapBecomesPaymentDocument() {
        Transaction swap = makeSwap(10_000, 30);
        String out = OneCExchangeFormatter.format(USER, List.of(swap), null, null, CREATED);

        assertTrue(out.contains("СекцияДокумент=Платежное поручение\r\n"));
        assertTrue(out.contains("КонецДокумента\r\n"));
        // outbound: SWAP → anchor is payer, pool is recipient
        assertTrue(out.contains("ПлательщикСчет=DLMM-USR-A3F8C912\r\n"),
                "anchor account must appear as ПлательщикСчет for outbound SWAP");
        assertTrue(out.contains("ПолучательСчет=DLMM-POOL-B1234567\r\n"),
                "pool stub account must appear as ПолучательСчет");
    }

    @Test
    @DisplayName("Сумма uses dot decimal regardless of system locale")
    void amountUsesDotDecimal() {
        Transaction swap = makeSwap(15_000_000, 45);
        String out = OneCExchangeFormatter.format(USER, List.of(swap), null, null, CREATED);
        assertTrue(out.contains("Сумма=15000000.00\r\n"),
                "1С Сумма must use . separator and trailing .00");
        // sanity: no comma-decimal anywhere in Сумма line
        assertFalse(out.contains("Сумма=15000000,00\r\n"));
    }

    @Test
    @DisplayName("REMOVE_LIQUIDITY is inbound — anchor becomes ПолучательСчет")
    void removeLiquidityIsInbound() {
        Transaction remove = Transaction.builder()
                .id(UUID.randomUUID())
                .txType(TransactionType.REMOVE_LIQUIDITY)
                .status(TransactionStatus.CONFIRMED)
                .userId(USER)
                .poolId(POOL)
                .amountIn(100_000L)
                .amountOut(99_500L)
                .feeAmount(500L)
                .createdAt(CREATED)
                .confirmedAt(CREATED.plusSeconds(2))
                .build();
        String out = OneCExchangeFormatter.format(USER, List.of(remove), null, null, CREATED);
        assertTrue(out.contains("ПлательщикСчет=DLMM-POOL-"));
        assertTrue(out.contains("ПолучательСчет=DLMM-USR-A3F8C912\r\n"));
    }

    @Test
    @DisplayName("CLAIM_FEE is inbound (anchor as ПолучательСчет)")
    void claimFeeIsInbound() {
        Transaction claim = Transaction.builder()
                .id(UUID.randomUUID())
                .txType(TransactionType.CLAIM_FEE)
                .status(TransactionStatus.CONFIRMED)
                .userId(USER)
                .poolId(POOL)
                .amountOut(123L)
                .feeAmount(0L)
                .createdAt(CREATED)
                .build();
        String out = OneCExchangeFormatter.format(USER, List.of(claim), null, null, CREATED);
        assertTrue(out.contains("ПолучательСчет=DLMM-USR-A3F8C912\r\n"));
    }

    @Test
    @DisplayName("НазначениеПлатежа carries tx id, type, pool, fee, idem")
    void purposeFieldHasDiagnostics() {
        Transaction swap = makeSwap(10_000, 30);
        String out = OneCExchangeFormatter.format(USER, List.of(swap), null, null, CREATED);

        // We check substrings; the exact line content includes the UUIDs.
        assertTrue(out.contains("НазначениеПлатежа=DLMM tx:c7d8e9f0-"));
        assertTrue(out.contains(" type:SWAP"));
        assertTrue(out.contains(" pool:b1234567-"));
        assertTrue(out.contains(" fee:30"));
        assertTrue(out.contains(" idem:idem-key-001"));
    }

    @Test
    @DisplayName("Empty transaction list still produces a valid 1С file (header+footer)")
    void emptyListStillValid() {
        String out = OneCExchangeFormatter.format(USER, List.of(), null, null, CREATED);
        assertTrue(out.startsWith("1CClientBankExchange\r\n"));
        assertTrue(out.endsWith("КонецФайла\r\n"));
        assertFalse(out.contains("СекцияДокумент"),
                "empty list = no document sections");
    }

    @Test
    @DisplayName("Date range falls back to data extents when caller passes null")
    void dateRangeFallback() {
        Transaction tx1 = Transaction.builder()
                .id(UUID.randomUUID())
                .txType(TransactionType.SWAP)
                .userId(USER)
                .feeAmount(0L)
                .createdAt(LocalDateTime.of(2026, 4, 1, 10, 0))
                .build();
        Transaction tx2 = Transaction.builder()
                .id(UUID.randomUUID())
                .txType(TransactionType.SWAP)
                .userId(USER)
                .feeAmount(0L)
                .createdAt(LocalDateTime.of(2026, 5, 15, 14, 0))
                .build();
        // No explicit from/to — service derives from data min/max
        String out = OneCExchangeFormatter.format(USER, List.of(tx1, tx2), null, null, CREATED);
        assertTrue(out.contains("ДатаНачала=01.04.2026\r\n"));
        assertTrue(out.contains("ДатаКонца=19.05.2026\r\n"));
    }

    @Test
    @DisplayName("Explicit date range overrides data extents")
    void explicitDateRange() {
        String out = OneCExchangeFormatter.format(
                USER, List.of(),
                LocalDate.of(2026, 1, 1),
                LocalDate.of(2026, 1, 31),
                CREATED);
        assertTrue(out.contains("ДатаНачала=01.01.2026\r\n"));
        assertTrue(out.contains("ДатаКонца=31.01.2026\r\n"));
    }

    @Test
    @DisplayName("Null targetUserId rejected with IllegalArgumentException")
    void nullTargetRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> OneCExchangeFormatter.format(null, List.of(), null, null, CREATED));
    }

    @Test
    @DisplayName("Account stub format matches DLMM-USR-XXXXXXXX convention")
    void accountStubFormat() {
        String acc = OneCExchangeFormatter.stubAccount(USER);
        assertEquals("DLMM-USR-A3F8C912", acc);
    }

    @Test
    @DisplayName("Tx number truncates UUID to 11 hex chars (1С doc-number convention)")
    void txNumberShort() {
        UUID id = UUID.fromString("c7d8e9f0-1234-5678-9abc-def012345678");
        assertEquals("C7D8E9F0123", OneCExchangeFormatter.shortenTxNumber(id));
    }
}
