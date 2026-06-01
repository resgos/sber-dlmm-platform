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

/**
 * Security-critical gateway filter — the platform's authentication boundary.
 *
 * <p>This is where untrusted client traffic becomes trusted, identity-bearing
 * traffic. It runs at order {@code -100} (after access logging at -200, before
 * tier rate-limiting at -50). For every request it performs two
 * non-negotiable steps, <em>in this order</em>:
 *
 * <ol>
 *   <li><b>Strip client-supplied identity headers (always).</b> Before doing
 *       anything else it removes any inbound {@code X-User-Id},
 *       {@code X-User-Role}, {@code X-Kyc-Status}, {@code X-Api-Tier},
 *       {@code X-Org-Id} and {@code X-Org-Role} from the request. These
 *       headers are <em>only</em> ever legitimate when set by this filter from
 *       a verified JWT; if a client could smuggle them in, a downstream that
 *       trusts them (rate-limit keying, public controllers, org gating) would
 *       honour a spoofed identity. The strip happens even on skip-list paths,
 *       closing that injection vector for every route.</li>
 *   <li><b>Validate the JWT and inject trusted headers.</b> On non-skipped
 *       paths it requires a {@code Bearer} token, verifies its HMAC signature
 *       with {@link #secretKey}, and on success re-adds the identity headers
 *       from the token's claims so upstream services receive a vetted
 *       identity. Any missing/expired/malformed/invalid token yields
 *       {@code 401 Unauthorized} and the request never reaches a backend.</li>
 * </ol>
 *
 * <p>Skip list ({@link #SKIP_PATHS}): pre-auth endpoints (auth login/register/
 * refresh, the SAML SSO handshake) and unauthenticated surfaces (public-data
 * API, {@code /actuator/**}) bypass step 2 but still undergo step 1.
 *
 * <p>Note on refresh tokens: the shared downstream {@code JwtAuthenticationFilter}
 * in {@code dlmm-common} is what ultimately rejects refresh tokens on business
 * endpoints; this gateway filter authenticates the bearer token and forwards
 * the derived claims (it deliberately does not re-issue or distinguish token
 * type beyond signature validity, leaving type enforcement to the downstream).
 *
 * @see com.sber.dlmm.gateway.config.RateLimitConfig the tier resolver/limiters
 *      that consume the {@code X-Api-Tier} header injected here
 */
@Component
public class JwtValidationFilter implements GlobalFilter, Ordered {

    /** SLF4J logger; records (without the token) why a request was rejected. */
    private static final Logger log = LoggerFactory.getLogger(JwtValidationFilter.class);

    /** Required prefix of the {@code Authorization} header value. */
    private static final String BEARER_PREFIX = "Bearer ";
    /** Trusted downstream header carrying the authenticated user id (JWT subject). */
    private static final String HEADER_USER_ID = "X-User-Id";
    /** Trusted downstream header carrying the authenticated user's role claim. */
    private static final String HEADER_USER_ROLE = "X-User-Role";
    /** Trusted downstream header carrying the user's KYC status claim. */
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

    /** Ant-style matcher used to test request paths against {@link #SKIP_PATHS}. */
    private final AntPathMatcher pathMatcher = new AntPathMatcher();
    /**
     * HMAC key used to verify every inbound JWT signature. Derived once at
     * construction from the configured shared secret; must be identical to the
     * key user-service signs tokens with, or all validation fails.
     */
    private final SecretKey secretKey;

    /**
     * Derives the HMAC verification key from the configured JWT secret.
     *
     * <p>Builds a {@link SecretKey} via {@link Keys#hmacShaKeyFor} from the
     * UTF-8 bytes of {@code dlmm.jwt.secret}. This is the same secret that
     * user-service uses to <em>sign</em> tokens, so the gateway can verify them
     * here; {@code SecretValidationOnStartup} (from {@code dlmm-common})
     * separately refuses to boot if that secret is missing or shorter than the
     * 32-byte HMAC minimum, so this constructor can assume a usable key.
     *
     * @param jwtSecret the shared HMAC signing secret, injected from the
     *                  {@code dlmm.jwt.secret} property/env var
     */
    public JwtValidationFilter(@Value("${dlmm.jwt.secret}") String jwtSecret) {
        this.secretKey = Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Authenticates the request and rewrites its identity headers.
     *
     * <p><b>Security ordering matters.</b> The very first action is to strip
     * any client-supplied {@code X-User-*} / {@code X-Api-Tier} / {@code X-Org-*}
     * headers from a mutated copy of the request — unconditionally, before the
     * skip-list check — so no caller can spoof an identity on any path. Only
     * after that does it branch:
     * <ul>
     *   <li><b>Skip-list path</b> ({@link #shouldSkipValidation}): forward the
     *       stripped request as-is (no token required, but the spoof-strip
     *       still applied).</li>
     *   <li><b>Protected path</b>: require {@code Authorization: Bearer <jwt>};
     *       verify the signature with {@link #secretKey}; read the
     *       {@code sub}/{@code role}/{@code kycStatus}/{@code tier} (and
     *       optional {@code orgId}/{@code orgRole}) claims; re-inject them as
     *       the trusted headers and forward. {@code tier} defaults to
     *       {@code FREE} when absent (pre-Sprint-9 tokens); org headers are
     *       added only when present so their absence reads downstream as "no
     *       org".</li>
     * </ul>
     * Any failure to produce a verified identity — no/!Bearer header, or a
     * token that is expired/unsupported/malformed/bad-signature/empty —
     * results in {@link #onUnauthorized} (401) and the chain is not invoked,
     * so an unauthenticated request never reaches a backend.
     *
     * @param exchange the current server exchange; its request is read and
     *                 replaced with a header-sanitised (and, when
     *                 authenticated, identity-stamped) mutation
     * @param chain    the downstream gateway filter chain, invoked only for
     *                 skip-list paths or successfully authenticated requests
     * @return a {@link Mono} completing when the request has been forwarded, or
     *         when the 401 response has been written for a rejected request
     */
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

    /**
     * Tests whether the path is exempt from JWT validation.
     *
     * <p>Matches the path against {@link #SKIP_PATHS} with
     * {@link AntPathMatcher}. A {@code true} result only waives the
     * <em>token-validation</em> step — the client-header strip in
     * {@link #filter} still runs for these paths, so skipping auth never opens
     * a header-spoof hole.
     *
     * @param path the request URI path
     * @return {@code true} if the path is a pre-auth/public/actuator endpoint
     *         that does not require a JWT
     */
    private boolean shouldSkipValidation(String path) {
        return SKIP_PATHS.stream().anyMatch(pattern -> pathMatcher.match(pattern, path));
    }

    /**
     * Short-circuits the request with {@code 401 Unauthorized}.
     *
     * <p>Sets the status and completes the response without a body and without
     * calling the filter chain, so a request that failed authentication is
     * never proxied to a downstream service. Used for every rejection branch
     * (missing/invalid/expired token) in {@link #filter}.
     *
     * @param exchange the current server exchange whose response is finalised
     * @return a {@link Mono} that completes once the 401 response is committed
     */
    private Mono<Void> onUnauthorized(ServerWebExchange exchange) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        return response.setComplete();
    }

    /**
     * Orders this filter immediately after request logging.
     *
     * <p>{@code -100} places it after {@link RequestLoggingFilter} (-200) so
     * rejected requests are still logged, and before
     * {@link TierBasedRateLimitFilter} (-50) so the trusted {@code X-Api-Tier}
     * header is injected here <em>before</em> the rate limiter reads it. This
     * ordering is what lets the downstream filters trust the identity/tier
     * headers.
     *
     * @return {@code -100}
     */
    @Override
    public int getOrder() {
        return -100;
    }
}
