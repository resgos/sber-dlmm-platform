package com.sber.dlmm.common.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import reactor.core.publisher.Mono;

/**
 * WebClient {@link ExchangeFilterFunction} that copies the inbound HTTP
 * request's {@code Authorization} header onto outgoing service-to-service
 * calls. Without this, downstream services protected by JWT (e.g. token-service)
 * reject the call with 403 because pool-engine / admin-bff make WebClient
 * requests without any auth header.
 *
 * Reads the Authorization header from {@link RequestContextHolder} —
 * works inside servlet request threads (which is where pool-engine
 * controllers call {@code .block()} on WebClient). When invoked outside
 * a request scope (e.g. background scheduler), the filter is a no-op.
 */
public class BearerTokenForwardingFilter {

    private static final Logger log = LoggerFactory.getLogger(BearerTokenForwardingFilter.class);
    private static final String AUTHORIZATION = "Authorization";

    /** Non-instantiable: this is a factory of {@link ExchangeFilterFunction}s ({@link #create()}). */
    private BearerTokenForwardingFilter() {}

    /**
     * Builds the WebClient filter that propagates the inbound caller's
     * {@code Authorization} header onto each outgoing request.
     *
     * <p>The header is only copied when (a) an inbound request scope exists and
     * carries an {@code Authorization} header, and (b) the outgoing request does
     * not already set one — an explicit per-call header always wins. Outside a
     * request scope (e.g. a background scheduler), the filter forwards the
     * request unchanged.
     *
     * @return an {@link ExchangeFilterFunction} to register on a
     *         {@code WebClient.Builder} (done centrally by
     *         {@link DlmmWebClientAutoConfiguration})
     */
    public static ExchangeFilterFunction create() {
        return (ClientRequest request, ExchangeFunction next) -> {
            String inboundAuth = currentInboundAuthHeader();
            if (inboundAuth != null && !request.headers().containsKey(AUTHORIZATION)) {
                ClientRequest authed = ClientRequest.from(request)
                        .header(AUTHORIZATION, inboundAuth)
                        .build();
                return next.exchange(authed);
            }
            return next.exchange(request);
        };
    }

    /**
     * Reads the current servlet request's {@code Authorization} header from the
     * thread-bound {@link RequestContextHolder}.
     *
     * <p>Works because pool-engine / admin-bff controllers call {@code .block()}
     * on WebClient from the servlet request thread, so the request attributes
     * are still bound. Returns {@code null} (no forwarding) when there is no
     * servlet request scope or the header is absent; any lookup error is
     * swallowed and treated as "no header".
     *
     * @return the inbound {@code Authorization} header value, or {@code null}
     *         when unavailable
     */
    private static String currentInboundAuthHeader() {
        try {
            var attrs = RequestContextHolder.getRequestAttributes();
            if (attrs instanceof ServletRequestAttributes servletAttrs) {
                return servletAttrs.getRequest().getHeader(AUTHORIZATION);
            }
        } catch (Exception ex) {
            log.trace("No request scope for Bearer forwarding: {}", ex.getMessage());
        }
        return null;
    }
}
