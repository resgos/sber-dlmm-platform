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

    /**
     * Builds the client with bounded timeouts and the three upstream URLs.
     *
     * <p>WHY tolerant timeouts (6s connect / 12s read): these public APIs are
     * occasionally slow but the fetch runs on a 2-minute scheduler, so a
     * generous-but-capped wait beats both a premature failure and an unbounded
     * hang. Each URL is overridable via config to allow pointing at a mirror.
     *
     * @param builder      Spring's builder used to make the timeout-configured
     *                     {@link RestTemplate}
     * @param coingeckoUrl CoinGecko {@code simple/price} base URL
     * @param cbrFxUrl     CBR daily FX JSON URL ({@code cbr-xml-daily.ru})
     * @param cbrMetalsUrl CBR precious-metals XML URL ({@code xml_metall.asp})
     */
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

    /**
     * CoinGecko coin id (e.g. "bitcoin") → quote (price in RUB + 24h %). Empty
     * on failure.
     *
     * <p>WHAT: one batched {@code simple/price} call for all requested ids,
     * reading the {@code rub} price and {@code rub_24h_change} fields.
     *
     * <p>WHY fail-soft (empty map, not throw): a transient CoinGecko outage
     * must leave the oracle's last good crypto prices in place rather than
     * wiping them; the caller upserts only the entries that come back.
     *
     * @param coinIds CoinGecko coin ids to price; an empty collection short-
     *                circuits to an empty result without a network call
     * @return map of coin id → {@link MarketQuote}; empty on any error or for
     *         ids CoinGecko omits
     */
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

    /**
     * CBR char-code (USD/EUR/CNY) → quote (RUB per 1 unit + 24h % vs the prior
     * fixing).
     *
     * <p>WHAT: reads the CBR daily JSON, and for each requested char-code
     * normalises {@code Value / Nominal} to a per-unit RUB price and derives
     * the 24h change from the {@code Previous} fixing.
     *
     * <p>WHY normalise by Nominal: CBR quotes some currencies per 10/100 units
     * (e.g. JPY), so dividing by {@code Nominal} guarantees callers always get
     * "1 unit = N RUB". Fails soft to an empty map on any error.
     *
     * @param charCodes CBR currency char-codes to fetch; empty short-circuits
     *                  to an empty result
     * @return map of char-code → {@link MarketQuote}; empty on any error or for
     *         codes absent from the response
     */
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

    /**
     * CBR metal code (1=gold, 2=silver, 3=platinum, 4=palladium) → quote
     * (RUB/gram + 24h %).
     *
     * <p>WHAT: requests a ~12-day window of the metals XML, groups the
     * {@code Buy} prices per metal code in document (date-ascending) order, and
     * reports the latest value plus its change vs the prior record.
     *
     * <p>WHY a date range rather than a single day: CBR only publishes metal
     * fixings on banking days, so asking for one day can return nothing on a
     * weekend/holiday; a short window guarantees at least one (usually two)
     * records to derive both the latest price and a 24h delta. Fails soft to an
     * empty map on any error.
     *
     * @return map of metal code → {@link MarketQuote} (RUB per gram); empty on
     *         any error
     */
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

    /**
     * Parse a CBR numeric like "10 464,39" (comma decimal, nbsp/space
     * thousands).
     *
     * <p>WHY all the stripping: CBR formats numbers the Russian way — a comma
     * decimal separator and space/no-break-space thousands grouping — none of
     * which {@code BigDecimal} accepts, so we remove the separators before
     * parsing. Returns {@code null} (rather than throwing) on blank/garbage so
     * a single bad record is skipped by the caller.
     *
     * @param s raw CBR numeric text; may be {@code null}/blank
     * @return the parsed value, or {@code null} if absent or unparseable
     */
    static BigDecimal parseCbrNumber(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return new BigDecimal(s.replace(" ", "").replace(" ", "").replace(',', '.'));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /**
     * 24h % change, 4dp; 0 when the prior value is missing or non-positive.
     *
     * <p>WHY the guard: dividing by a missing or non-positive {@code prev}
     * would NPE or produce a meaningless percentage, so those cases collapse to
     * a neutral 0% rather than propagating an error into the feed.
     *
     * @param prev    the prior reference value (denominator)
     * @param current the latest value
     * @return {@code (current - prev) / prev * 100} rounded to 4dp, or
     *         {@link BigDecimal#ZERO} when {@code prev} is null/non-positive or
     *         {@code current} is null
     */
    static BigDecimal pctChange(BigDecimal prev, BigDecimal current) {
        if (prev == null || prev.signum() <= 0 || current == null) return BigDecimal.ZERO;
        return current.subtract(prev).divide(prev, MC)
                .multiply(BigDecimal.valueOf(100))
                .setScale(4, RoundingMode.HALF_UP);
    }
}
