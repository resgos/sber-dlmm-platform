package com.sber.dlmm.notification.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins the notification amount formatting (audit B2): raw ×10⁴ integers render as
 * human amounts with RU grouping + ≤4 decimals, and an unknown token degrades to a
 * neutral label rather than failing. The symbol/pool lookups hit the DB and are
 * exercised by the listener path, not here.
 */
class NotificationFormatterTest {

    // amount() never touches the JdbcTemplate, so null is fine for these cases.
    private final NotificationFormatter f = new NotificationFormatter(null);

    /** RU grouping uses a (narrow) no-break space depending on the JDK/CLDR — normalise
     *  U+00A0 / U+202F to a plain space so the assertions are stable. */
    private static String norm(String s) {
        return s.replaceAll("[\\u00A0\\u202F]", " ");
    }

    @Test
    void scalesRawByTenThousandAndTrimsTrailingZeros() {
        assertEquals("0,5", norm(f.amount(5_000)));            // 5000 / 1e4
        assertEquals("1", norm(f.amount(10_000)));             // exactly one token
        assertEquals("1 234,56", norm(f.amount(12_345_600)));  // 1234.56, grouping + comma
        assertEquals("0", norm(f.amount(0)));
    }

    @Test
    void groupsLargeAmountsRuStyle() {
        assertEquals("2 575 583 681", norm(f.amount(25_755_836_810_000L)));
    }

    @Test
    void unknownTokenIdFallsBackWithoutDbHit() {
        assertEquals("токен", f.tokenSymbol(null));
        assertEquals("токен", f.tokenSymbol(""));
        assertEquals("токен", f.tokenSymbol("null"));
    }
}
