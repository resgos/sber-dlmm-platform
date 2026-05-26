package com.sber.dlmm.admin.client;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sprint 13 S13-01 — pins the shape of {@link BffProxyClient}'s
 * fallback methods. These are invoked by Resilience4j when the
 * circuit-breaker is OPEN — they must return a caller-friendly
 * value (empty JSON page / empty object / canned error message) so
 * the controller can forward an HTTP 200 with a usable body even
 * during a downstream outage.
 *
 * <p>Because the fallbacks are {@code private}, we drive them via
 * reflection — these tests guarantee behaviour without needing a
 * full Spring AOP context (which would require booting the whole
 * application + R4j infrastructure to weave annotations).
 */
class BffProxyClientFallbackTest {

    private final BffProxyClient client = new BffProxyClient(
            org.springframework.web.reactive.function.client.WebClient.builder(),
            "http://stub", "http://stub", "http://stub", "http://stub");

    private static final RuntimeException CB_OPEN =
            new RuntimeException("CircuitBreaker 'user-service' is OPEN");

    @Test
    void getUsersFallbackReturnsEmptyPage() throws Exception {
        String body = invokeFallback("getUsersFallback",
                new Class<?>[]{int.class, int.class, String.class, String.class, Throwable.class},
                new Object[]{0, 20, null, "Bearer x", CB_OPEN});
        assertThat(body).isEqualTo(BffProxyClient.EMPTY_PAGE);
    }

    @Test
    void getUserFallbackReturnsEmptyObject() throws Exception {
        String body = invokeFallback("getUserFallback",
                new Class<?>[]{UUID.class, String.class, Throwable.class},
                new Object[]{UUID.randomUUID(), "Bearer x", CB_OPEN});
        assertThat(body).isEqualTo(BffProxyClient.EMPTY_OBJECT);
    }

    @Test
    void getPoolsFallbackReturnsEmptyPage() throws Exception {
        String body = invokeFallback("getPoolsFallback",
                new Class<?>[]{int.class, int.class, String.class, Throwable.class},
                new Object[]{0, 20, "Bearer x", CB_OPEN});
        assertThat(body).isEqualTo(BffProxyClient.EMPTY_PAGE);
    }

    @Test
    void getPoolFallbackReturnsNullSoControllerCanMapTo404() throws Exception {
        String body = invokeFallback("getPoolFallback",
                new Class<?>[]{UUID.class, String.class, Throwable.class},
                new Object[]{UUID.randomUUID(), "Bearer x", CB_OPEN});
        // null is the contract — AdminProxyController.getPool maps null
        // to ResponseEntity.notFound(). Empty object would falsely
        // suggest the pool exists.
        assertThat(body).isNull();
    }

    @Test
    void createPoolFallbackReturnsCannedError() throws Exception {
        String body = invokeFallback("createPoolFallback",
                new Class<?>[]{String.class, String.class, Throwable.class},
                new Object[]{"{\"name\":\"p\"}", "Bearer x", CB_OPEN});
        assertThat(body).contains("Failed to create pool");
    }

    @Test
    void poolActionFallbackReturnsEmptyObject() throws Exception {
        String body = invokeFallback("poolActionFallback",
                new Class<?>[]{UUID.class, String.class, Throwable.class},
                new Object[]{UUID.randomUUID(), "Bearer x", CB_OPEN});
        assertThat(body).isEqualTo(BffProxyClient.EMPTY_OBJECT);
    }

    @Test
    void mintTokenFallbackReturnsCannedError() throws Exception {
        String body = invokeFallback("mintTokenFallback",
                new Class<?>[]{String.class, String.class, Throwable.class},
                new Object[]{"{}", "Bearer x", CB_OPEN});
        assertThat(body).contains("Mint failed");
    }

    @Test
    void burnTokenFallbackReturnsCannedError() throws Exception {
        String body = invokeFallback("burnTokenFallback",
                new Class<?>[]{String.class, String.class, Throwable.class},
                new Object[]{"{}", "Bearer x", CB_OPEN});
        assertThat(body).contains("Burn failed");
    }

    @Test
    void reviewTransactionFallbackReturnsCannedError() throws Exception {
        String body = invokeFallback("reviewTransactionFallback",
                new Class<?>[]{UUID.class, String.class, Throwable.class},
                new Object[]{UUID.randomUUID(), "Bearer x", CB_OPEN});
        assertThat(body).contains("Failed to mark reviewed");
    }

    @Test
    void blockUserFallbackIsVoidAndSwallowsException() throws Exception {
        Method m = BffProxyClient.class.getDeclaredMethod(
                "blockUserFallback", UUID.class, String.class, Throwable.class);
        m.setAccessible(true);
        // No throw; the proxy semantics here are fire-and-forget on the
        // admin "block user" action. The CB-OPEN path logs + returns.
        m.invoke(client, UUID.randomUUID(), "Bearer x", CB_OPEN);
    }

    private String invokeFallback(String name, Class<?>[] sig, Object[] args) throws Exception {
        Method m = BffProxyClient.class.getDeclaredMethod(name, sig);
        m.setAccessible(true);
        return (String) m.invoke(client, args);
    }
}
