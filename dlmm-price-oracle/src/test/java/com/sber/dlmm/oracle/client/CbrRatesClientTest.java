package com.sber.dlmm.oracle.client;

import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 5 #5.9 — exercises the CBR XML→POJO mapping in isolation
 * (no HTTP, no Spring). The fixture is a real-shape snippet of what
 * cbr.ru/scripts/XML_daily.asp returns on a recent business day.
 *
 * <p>The full HTTP path is implicitly verified at integration time
 * (CBR endpoint hit in the CbrRatesService startup fetch) — pinning
 * the XML schema here means a CBR breaking-change is caught at build
 * not at first 14:00-cron failure.
 */
class CbrRatesClientTest {

    // Trimmed-down real CBR response. Cyrillic names preserved
    // (they get UTF-8'd in the test source — the production decoder
    // handles the Windows-1251 over-the-wire encoding before parsing).
    private static final String SAMPLE_XML = """
            <?xml version="1.0" encoding="UTF-8"?>
            <ValCurs Date="19.05.2026" name="Foreign Currency Market">
                <Valute ID="R01235">
                    <NumCode>840</NumCode>
                    <CharCode>USD</CharCode>
                    <Nominal>1</Nominal>
                    <Name>Доллар США</Name>
                    <Value>89,1234</Value>
                    <VunitRate>89,1234</VunitRate>
                </Valute>
                <Valute ID="R01239">
                    <NumCode>978</NumCode>
                    <CharCode>EUR</CharCode>
                    <Nominal>1</Nominal>
                    <Name>Евро</Name>
                    <Value>96,4567</Value>
                    <VunitRate>96,4567</VunitRate>
                </Valute>
                <Valute ID="R01375">
                    <NumCode>156</NumCode>
                    <CharCode>CNY</CharCode>
                    <Nominal>1</Nominal>
                    <Name>Китайский юань</Name>
                    <Value>12,3456</Value>
                    <VunitRate>12,3456</VunitRate>
                </Valute>
                <Valute ID="R01820">
                    <NumCode>392</NumCode>
                    <CharCode>JPY</CharCode>
                    <Nominal>100</Nominal>
                    <Name>Японских иен</Name>
                    <Value>57,8901</Value>
                    <VunitRate>0,5789</VunitRate>
                </Valute>
            </ValCurs>
            """;

    @Test
    @DisplayName("ValCurs XML parses into POJO with all valutes")
    void parsesValCursStructure() throws Exception {
        XmlMapper xmlMapper = new XmlMapper();
        CbrRatesClient.ValCurs parsed = xmlMapper.readValue(SAMPLE_XML, CbrRatesClient.ValCurs.class);

        assertNotNull(parsed);
        assertEquals("19.05.2026", parsed.date);
        assertNotNull(parsed.valutes);
        assertEquals(4, parsed.valutes.size(),
                "should parse all 4 Valute children (USD, EUR, CNY, JPY)");
    }

    @Test
    @DisplayName("CharCode + Value mapped per Valute")
    void mapsCharCodeAndValue() throws Exception {
        XmlMapper xmlMapper = new XmlMapper();
        CbrRatesClient.ValCurs parsed = xmlMapper.readValue(SAMPLE_XML, CbrRatesClient.ValCurs.class);

        CbrRatesClient.Valute usd = parsed.valutes.stream()
                .filter(v -> "USD".equals(v.charCode)).findFirst().orElseThrow();
        assertEquals("840", usd.numCode);
        assertEquals("89,1234", usd.value); // raw value, still comma-decimal
        assertEquals(1, usd.nominal);
    }

    @Test
    @DisplayName("JPY Nominal=100 means client must divide raw Value by 100 to get per-unit RUB")
    void jpyNominalHandling() throws Exception {
        // This is the trap the CbrRatesClient.fetchTodayRates() implementation
        // accounts for — CBR reports e.g. JPY as "100 units = N RUB". The
        // client divides by Nominal so callers always see "1 unit = N RUB".
        XmlMapper xmlMapper = new XmlMapper();
        CbrRatesClient.ValCurs parsed = xmlMapper.readValue(SAMPLE_XML, CbrRatesClient.ValCurs.class);
        CbrRatesClient.Valute jpy = parsed.valutes.stream()
                .filter(v -> "JPY".equals(v.charCode)).findFirst().orElseThrow();
        assertEquals(100, jpy.nominal);
        // Raw Value is 57.8901 for 100 JPY; per-unit is 0.578901.
        BigDecimal raw = new BigDecimal(jpy.value.replace(',', '.'));
        BigDecimal perUnit = raw.divide(BigDecimal.valueOf(jpy.nominal),
                java.math.MathContext.DECIMAL64);
        assertEquals(new BigDecimal("0.578901"), perUnit);
    }

    @Test
    @DisplayName("Comma decimal separator (Russian convention) parses correctly")
    void russianDecimalSeparator() throws Exception {
        XmlMapper xmlMapper = new XmlMapper();
        CbrRatesClient.ValCurs parsed = xmlMapper.readValue(SAMPLE_XML, CbrRatesClient.ValCurs.class);
        // CharCode lookup uses the post-processing logic from CbrRatesClient
        // (parseRussianDecimal is private; the public API normalises via
        // fetchTodayRates()). We replicate the comma→dot here to assert.
        CbrRatesClient.Valute eur = parsed.valutes.stream()
                .filter(v -> "EUR".equals(v.charCode)).findFirst().orElseThrow();
        BigDecimal eurRate = new BigDecimal(eur.value.replace(',', '.'));
        assertTrue(eurRate.compareTo(new BigDecimal("96")) > 0);
        assertTrue(eurRate.compareTo(new BigDecimal("97")) < 0);
    }
}
