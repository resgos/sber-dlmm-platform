package com.sber.dlmm.admin.config;

import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Increases Jackson's max number length when admin-bff parses downstream
 * responses. Pool-engine currently emits {@code currentPrice} computed as
 * {@code basePrice * (1 + binStep/10000)^activeBinId} — and seed data uses
 * {@code activeBinId = 8_388_608} (2^23, Meteora-style "middle bin" id),
 * which makes the resulting BigDecimal a ~1800-digit number.
 *
 * Jackson 2.15+ defaults to a 1000-char number cap and rejects the field
 * with {@code JsonDecodingException: Number length (1842) exceeds the
 * maximum length (1000)} — surfacing as a silently-empty pools list on
 * the admin dashboard.
 *
 * The architectural fix is in pool-engine ({@code BinMath.binPrice} should
 * either treat {@code activeBinId} as an offset from a calibration bin, or
 * normalise during {@code toPoolResponse}). Until that lands, accept the
 * outsized numbers here so the dashboard can render.
 */
@Configuration
public class JacksonConfig {

    private static final int MAX_NUMBER_LEN = 10_000;

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer relaxJacksonNumberLimits() {
        return builder -> builder.postConfigurer(this::applyConstraints);
    }

    private void applyConstraints(ObjectMapper mapper) {
        StreamReadConstraints constraints = StreamReadConstraints.builder()
                .maxNumberLength(MAX_NUMBER_LEN)
                .build();
        mapper.getFactory().setStreamReadConstraints(constraints);
    }
}
