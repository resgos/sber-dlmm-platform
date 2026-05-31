package com.sber.dlmm.oracle.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.sber.dlmm.oracle.dto.MarketQuote;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Fetches REAL spot prices (in RUB) from free, no-key public APIs:
 * <ul>
 *   <li><b>CoinGecko</b> {@code simple/price} — crypto (BTC, ETH …) in RUB + 24h change.</li>
 *   <li><b>Bank of Russia</b> daily JSON ({@code cbr-xml-daily.ru}) — FX (USD/EUR/CNY) in RUB.</li>
 *   <li><b>Bank of Russia</b> {@code xml_metall.asp} — precious metals (gold/silver/platinum/
 *       palladium) in RUB per gram.</li>
 * </ul>
 *
 * <p>MOEX ISS (Russian equities/indices) is intentionally NOT used here: it is
 * geo-restricted from the platform's hosting environment (verified
 * unreachable — connect timeout). Those assets stay on the labelled synthetic
 * walk in {@code PriceOracleService}. Swap in a MOEX feed (or a reachable mirror)
 * when the deployment sits on a RU IP.
 *
 * <p>Every method fails soft: on any error it logs and returns an empty map, so a
 * transient outage leaves the last good price in place (the oracle's staleness
 * guard still applies).
 */
@Component
public class MarketDataClient {

    private static final Logger log = LoggerFactory.getLogger(MarketDataClient.class);
    private static final MathContext MC = new MathContext(18, RoundingMode.HALF_UP);
    private static final DateTimeFormatter CBR_DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final RestTemplate rt;
    private final ObjectMapper json = new ObjectMapper();
    private final XmlMapper xml = new XmlMapper();
    private final String coingeckoUrl;
    private final String cbrFxUrl;
    private final String cbrMetalsUrl;

    public MarketDataClient(
            RestTemplateBuilder builder,
            @Value("${dlmm.market.coingecko-url:https://api.coingecko.com/api/v3/simple/price}") String coingeckoUrl,
            @Value("${dlmm.market.cbr-fx-url:https://www.cbr-xml-daily.ru/daily_json.js}") String cbrFxUrl,
            @Value("${dlmm.market.cbr-metals-url:https://www.cbr.ru/scripts/xml_metall.asp}") String cbrMetalsUrl) {
        this.rt = builder
                .setConnectTimeout(Duration.ofSeconds(6))
                .setReadTimeout(Duration.ofSeconds(12))
                .build();
        this.coingeckoUrl = coingeckoUrl;
        this.cbrFxUrl = cbrFxUrl;
        this.cbrMetalsUrl = cbrMetalsUrl;
    }

    /** CoinGecko coin id (e.g. "bitcoin") → quote (price in RUB + 24h %). Empty on failure. */
    public Map<String, MarketQuote> fetchCryptoRub(Collection<String> coinIds) {
        if (coinIds.isEmpty()) return Map.of();
        try {
            String url = coingeckoUrl + "?ids=" + String.join(",", coinIds)
                    + "&vs_currencies=rub&include_24hr_change=true";
            JsonNode root = json.readTree(rt.getForObject(url, String.class));
            Map<String, MarketQuote> out = new HashMap<>();
            for (String id : coinIds) {
                JsonNode n = root.get(id);
                if (n != null && n.hasNonNull("rub")) {
                    out.put(id, new MarketQuote(
                            n.get("rub").decimalValue(),
                            n.path("rub_24h_change").isNumber()
                                    ? n.get("rub_24h_change").decimalValue().setScale(4, RoundingMode.HALF_UP)
                                    : BigDecimal.ZERO));
                }
            }
            return out;
        } catch (Exception e) {
            log.warn("CoinGecko fetch failed: {}", e.toString());
            return Map.of();
        }
    }

    /** CBR char-code (USD/EUR/CNY) → quote (RUB per 1 unit + 24h % vs the prior fixing). */
    public Map<String, MarketQuote> fetchFxRub(Collection<String> charCodes) {
        if (charCodes.isEmpty()) return Map.of();
        try {
            JsonNode valute = json.readTree(rt.getForObject(cbrFxUrl, String.class)).path("Valute");
            Map<String, MarketQuote> out = new HashMap<>();
            for (String cc : charCodes) {
                JsonNode n = valute.get(cc);
                if (n != null && n.hasNonNull("Value")) {
                    BigDecimal nominal = n.path("Nominal").isNumber() ? n.get("Nominal").decimalValue() : BigDecimal.ONE;
                    if (nominal.signum() == 0) nominal = BigDecimal.ONE;
                    BigDecimal val = n.get("Value").decimalValue().divide(nominal, MC);
                    BigDecimal prev = n.path("Previous").isNumber()
                            ? n.get("Previous").decimalValue().divide(nominal, MC) : val;
                    out.put(cc, new MarketQuote(val, pctChange(prev, val)));
                }
            }
            return out;
        } catch (Exception e) {
            log.warn("CBR FX fetch failed: {}", e.toString());
            return Map.of();
        }
    }

    /** CBR metal code (1=gold, 2=silver, 3=platinum, 4=palladium) → quote (RUB/gram + 24h %). */
    public Map<Integer, MarketQuote> fetchMetalsRub() {
        try {
            String to = LocalDate.now().format(CBR_DATE);
            String from = LocalDate.now().minusDays(12).format(CBR_DATE);
            String url = cbrMetalsUrl + "?date_req1=" + from + "&date_req2=" + to;
            // CBR ships windows-1251, but Buy/Sell are ASCII numerics, so charset is irrelevant here.
            JsonNode root = xml.readTree(rt.getForObject(url, String.class));
            JsonNode records = root.path("Record");
            Map<Integer, List<BigDecimal>> byCode = new HashMap<>();
            Iterator<JsonNode> it = records.isArray() ? records.elements() : List.of(records).iterator();
            while (it.hasNext()) {
                JsonNode r = it.next();
                int code = r.path("Code").asInt(0);
                BigDecimal price = parseCbrNumber(r.path("Buy").asText(null));
                if (code >= 1 && price != null) {
                    byCode.computeIfAbsent(code, k -> new ArrayList<>()).add(price);
                }
            }
            Map<Integer, MarketQuote> out = new HashMap<>();
            for (Map.Entry<Integer, List<BigDecimal>> e : byCode.entrySet()) {
                List<BigDecimal> s = e.getValue(); // document order = date-ascending
                BigDecimal latest = s.get(s.size() - 1);
                BigDecimal prev = s.size() >= 2 ? s.get(s.size() - 2) : latest;
                out.put(e.getKey(), new MarketQuote(latest, pctChange(prev, latest)));
            }
            return out;
        } catch (Exception e) {
            log.warn("CBR metals fetch failed: {}", e.toString());
            return Map.of();
        }
    }

    /** Parse a CBR numeric like "10 464,39" (comma decimal, nbsp/space thousands). */
    static BigDecimal parseCbrNumber(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return new BigDecimal(s.replace(" ", "").replace(" ", "").replace(',', '.'));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /** 24h % change, 4dp; 0 when the prior value is missing or non-positive. */
    static BigDecimal pctChange(BigDecimal prev, BigDecimal current) {
        if (prev == null || prev.signum() <= 0 || current == null) return BigDecimal.ZERO;
        return current.subtract(prev).divide(prev, MC)
                .multiply(BigDecimal.valueOf(100))
                .setScale(4, RoundingMode.HALF_UP);
    }
}
