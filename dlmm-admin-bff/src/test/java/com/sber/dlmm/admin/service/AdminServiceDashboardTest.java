package com.sber.dlmm.admin.service;

import com.sber.dlmm.admin.dto.DashboardResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the contract of {@link AdminService#getDashboard()} after the
 * BFF-stability refactor:
 *
 *  - parses Spring Data Page-shaped responses ({@code {"content": [...]}})
 *    correctly instead of trying to deserialise the page object as a
 *    bare List (the old bug);
 *  - degrades to zeros when a single downstream returns HTTP 5xx —
 *    other counters still aggregate from the healthy services;
 *  - degrades to zeros when a single downstream returns the wrong
 *    JSON shape — no NPE, no 10-second stall;
 *  - reads {@code kycStatus == "VERIFIED"} (not the old
 *    {@code verified}/{@code kycVerified} keys that the prior code
 *    looked at and never matched);
 *  - aggregates pool TVL from {@code totalTvlY} and fee revenue from
 *    {@code totalFeesCollectedY} (matches actual pool-engine DTO field
 *    names).
 *
 * Uses a WebClient {@code exchangeFunction} stub so we don't boot a server.
 */
class AdminServiceDashboardTest {

    private AdminService adminService;
    private final AtomicReference<Mono<ClientResponse>> userResponse = new AtomicReference<>();
    private final AtomicReference<Mono<ClientResponse>> poolResponse = new AtomicReference<>();
    private final AtomicReference<Mono<ClientResponse>> txResponse = new AtomicReference<>();

    @BeforeEach
    void setUp() throws Exception {
        WebClient userClient = stubClient(userResponse);
        WebClient poolClient = stubClient(poolResponse);
        WebClient txClient = stubClient(txResponse);
        WebClient noopClient = stubClient(new AtomicReference<>(Mono.error(new IllegalStateException("not used"))));

        adminService = new AdminService(
                WebClient.builder(),
                "http://noop", "http://noop", "http://noop",
                "http://noop", "http://noop", "http://noop"
        );
        // Override the 6 internal clients with our stubs. Field injection is
        // ugly but keeps the test independent of Spring context.
        setField("userServiceClient", userClient);
        setField("tokenServiceClient", noopClient);
        setField("poolEngineClient", poolClient);
        setField("feeServiceClient", noopClient);
        setField("transactionServiceClient", txClient);
        setField("priceOracleClient", noopClient);
    }

    @AfterEach
    void teardown() {
        userResponse.set(null);
        poolResponse.set(null);
        txResponse.set(null);
    }

    @Test
    void aggregatesHealthyResponsesCorrectly() {
        userResponse.set(jsonOk(Map.of(
                "content", List.of(
                        Map.of("id", "u1", "kycStatus", "VERIFIED"),
                        Map.of("id", "u2", "kycStatus", "VERIFIED"),
                        Map.of("id", "u3", "kycStatus", "PENDING"),
                        Map.of("id", "u4", "kycStatus", "REJECTED")))));
        poolResponse.set(jsonOk(Map.of(
                "content", List.of(
                        Map.of("id", "p1", "status", "ACTIVE",
                                "totalTvlY", 100, "volume24h", 50, "totalFeesCollectedY", 5),
                        Map.of("id", "p2", "status", "ACTIVE",
                                "totalTvlY", 200, "volume24h", 80, "totalFeesCollectedY", 8),
                        Map.of("id", "p3", "status", "PAUSED",
                                "totalTvlY", 30, "volume24h", 0, "totalFeesCollectedY", 1)))));
        txResponse.set(jsonOk(Map.of(
                "content", List.of(
                        Map.of("id", "t1"), Map.of("id", "t2"), Map.of("id", "t3")))));

        DashboardResponse r = adminService.getDashboard();

        assertThat(r.totalUsers()).isEqualTo(4);
        assertThat(r.verifiedUsers()).isEqualTo(2);
        assertThat(r.totalPools()).isEqualTo(3);
        assertThat(r.activePools()).isEqualTo(2);
        assertThat(r.totalTvlRub()).isEqualByComparingTo(new BigDecimal("330"));
        assertThat(r.volume24hRub()).isEqualByComparingTo(new BigDecimal("130"));
        assertThat(r.totalFeesCollectedRub()).isEqualByComparingTo(new BigDecimal("14"));
        assertThat(r.transactionsToday()).isEqualTo(3);
    }

    @Test
    void degradesGracefullyOnSingleDownstream5xx() {
        userResponse.set(jsonOk(Map.of("content", List.of(
                Map.of("id", "u1", "kycStatus", "VERIFIED")))));
        poolResponse.set(Mono.error(WebClientResponseException.create(
                500, "Internal Server Error", null, null, null)));
        txResponse.set(jsonOk(Map.of("content", List.of(Map.of("id", "t1")))));

        DashboardResponse r = adminService.getDashboard();

        // user + transactions came through; pools all zero
        assertThat(r.totalUsers()).isEqualTo(1);
        assertThat(r.verifiedUsers()).isEqualTo(1);
        assertThat(r.totalPools()).isZero();
        assertThat(r.activePools()).isZero();
        assertThat(r.totalTvlRub()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(r.transactionsToday()).isEqualTo(1);
    }

    @Test
    void degradesGracefullyOnWrongResponseShape() {
        // Imagine an old or misconfigured downstream returning a bare list
        // instead of a page. The old AdminService stalled silently for
        // 10 seconds; the new one should treat the shape as empty and
        // continue.
        userResponse.set(jsonOk(List.of(Map.of("id", "ignored"))));  // bare list, no "content" key
        poolResponse.set(jsonOk(Map.of("content", List.of(
                Map.of("status", "ACTIVE", "totalTvlY", 999)))));
        txResponse.set(jsonOk(Map.of("content", List.of())));

        DashboardResponse r = adminService.getDashboard();

        assertThat(r.totalUsers()).as("bare list lacks .content key, treated as empty").isZero();
        assertThat(r.totalPools()).isEqualTo(1);
        assertThat(r.totalTvlRub()).isEqualByComparingTo(new BigDecimal("999"));
    }

    @Test
    void neverThrowsAndReturnsZerosWhenAllDownstreamFail() {
        userResponse.set(Mono.error(new RuntimeException("user-svc down")));
        poolResponse.set(Mono.error(new RuntimeException("pool-engine down")));
        txResponse.set(Mono.error(new RuntimeException("tx-svc down")));

        DashboardResponse r = adminService.getDashboard();

        assertThat(r.totalUsers()).isZero();
        assertThat(r.totalPools()).isZero();
        assertThat(r.transactionsToday()).isZero();
        assertThat(r.totalTvlRub()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    // ─── helpers ──────────────────────────────────────────────────────────

    private static WebClient stubClient(AtomicReference<Mono<ClientResponse>> next) {
        return WebClient.builder()
                .baseUrl("http://stub")
                .exchangeFunction(req -> {
                    Mono<ClientResponse> r = next.get();
                    return r != null ? r : Mono.empty();
                })
                .build();
    }

    private static Mono<ClientResponse> jsonOk(Object body) {
        try {
            String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(body);
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header("Content-Type", "application/json")
                    .body(json)
                    .build());
        } catch (Exception e) {
            return Mono.error(e);
        }
    }

    private void setField(String name, Object value) throws Exception {
        Field f = AdminService.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(adminService, value);
    }

    // Touch the imports to keep them
    @SuppressWarnings("unused")
    private static void noOp() {
        new LinkedHashMap<>();
        Duration.ZERO.toMillis();
    }
}
