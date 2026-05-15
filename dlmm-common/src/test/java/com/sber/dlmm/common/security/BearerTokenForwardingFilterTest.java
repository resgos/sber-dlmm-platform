package com.sber.dlmm.common.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that {@link BearerTokenForwardingFilter} actually copies the
 * inbound request's Authorization header onto outbound WebClient calls.
 * Uses an in-process exchangeFunction stub so we observe the bytes that
 * would land at the downstream service without booting a real server.
 */
class BearerTokenForwardingFilterTest {

    @AfterEach
    void cleanup() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void copiesInboundAuthorizationHeader_whenRequestScopePresent() {
        givenInboundBearer("Bearer caller-jwt-abc");
        AtomicReference<ClientRequest> captured = new AtomicReference<>();

        client(captured).get().uri("/api/v1/probe").retrieve().toBodilessEntity().block();

        assertThat(captured.get().headers().getFirst("Authorization"))
                .isEqualTo("Bearer caller-jwt-abc");
    }

    @Test
    void doesNotOverrideExplicitAuthorizationOnRequest() {
        givenInboundBearer("Bearer inbound-token");
        AtomicReference<ClientRequest> captured = new AtomicReference<>();

        client(captured).get()
                .uri("/api/v1/probe")
                .header("Authorization", "Bearer explicit-override")
                .retrieve()
                .toBodilessEntity()
                .block();

        assertThat(captured.get().headers().getFirst("Authorization"))
                .isEqualTo("Bearer explicit-override");
    }

    @Test
    void noOpWhenNoRequestScope() {
        // Simulates a background scheduler invocation — no servlet request thread.
        RequestContextHolder.resetRequestAttributes();
        AtomicReference<ClientRequest> captured = new AtomicReference<>();

        client(captured).get().uri("/api/v1/probe").retrieve().toBodilessEntity().block();

        assertThat(captured.get().headers().getFirst("Authorization")).isNull();
    }

    @Test
    void noOpWhenInboundHasNoAuthorization() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        AtomicReference<ClientRequest> captured = new AtomicReference<>();

        client(captured).get().uri("/api/v1/probe").retrieve().toBodilessEntity().block();

        assertThat(captured.get().headers().getFirst("Authorization")).isNull();
    }

    private static WebClient client(AtomicReference<ClientRequest> captured) {
        return WebClient.builder()
                .baseUrl("http://stub")
                .filter(BearerTokenForwardingFilter.create())
                .exchangeFunction(req -> {
                    captured.set(req);
                    return Mono.just(ClientResponse.create(HttpStatus.OK).build());
                })
                .build();
    }

    private void givenInboundBearer(String headerValue) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", headerValue);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }
}
