package com.sber.dlmm.transaction.export;

import com.sber.dlmm.common.enums.TransactionType;
import com.sber.dlmm.transaction.entity.Transaction;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

/**
 * Sprint 5 #5.11 — 1С банк-клиент v1.03 exchange-format generator.
 *
 * <p>1С Бухгалтерия (8.3 line) imports bank statements via the
 * "1CClientBankExchange" text format, NOT XML — the format is a flat
 * pseudo-INI with section markers, defined in the public Microsoft Word
 * spec "Стандарт обмена данными между 1С: Предприятие и системами
 * Клиент-Банк" (last v1.03, 2008, still used by every Russian bank).
 *
 * <p>Why we ship this format instead of generic CSV/XML:
 * <ul>
 *   <li>Every Russian corp accountant uses 1С 8.3 (~90% market share).</li>
 *   <li>The CSV from Sprint 4 #4.4 needs hand-mapping into 1С — accountant
 *       headache. 1CClientBankExchange goes straight into the import wizard.</li>
 *   <li>Standard accepts Cyrillic in field values when {@code Кодировка=Windows}
 *       declared (Windows-1251).</li>
 * </ul>
 *
 * <p>Output format (high-level):
 * <pre>
 * 1CClientBankExchange
 * ВерсияФормата=1.03
 * Кодировка=Windows
 * Отправитель=DLMM Platform
 * Получатель=
 * ДатаСоздания=DD.MM.YYYY
 * ВремяСоздания=HH:MM:SS
 * ДатаНачала=DD.MM.YYYY
 * ДатаКонца=DD.MM.YYYY
 * РасчСчет=&lt;requesting-user-account&gt;
 * СекцияДокумент=Платежное поручение
 * Номер=&lt;tx-id-short&gt;
 * Дата=DD.MM.YYYY
 * Сумма=NNN.NN
 * ПлательщикСчет=&lt;from-account&gt;
 * ПолучательСчет=&lt;to-account&gt;
 * НазначениеПлатежа=DLMM swap tx:UUID type:SWAP fee:N
 * КонецДокумента
 * ...
 * КонецФайла
 * </pre>
 *
 * <p>For prototype, account numbers are stubbed as
 * {@code DLMM-USR-&lt;uuid-prefix&gt;} — real 20-digit РасчСчет numbers come
 * from the SBBOL integration in Sprint 5 #5.13 (which populates
 * {@code user_balances.sbbol_account_id} per the SBBOL design memo §3).
 * Accountant test imports will warn about the non-standard account format
 * but the line items themselves import cleanly.
 *
 * <p>Section ordering AND tag names are case-sensitive and must match
 * the spec exactly — 1С rejects the file silently on a typo.
 */
public final class OneCExchangeFormatter {

    private static final String LINE_SEP = "\r\n"; // 1С expects CRLF (Windows-style)
    private static final DateTimeFormatter D = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final DateTimeFormatter T = DateTimeFormatter.ofPattern("HH:mm:ss");

    private static final String PLATFORM_SENDER = "DLMM Platform";

    /** Non-instantiable: stateless formatter exposed via static methods. */
    private OneCExchangeFormatter() {}

    /**
     * Renders the full 1CClientBankExchange document for the given
     * transaction set. {@code fromDate}/{@code toDate} default to the
     * earliest/latest tx in the list if null.
     *
     * @param targetUserId requester (becomes РасчСчет anchor account)
     * @param transactions transactions to include — typically Sprint 4
     *        #4.4's findForReport result (capped at 10k)
     * @param fromDate report range start (null = derive from data)
     * @param toDate   report range end (null = today)
     * @param createdAt timestamp for the file header (= "now" in practice)
     */
    public static String format(UUID targetUserId,
                                 List<Transaction> transactions,
                                 LocalDate fromDate,
                                 LocalDate toDate,
                                 LocalDateTime createdAt) {
        if (targetUserId == null) throw new IllegalArgumentException("targetUserId required");
        if (transactions == null) throw new IllegalArgumentException("transactions required");
        if (createdAt == null) createdAt = LocalDateTime.now();

        // Range defaults — earliest tx → fromDate, latest → toDate.
        LocalDate effectiveFrom = fromDate != null ? fromDate :
                transactions.stream()
                        .map(Transaction::getCreatedAt)
                        .filter(java.util.Objects::nonNull)
                        .min(LocalDateTime::compareTo)
                        .map(LocalDateTime::toLocalDate)
                        .orElse(createdAt.toLocalDate());
        LocalDate effectiveTo = toDate != null ? toDate : createdAt.toLocalDate();

        String anchorAccount = stubAccount(targetUserId);

        StringBuilder sb = new StringBuilder(4096);
        // ── Header ──
        sb.append("1CClientBankExchange").append(LINE_SEP);
        sb.append("ВерсияФормата=1.03").append(LINE_SEP);
        sb.append("Кодировка=Windows").append(LINE_SEP);
        sb.append("Отправитель=").append(PLATFORM_SENDER).append(LINE_SEP);
        sb.append("Получатель=").append(LINE_SEP);
        sb.append("ДатаСоздания=").append(D.format(createdAt)).append(LINE_SEP);
        sb.append("ВремяСоздания=").append(T.format(createdAt)).append(LINE_SEP);
        sb.append("ДатаНачала=").append(D.format(effectiveFrom)).append(LINE_SEP);
        sb.append("ДатаКонца=").append(D.format(effectiveTo)).append(LINE_SEP);
        sb.append("РасчСчет=").append(anchorAccount).append(LINE_SEP);

        // ── Documents (one section per transaction) ──
        for (Transaction t : transactions) {
            appendDocument(sb, t, anchorAccount);
        }

        // ── Footer ──
        sb.append("КонецФайла").append(LINE_SEP);
        return sb.toString();
    }

    /**
     * Appends one {@code СекцияДокумент} … {@code КонецДокумента} block for a
     * single transaction. The anchor (requester) account is placed on the
     * payer or payee side according to the transaction's direction; the other
     * side is a derived stub (pool or treasury).
     *
     * @param sb            buffer being built
     * @param t             transaction to render as one document
     * @param anchorAccount the requester's stub account (one side of every row)
     */
    private static void appendDocument(StringBuilder sb, Transaction t, String anchorAccount) {
        sb.append("СекцияДокумент=Платежное поручение").append(LINE_SEP);
        sb.append("Номер=").append(shortenTxNumber(t.getId())).append(LINE_SEP);
        LocalDateTime when = t.getConfirmedAt() != null ? t.getConfirmedAt() : t.getCreatedAt();
        if (when != null) {
            sb.append("Дата=").append(D.format(when)).append(LINE_SEP);
        }
        sb.append("Сумма=").append(formatAmount(t)).append(LINE_SEP);

        // Direction: SWAP/TRANSFER/CLAIM_FEE — anchor account is always
        // one side; the other side is a stub derived from counterparty
        // (pool, treasury account, or recipient).
        TransactionType type = t.getTxType();
        String counterAccount = stubCounterAccount(t);
        if (isOutbound(type)) {
            sb.append("ПлательщикСчет=").append(anchorAccount).append(LINE_SEP);
            sb.append("ПолучательСчет=").append(counterAccount).append(LINE_SEP);
        } else {
            sb.append("ПлательщикСчет=").append(counterAccount).append(LINE_SEP);
            sb.append("ПолучательСчет=").append(anchorAccount).append(LINE_SEP);
        }
        sb.append("НазначениеПлатежа=").append(paymentPurpose(t)).append(LINE_SEP);
        sb.append("КонецДокумента").append(LINE_SEP);
    }

    /**
     * Classifies a transaction type as outbound (money leaves the anchor
     * account) or inbound, which decides payer/payee placement and which
     * amount field is reported. Null defaults to outbound (conservative).
     *
     * @param type transaction type, possibly {@code null}
     * @return {@code true} for SWAP / ADD_LIQUIDITY / TRANSFER / BURN /
     *         WITHDRAW (and null); {@code false} for REMOVE_LIQUIDITY /
     *         CLAIM_FEE / MINT / DEPOSIT
     */
    private static boolean isOutbound(TransactionType type) {
        if (type == null) return true;
        return switch (type) {
            // From anchor account: swap (we sent the input token), add liquidity,
            // outbound transfer, burn (we destroyed our balance), withdraw.
            case SWAP, ADD_LIQUIDITY, TRANSFER, BURN, WITHDRAW -> true;
            // To anchor: liquidity removal, fee claim, mint of new tokens, deposit-in.
            case REMOVE_LIQUIDITY, CLAIM_FEE, MINT, DEPOSIT -> false;
        };
    }

    /**
     * 1С documents amounts in roubles with a dot decimal separator
     * regardless of system locale ({@code Сумма=10000.50}). Our amounts
     * are stored as bare longs (token-native units) — for the report we
     * present them as-is with .00, since accountant ETL maps the
     * DLMM unit to whatever subaccount they choose.
     *
     * @param t transaction whose amount to format
     * @return the amount as a {@code NNN.00} string (amount-in for outbound,
     *         amount-out for inbound, falling back to fee if both are null)
     */
    private static String formatAmount(Transaction t) {
        // Prefer amount_in for outbound, amount_out for inbound to match the
        // direction the accountant sees. fee_amount goes into НазначениеПлатежа text.
        Long amount = isOutbound(t.getTxType()) ? t.getAmountIn() : t.getAmountOut();
        if (amount == null) amount = t.getFeeAmount();
        return amount + ".00";
    }

    /**
     * Builds the {@code НазначениеПлатежа} (payment purpose) free-text line,
     * embedding tx id, type, and optionally pool / fee / idempotency key.
     * Strips CR/LF because 1С rejects line breaks inside a field value.
     *
     * @param t transaction to describe
     * @return a single-line, line-break-free purpose string
     */
    private static String paymentPurpose(Transaction t) {
        StringBuilder p = new StringBuilder(256);
        p.append("DLMM tx:").append(t.getId());
        p.append(" type:").append(t.getTxType());
        if (t.getPoolId() != null) p.append(" pool:").append(t.getPoolId());
        if (t.getFeeAmount() > 0) p.append(" fee:").append(t.getFeeAmount());
        if (t.getIdempotencyKey() != null && !t.getIdempotencyKey().isBlank()) {
            p.append(" idem:").append(t.getIdempotencyKey());
        }
        // 1С rejects line breaks within a field value — strip just in case.
        return p.toString().replace('\r', ' ').replace('\n', ' ');
    }

    /**
     * 8-char user-prefix account stub. Real СберБизнес РасчСчет numbers
     * (20 digits, sub-account encoding) arrive with the SBBOL integration
     * (Sprint 5 #5.13). For now accountant sees "DLMM-USR-A3F8C912" and
     * uses 1С free-text matching.
     *
     * @param userId user to derive the stub account from
     * @return {@code DLMM-USR-} + the uppercased first 8 chars of the UUID
     */
    static String stubAccount(UUID userId) {
        return "DLMM-USR-" + userId.toString().substring(0, 8).toUpperCase();
    }

    /**
     * Derives the counterparty stub account for a transaction: a pool account
     * when the row has a pool, otherwise the shared treasury label.
     *
     * @param t transaction to derive the counter account from
     * @return {@code DLMM-POOL-<prefix>} when a pool is present, else
     *         {@code DLMM-TREASURY}
     */
    private static String stubCounterAccount(Transaction t) {
        if (t.getPoolId() != null) {
            return "DLMM-POOL-" + t.getPoolId().toString().substring(0, 8).toUpperCase();
        }
        return "DLMM-TREASURY";
    }

    /**
     * 1С doc number — 6-digit recommendation but accepts up to 11.
     * Use first 11 chars of the UUID (no dashes) for uniqueness.
     *
     * @param id transaction id, possibly {@code null}
     * @return the uppercased first 11 dash-free chars of the UUID, or
     *         {@code "000000"} when {@code id} is null
     */
    static String shortenTxNumber(UUID id) {
        if (id == null) return "000000";
        return id.toString().replace("-", "").substring(0, 11).toUpperCase();
    }
}
