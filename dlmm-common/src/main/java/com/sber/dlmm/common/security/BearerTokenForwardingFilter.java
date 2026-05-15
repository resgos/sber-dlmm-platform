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

    private BearerTokenForwardingFilter() {}

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
