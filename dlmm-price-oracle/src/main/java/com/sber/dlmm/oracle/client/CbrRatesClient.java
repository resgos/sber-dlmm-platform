package com.sber.dlmm.oracle.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.RequestEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.Charset;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Sprint 5 #5.9 — official Bank of Russia (ЦБ РФ) daily FX rates client.
 *
 * <p>CBR publishes RUB exchange rates as XML at
 * {@code https://www.cbr.ru/scripts/XML_daily.asp}. The endpoint is free,
 * authentication-less, public-domain, and has been stable since 2003. Rates
 * are posted once per banking day around 12:00 MSK for the NEXT working day.
 *
 * <p>Why the official endpoint (not the convenient {@code cbr-xml-daily.ru}
 * JSON mirror): the JSON mirror is a community proxy — Sber compliance flags
 * any third-party data hop as supply-chain risk. Official cbr.ru XML is in
 * the trust boundary.
 *
 * <p>The XML is Windows-1251 encoded (legacy) — RestTemplate uses
 * {@link org.springframework.http.converter.StringHttpMessageConverter} which
 * defaults to ISO-8859-1, which mangles the Cyrillic currency names. We
 * explicitly request the response as bytes + decode with Windows-1251 before
 * handing to Jackson.
 *
 * <p>Cached for 24h by {@code CbrRatesService} (CBR doesn't change rates more
 * than once per day). On weekends/holidays the call returns the most recent
 * working-day rates — caller is unaffected.
 */
@Component
@Slf4j
public class CbrRatesClient {

    private static final Charset CBR_CHARSET = Charset.forName("Windows-1251");

    private final RestTemplate restTemplate;
    private final XmlMapper xmlMapper;
    private final String cbrUrl;

    public CbrRatesClient(@Value("${dlmm.cbr.url:https://www.cbr.ru/scripts/XML_daily.asp}") String cbrUrl,
                          RestTemplateBuilder builder) {
        this.cbrUrl = cbrUrl;
        this.restTemplate = builder
                .setConnectTimeout(Duration.ofSeconds(5))
                .setReadTimeout(Duration.ofSeconds(10))
                .build();
        // jackson-dataformat-xml default config — works for the simple
        // CBR schema without extra configuration.
        this.xmlMapper = new XmlMapper();
    }

    /**
     * Fetches today's official rates from CBR. Returns a map keyed by
     * currency CharCode (USD, EUR, CNY, ...) with the per-unit RUB price
     * already normalised (CBR reports e.g. JPY as "100 JPY = X RUB" —
     * we divide by Nominal so callers always see "1 unit = N RUB").
     *
     * @throws RuntimeException if the network call fails or XML can't be
     *     parsed — caller (CbrRatesService) catches and logs; the stale
     *     cached rates remain in price_feeds.
     */
    public Map<String, BigDecimal> fetchTodayRates() {
        RequestEntity<Void> request = RequestEntity
                .method(HttpMethod.GET, URI.create(cbrUrl))
                .accept(MediaType.APPLICATION_XML)
                .header(HttpHeaders.ACCEPT_CHARSET, CBR_CHARSET.name())
                .build();
        // Fetch as bytes — CBR uses Windows-1251 in the XML declaration but
        // RestTemplate's default StringHttpMessageConverter ignores it.
        byte[] body = restTemplate.exchange(request, byte[].class).getBody();
        if (body == null || body.length == 0) {
            throw new IllegalStateException("CBR returned empty body");
        }
        String xml = new String(body, CBR_CHARSET);

        try {
            ValCurs parsed = xmlMapper.readValue(xml, ValCurs.class);
            if (parsed.valutes == null || parsed.valutes.isEmpty()) {
                throw new IllegalStateException("CBR response had no <Valute> entries");
            }
            return parsed.valutes.stream()
                    .filter(v -> v.charCode != null && v.value != null && v.nominal != null && v.nominal > 0)
                    .collect(Collectors.toMap(
                            v -> v.charCode.trim().toUpperCase(),
                            // CBR uses comma as decimal separator (Russian convention)
                            // — replace before parsing. Nominal=100 for JPY etc. — normalise to per-unit.
                            v -> parseRussianDecimal(v.value)
                                    .divide(BigDecimal.valueOf(v.nominal),
                                            java.math.MathContext.DECIMAL64),
                            // CBR shouldn't have duplicate CharCodes but be defensive
                            (a, b) -> {
                                log.warn("Duplicate CBR currency code, keeping first");
                                return a;
                            }
                    ));
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to parse CBR XML response: " + ex.getMessage(), ex);
        }
    }

    private static BigDecimal parseRussianDecimal(String raw) {
        // CBR XML uses "89,1234" (comma as decimal separator).
        return new BigDecimal(raw.trim().replace(',', '.'));
    }

    // ── XML POJOs — matches CBR schema https://www.cbr.ru/scripts/XML_daily.asp ──

    @Data
    @NoArgsConstructor
    @JacksonXmlRootElement(localName = "ValCurs")
    public static class ValCurs {
        @JacksonXmlProperty(isAttribute = true, localName = "Date")
        public String date;
        @JacksonXmlProperty(isAttribute = true, localName = "name")
        public String name;
        @JacksonXmlElementWrapper(useWrapping = false)
        @JacksonXmlProperty(localName = "Valute")
        public List<Valute> valutes;
    }

    @Data
    @NoArgsConstructor
    public static class Valute {
        @JacksonXmlProperty(isAttribute = true, localName = "ID")
        public String id;
        @JsonProperty("NumCode") public String numCode;
        @JsonProperty("CharCode") public String charCode;
        @JsonProperty("Nominal") public Integer nominal;
        @JsonProperty("Name") public String name;
        @JsonProperty("Value") public String value;
        // VunitRate exists in modern CBR responses but we re-derive from Value/Nominal
        // for backward compatibility with older XML snapshots.
        @JsonProperty("VunitRate") public String vunitRate;
    }
}
