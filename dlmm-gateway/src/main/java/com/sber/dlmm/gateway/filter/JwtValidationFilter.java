package com.sber.dlmm.gateway.filter;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.UnsupportedJwtException;
import io.jsonwebtoken.security.Keys;
import io.jsonwebtoken.security.SecurityException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.List;

@Component
public class JwtValidationFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(JwtValidationFilter.class);

    private static final String BEARER_PREFIX = "Bearer ";
    private static final String HEADER_USER_ID = "X-User-Id";
    private static final String HEADER_USER_ROLE = "X-User-Role";
    private static final String HEADER_KYC_STATUS = "X-Kyc-Status";
    /** Sprint 9 #6.6 — propagated downstream + consumed by TierKeyResolver. */
    public static final String HEADER_API_TIER = "X-Api-Tier";
    /** Sprint 11 G-21 — organisation membership headers. Both are
     *  absent (header not added at all) when the JWT carries no
     *  {@code orgId}/{@code orgRole} claims; downstream services
     *  treat absence as "user has no org". */
    public static final String HEADER_ORG_ID = "X-Org-Id";
    public static final String HEADER_ORG_ROLE = "X-Org-Role";

    private static final List<String> SKIP_PATHS = List.of(
            "/api/v1/auth/login",
            "/api/v1/auth/register",
            "/api/v1/auth/refresh",
            // S14-01 — SAML 2.0 SSO endpoints must be reachable without a JWT.
            // Metadata is fetched by IdPs before any user is logged in;
            // initiate and callback are part of the pre-auth SSO handshake.
            "/api/v1/auth/saml/**",
            // Sprint 9 R-M-33 — Public Data API tiers. No auth required;
            // rate-limit applies via the IP-fallback path in TierKeyResolver.
            "/api/v1/public/**",
            "/actuator/**"
    );

    private final AntPathMatcher pathMatcher = new AntPathMatcher();
    private final SecretKey secretKey;

    public JwtValidationFilter(@Value("${dlmm.jwt.secret}") String jwtSecret) {
        this.secretKey = Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest original = exchange.getRequest();
        String path = original.getURI().getPath();

        // Strip any CLIENT-supplied identity headers up front — these must ONLY
        // ever be set by this gateway from a verified JWT. Without this a client
        // could inject X-User-Id / X-User-Role / X-Kyc-Status / X-Api-Tier /
        // X-Org-* on a skip-list path (or any path) and a downstream that reads
        // them from the header (rate-limit keying, public controllers) would
        // trust the spoof. Applied to EVERY request — authenticated or skip.
        ServerHttpRequest request = original.mutate()
                .headers(h -> {
                    h.remove(HEADER_USER_ID);
                    h.remove(HEADER_USER_ROLE);
                    h.remove(HEADER_KYC_STATUS);
                    h.remove(HEADER_API_TIER);
                    h.remove(HEADER_ORG_ID);
                    h.remove(HEADER_ORG_ROLE);
                })
                .build();

        if (shouldSkipValidation(path)) {
            return chain.filter(exchange.mutate().request(request).build());
        }

        String authHeader = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (authHeader == null || !authHeader.startsWith(BEARER_PREFIX)) {
            log.warn("Missing or invalid Authorization header for path: {}", path);
            return onUnauthorized(exchange);
        }

        String token = authHeader.substring(BEARER_PREFIX.length());

        try {
            Claims claims = Jwts.parser()
                    .verifyWith(secretKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            String userId = claims.getSubject();
            String userRole = claims.get("role", String.class);
            String kycStatus = claims.get("kycStatus", String.class);
            // Sprint 9 #6.6 — tier claim. Default FREE for any token without
            // one (pre-Sprint-9 tokens). Header is consumed by TierKeyResolver
            // and propagated downstream so per-tier feature gating can work.
            String tier = claims.get("tier", String.class);
            if (tier == null || tier.isBlank()) {
                tier = "FREE";
            }
            // Sprint 11 G-21 — optional org claims. Additive, must NOT
            // alter the user/role/kyc header path above; absence is fine
            // (most users had no org before this commit, and tokens
            // issued by the pre-G-21 release still validate).
            String orgId = claims.get("orgId", String.class);
            String orgRole = claims.get("orgRole", String.class);

            ServerHttpRequest.Builder requestBuilder = request.mutate()
                    .header(HEADER_USER_ID, userId != null ? userId : "")
                    .header(HEADER_USER_ROLE, userRole != null ? userRole : "")
                    .header(HEADER_KYC_STATUS, kycStatus != null ? kycStatus : "")
                    .header(HEADER_API_TIER, tier);
            if (orgId != null && !orgId.isBlank()) {
                requestBuilder.header(HEADER_ORG_ID, orgId);
            }
            if (orgRole != null && !orgRole.isBlank()) {
                requestBuilder.header(HEADER_ORG_ROLE, orgRole);
            }

            return chain.filter(exchange.mutate().request(requestBuilder.build()).build());

        } catch (ExpiredJwtException e) {
            log.warn("Expired JWT token for path: {}", path);
            return onUnauthorized(exchange);
        } catch (UnsupportedJwtException | MalformedJwtException | SecurityException | IllegalArgumentException e) {
            log.warn("Invalid JWT token for path: {} - {}", path, e.getMessage());
            return onUnauthorized(exchange);
        }
    }

    private boolean shouldSkipValidation(String path) {
        return SKIP_PATHS.stream().anyMatch(pattern -> pathMatcher.match(pattern, path));
    }

    private Mono<Void> onUnauthorized(ServerWebExchange exchange) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        return response.setComplete();
    }

    @Override
    public int getOrder() {
        return -100;
    }
}
