package com.sber.dlmm.pool.controller;

import com.sber.dlmm.common.audit.AdminAudit;
import com.sber.dlmm.common.audit.UserAudit;
import com.sber.dlmm.common.dto.PageResponse;
import com.sber.dlmm.common.exception.ForbiddenException;
import com.sber.dlmm.pool.config.JwtUserDetails;
import com.sber.dlmm.pool.dto.AddLiquidityRequest;
import com.sber.dlmm.pool.dto.AddLiquidityResponse;
import com.sber.dlmm.pool.dto.BinResponse;
import com.sber.dlmm.pool.dto.PoolDetailResponse;
import com.sber.dlmm.pool.dto.PoolResponse;
import com.sber.dlmm.pool.dto.PositionResponse;
import com.sber.dlmm.pool.dto.PreviewAddLiquidityResponse;
import com.sber.dlmm.pool.dto.RemoveLiquidityRequest;
import com.sber.dlmm.pool.dto.RemoveLiquidityResponse;
import com.sber.dlmm.pool.dto.SwapQuoteRequest;
import com.sber.dlmm.pool.dto.SwapQuoteResponse;
import com.sber.dlmm.pool.dto.SwapRequest;
import com.sber.dlmm.pool.dto.SwapResponse;
import com.sber.dlmm.pool.dto.CreatePoolRequest;
import com.sber.dlmm.pool.dto.UpdateCounterpartyLimitsRequest;
import com.sber.dlmm.pool.dto.UpdateFeeParamsRequest;
import com.sber.dlmm.pool.dto.UpdateProtocolFeeRequest;
import com.sber.dlmm.pool.dto.ProMetricsDto;
import com.sber.dlmm.pool.service.LiquidityService;
import com.sber.dlmm.pool.service.PoolApyCalibrationService;
import com.sber.dlmm.pool.service.PoolProMetricsService;
import com.sber.dlmm.pool.service.PoolService;
import com.sber.dlmm.pool.service.SwapService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Primary REST controller for the DLMM pool-engine, mounted at
 * {@code /api/v1/pools}. Covers the full pool lifecycle and trading surface:
 * admin pool management (create / pause / resume / emergency-shutdown and
 * fee/protocol/counterparty parameter updates), public read endpoints (pool
 * list and detail, bins, target-APY and risk-metric calibration signals),
 * liquidity operations (add / preview / remove, position listing), and swaps
 * (execute + quote).
 *
 * <p>Authorization is mixed and enforced per handler: GET reads and the
 * read-only quote/preview endpoints are public; add-liquidity / remove-
 * liquidity / swap require a KYC-verified, non-self-restricted caller; and the
 * pool-management mutations require ADMIN (enforced via {@link #requireAdmin}).
 * The caller is always resolved from the JWT-populated security context via
 * {@link #getCurrentUser}. Mutating operations are audited through the
 * {@code @AdminAudit}/{@code @UserAudit} annotations, and the actual business
 * logic lives in the injected services — this class only handles HTTP and
 * authorization.
 */
@RestController
@RequestMapping("/api/v1/pools")
@Tag(name = "Pool Engine", description = "DLMM Liquidity Pool management, swap, and liquidity operations")
public class PoolController {

    private final PoolService poolService;
    private final LiquidityService liquidityService;
    private final SwapService swapService;
    private final PoolApyCalibrationService apyCalibrationService;
    private final PoolProMetricsService proMetricsService;

    /**
     * @param poolService            pool lifecycle + parameter mutations and pool reads
     * @param liquidityService       add/preview/remove liquidity and position queries
     * @param swapService            swap execution and quoting
     * @param apyCalibrationService  computes the per-pool target-APY calibration anchor
     * @param proMetricsService      computes per-pool risk metrics (volatility, drawdown, Sharpe)
     */
    public PoolController(PoolService poolService,
                          LiquidityService liquidityService,
                          SwapService swapService,
                          PoolApyCalibrationService apyCalibrationService,
                          PoolProMetricsService proMetricsService) {
        this.poolService = poolService;
        this.liquidityService = liquidityService;
        this.swapService = swapService;
        this.apyCalibrationService = apyCalibrationService;
        this.proMetricsService = proMetricsService;
    }

    /**
     * Creates a new DLMM pool for a token pair at a given bin step and fee
     * config. ADMIN-only; both tokens must exist and be active and no pool may
     * already exist for the same pair and bin step. Audited.
     *
     * @param request the validated pool-creation parameters (token pair, bin step, fee config)
     * @return 201 with the created {@link PoolResponse}
     * @throws ForbiddenException if the caller is not an administrator
     */
    @PostMapping
    @Operation(
            summary = "Create a new liquidity pool (ADMIN only)",
            description = "Creates a new DLMM liquidity pool for a token pair at a given bin step and fee "
                    + "configuration. ADMIN only — the caller is resolved from the JWT-populated security context "
                    + "and must hold the ADMIN (or SUPER_ADMIN) role. Both tokens must exist and be active, and no "
                    + "pool may already exist for the same token pair and bin step. The action is audited. Returns "
                    + "the newly created pool.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Pool created"),
            @ApiResponse(responseCode = "400", description = "Validation failed, or a pool already exists for this token pair and bin step"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid authentication token"),
            @ApiResponse(responseCode = "403", description = "Caller is not an administrator"),
            @ApiResponse(responseCode = "404", description = "Token X or token Y not found (or inactive)")
    })
    @AdminAudit(action = "POOL_CREATE", targetType = "POOL")
    public ResponseEntity<PoolResponse> createPool(@Valid @RequestBody CreatePoolRequest request) {
        JwtUserDetails user = getCurrentUser();
        requireAdmin(user);
        PoolResponse response = poolService.createPool(request, user.userIdAsUUID());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * Returns a paginated, sorted list of all pools with their summary metrics
     * (TVL, 24h volume, fees, status). Open to any authenticated caller.
     *
     * @param page   zero-based page index (default 0)
     * @param size   page size (default 20)
     * @param sortBy field to sort by (default {@code createdAt})
     * @return 200 with a {@link PageResponse} of {@link PoolResponse}
     */
    @GetMapping
    @Operation(
            summary = "Get all pools with pagination",
            description = "Returns a paginated list of all liquidity pools with their summary metrics (TVL, "
                    + "24h volume, fees, status). Results are sorted by the requested field. Open to any "
                    + "authenticated caller; no special role is required.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Paginated list of pools")
    })
    public ResponseEntity<PageResponse<PoolResponse>> getAllPools(
            @Parameter(description = "Zero-based page index (default 0)")
            @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size (default 20)")
            @RequestParam(defaultValue = "20") int size,
            @Parameter(description = "Field to sort by (default createdAt)")
            @RequestParam(defaultValue = "createdAt") String sortBy) {
        return ResponseEntity.ok(poolService.getAllPools(page, size, sortBy));
    }

    /**
     * Returns full details for a single pool — active bin, price, reserves, fee
     * configuration, and bin distribution. Open to any authenticated caller.
     *
     * @param id the pool id
     * @return 200 with the {@link PoolDetailResponse}
     */
    @GetMapping("/{id}")
    @Operation(
            summary = "Get pool details with bin distribution",
            description = "Returns full details for a single pool, including its current active bin, price, "
                    + "reserves, fee configuration, and bin distribution. Open to any authenticated caller; no "
                    + "special role is required.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Pool detail with bin distribution"),
            @ApiResponse(responseCode = "404", description = "Pool not found")
    })
    public ResponseEntity<PoolDetailResponse> getPoolDetail(
            @Parameter(description = "Pool ID") @PathVariable UUID id) {
        return ResponseEntity.ok(poolService.getPoolDetail(id));
    }

    /**
     * Returns the pool's bins within the inclusive id range {@code [from, to]},
     * each with its price and reserves — used to render the liquidity
     * distribution / order-book view. Open to any authenticated caller.
     *
     * @param id   the pool id
     * @param from range start bin id (inclusive)
     * @param to   range end bin id (inclusive)
     * @return 200 with the list of {@link BinResponse} in range
     */
    @GetMapping("/{id}/bins")
    @Operation(
            summary = "Get pool bins in a range",
            description = "Returns the pool's bins within an inclusive bin-id range [from, to], each with its "
                    + "price and reserves — used to render the liquidity distribution / order-book view. Open to "
                    + "any authenticated caller; no special role is required.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Bins in the requested range"),
            @ApiResponse(responseCode = "404", description = "Pool not found")
    })
    public ResponseEntity<List<BinResponse>> getPoolBins(
            @Parameter(description = "Pool ID") @PathVariable UUID id,
            @Parameter(description = "Range start (inclusive) bin id") @RequestParam int from,
            @Parameter(description = "Range end (inclusive) bin id") @RequestParam int to) {
        return ResponseEntity.ok(poolService.getPoolBins(id, from, to));
    }

    /**
     * NEW-4 (Batch #3) — per-pool target APY for the frontend
     * Position Health Score calibration. Replaces the hard-coded 20%
     * in {@code positionHealth.ts}. Returns the median realised fee
     * APY across active positions in the pool (sample &ge; 5, age
     * &ge; 7 days), or 0.20 (the historical default) when the sample
     * is too small.
     *
     * <p>Decimal fraction: {@code 0.08} = 8% APY, {@code 0.20} = 20%.
     * No auth required — same as {@code GET /{id}} and the public
     * stats endpoint; it's a soft calibration signal, not sensitive.
     *
     * @param id the pool id (an unknown pool yields the default rather than an error)
     * @return 200 with a single-entry map {@code {targetApy}} holding the decimal-fraction APY
     */
    @GetMapping("/{id}/target-apy")
    @Operation(
            summary = "Pool target APY anchor for Health Score calibration (NEW-4)",
            description = "Returns a single-entry map {targetApy} — the median realised fee APY across the pool's "
                    + "active positions, used by the frontend to calibrate its Position Health Score instead of a "
                    + "hard-coded anchor. The value is a decimal fraction (0.08 = 8%). Falls back to the historical "
                    + "default 0.20 when the sample is too small. No auth required — it is a soft calibration signal, "
                    + "not sensitive; never returns an error for an unknown pool (yields the default instead).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Map with the targetApy decimal fraction")
    })
    public ResponseEntity<java.util.Map<String, java.math.BigDecimal>> getPoolTargetApy(
            @Parameter(description = "Pool ID") @PathVariable UUID id) {
        return ResponseEntity.ok(java.util.Map.of("targetApy", apyCalibrationService.getPoolTargetApy(id)));
    }

    /**
     * G-23 (Batch #3, Sprint 15) — pro-grade risk metrics for the Pool
     * comparator: 30-day realised volatility, max drawdown, Sharpe.
     * Returns {@code isReliable=false} with zeros when the price-history
     * sample is too small (&lt; 10 points) — UI renders "—" in that case.
     *
     * <p>No auth — same rationale as {@code /target-apy}. Cached 1h
     * per poolId in the service, so high concurrency on the comparator
     * doesn't hit the DB.
     *
     * @param id the pool id (an unknown pool degrades to {@code isReliable=false} rather than an error)
     * @return 200 with the {@link ProMetricsDto} (zeros and {@code isReliable=false} when the sample is too small)
     */
    @GetMapping("/{id}/pro-metrics")
    @Operation(
            summary = "Pool risk metrics (volatility, max drawdown, Sharpe) — G-23",
            description = "Returns pro-grade risk metrics for the pool comparator: 30-day realised volatility, "
                    + "max drawdown, and Sharpe ratio. When the price-history sample is too small (< 10 points) the "
                    + "response carries isReliable=false with zero metrics (the UI renders \"—\"). No auth required; "
                    + "the result is cached ~1h per pool. Never returns an error for an unknown pool (degrades to "
                    + "isReliable=false instead).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Risk metrics (isReliable=false with zeros when the sample is too small)")
    })
    public ResponseEntity<ProMetricsDto> getPoolProMetrics(
            @Parameter(description = "Pool ID") @PathVariable UUID id) {
        return ResponseEntity.ok(proMetricsService.compute(id));
    }

    /**
     * Pauses trading and liquidity operations on the pool, moving it to a
     * PAUSED state that can later be resumed. ADMIN-only; audited.
     *
     * @param id the pool id
     * @return 200 with the updated {@link PoolResponse}
     * @throws ForbiddenException if the caller is not an administrator
     */
    @PostMapping("/{id}/pause")
    @Operation(
            summary = "Pause a pool (ADMIN only)",
            description = "Pauses trading and liquidity operations on the pool, transitioning it to a PAUSED "
                    + "state that can later be resumed. ADMIN only — the caller is resolved from the JWT-populated "
                    + "security context and must hold the ADMIN (or SUPER_ADMIN) role. The action is audited. "
                    + "Returns the updated pool.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Pool paused"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid authentication token"),
            @ApiResponse(responseCode = "403", description = "Caller is not an administrator"),
            @ApiResponse(responseCode = "404", description = "Pool not found")
    })
    @AdminAudit(action = "POOL_PAUSE", targetType = "POOL", targetIdParam = "id")
    public ResponseEntity<PoolResponse> pausePool(
            @Parameter(description = "Pool ID") @PathVariable UUID id) {
        requireAdmin(getCurrentUser());
        return ResponseEntity.ok(poolService.pausePool(id));
    }

    /**
     * Immediately halts all activity on the pool as an emergency control — a
     * stronger, incident-response action than {@link #pausePool}. ADMIN-only;
     * audited.
     *
     * @param id the pool id
     * @return 200 with the updated {@link PoolResponse}
     * @throws ForbiddenException if the caller is not an administrator
     */
    @PostMapping("/{id}/emergency-shutdown")
    @Operation(
            summary = "Emergency shutdown a pool (ADMIN only)",
            description = "Immediately halts all activity on the pool as an emergency control (a stronger, "
                    + "incident-response action than pause). ADMIN only — the caller is resolved from the "
                    + "JWT-populated security context and must hold the ADMIN (or SUPER_ADMIN) role. The action is "
                    + "audited. Returns the updated pool.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Pool shut down"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid authentication token"),
            @ApiResponse(responseCode = "403", description = "Caller is not an administrator"),
            @ApiResponse(responseCode = "404", description = "Pool not found")
    })
    @AdminAudit(action = "POOL_EMERGENCY_SHUTDOWN", targetType = "POOL", targetIdParam = "id")
    public ResponseEntity<PoolResponse> emergencyShutdown(
            @Parameter(description = "Pool ID") @PathVariable UUID id) {
        requireAdmin(getCurrentUser());
        return ResponseEntity.ok(poolService.emergencyShutdown(id));
    }

    /**
     * Resumes a previously paused pool, returning it to ACTIVE so trading and
     * liquidity operations may continue. ADMIN-only; audited.
     *
     * @param id the pool id
     * @return 200 with the updated {@link PoolResponse}
     * @throws ForbiddenException if the caller is not an administrator
     */
    @PostMapping("/{id}/resume")
    @Operation(
            summary = "Resume a pool (ADMIN only)",
            description = "Resumes a previously paused pool, returning it to the ACTIVE state so trading and "
                    + "liquidity operations may continue. ADMIN only — the caller is resolved from the JWT-populated "
                    + "security context and must hold the ADMIN (or SUPER_ADMIN) role. The action is audited. Returns "
                    + "the updated pool.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Pool resumed"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid authentication token"),
            @ApiResponse(responseCode = "403", description = "Caller is not an administrator"),
            @ApiResponse(responseCode = "404", description = "Pool not found")
    })
    @AdminAudit(action = "POOL_RESUME", targetType = "POOL", targetIdParam = "id")
    public ResponseEntity<PoolResponse> resumePool(
            @Parameter(description = "Pool ID") @PathVariable UUID id) {
        requireAdmin(getCurrentUser());
        return ResponseEntity.ok(poolService.resumePool(id));
    }

    /**
     * Updates the pool's dynamic-fee parameters: base fee (bps), maximum
     * variable fee (bps), and the volatility decay period (seconds). ADMIN-
     * only; audited.
     *
     * @param id      the pool id
     * @param request the validated new fee parameters
     * @return 200 with the updated {@link PoolResponse}
     * @throws ForbiddenException if the caller is not an administrator
     */
    @PutMapping("/{id}/fee-params")
    @Operation(
            summary = "Update pool fee parameters (ADMIN only)",
            description = "Updates the pool's dynamic-fee parameters: base fee (bps), maximum variable fee (bps), "
                    + "and the volatility decay period (seconds). ADMIN only — the caller is resolved from the "
                    + "JWT-populated security context and must hold the ADMIN (or SUPER_ADMIN) role. The action is "
                    + "audited. Returns the updated pool.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Fee parameters updated"),
            @ApiResponse(responseCode = "400", description = "Validation failed"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid authentication token"),
            @ApiResponse(responseCode = "403", description = "Caller is not an administrator"),
            @ApiResponse(responseCode = "404", description = "Pool not found")
    })
    @AdminAudit(action = "POOL_FEE_PARAMS_UPDATE", targetType = "POOL", targetIdParam = "id")
    public ResponseEntity<PoolResponse> updateFeeParams(
            @Parameter(description = "Pool ID") @PathVariable UUID id,
            @Valid @RequestBody UpdateFeeParamsRequest request) {
        requireAdmin(getCurrentUser());
        return ResponseEntity.ok(poolService.updateFeeParams(id,
                request.baseFeeBps(), request.maxVariableFeeBps(), request.decayPeriodSeconds()));
    }

    /**
     * Sprint 6 #3.1 — admin tunes per-pool protocol fee share.
     *
     * <p>Range 0-5 percent (Sprint 5 #5.G legal memo verdict).
     * Toggle to activate revenue accumulation on a pool (default 0
     * means pure-LP). Existing accumulators ({@code totalProtocolFeeX/Y})
     * grow on each subsequent swap; doesn't retro-charge past swaps.
     *
     * @param id      the pool id
     * @param request the validated new protocol fee percentage (0-5)
     * @return 200 with the updated {@link PoolResponse}
     * @throws ForbiddenException if the caller is not an administrator
     */
    @PutMapping("/{id}/protocol-fee-pct")
    @Operation(
            summary = "Update pool protocol fee % (0-5, ADMIN only)",
            description = "Sets the pool's protocol fee share as a percentage in the range 0-5. A value of 0 "
                    + "means pure-LP (no protocol cut); a non-zero value activates protocol revenue accrual on "
                    + "subsequent swaps and does not retro-charge past swaps. ADMIN only — the caller is resolved "
                    + "from the JWT-populated security context and must hold the ADMIN (or SUPER_ADMIN) role. The "
                    + "action is audited. Returns the updated pool.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Protocol fee percentage updated"),
            @ApiResponse(responseCode = "400", description = "Validation failed (e.g. value outside the 0-5 range)"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid authentication token"),
            @ApiResponse(responseCode = "403", description = "Caller is not an administrator"),
            @ApiResponse(responseCode = "404", description = "Pool not found")
    })
    @AdminAudit(action = "POOL_PROTOCOL_FEE_UPDATE", targetType = "POOL", targetIdParam = "id")
    public ResponseEntity<PoolResponse> updateProtocolFeePct(
            @Parameter(description = "Pool ID") @PathVariable UUID id,
            @Valid @RequestBody UpdateProtocolFeeRequest request) {
        requireAdmin(getCurrentUser());
        return ResponseEntity.ok(poolService.updateProtocolFeePct(id, request.protocolFeePct()));
    }

    /**
     * Sprint 4 #4.2 — admin sets per-pool counterparty caps.
     *
     * Either side may be omitted (null) to leave it uncapped. The full
     * target state is replayed every call — admin-ui should pre-fill with
     * current values to prevent accidental cap removal.
     *
     * @param id      the pool id
     * @param request the validated per-side caps; a null side leaves that side uncapped
     * @return 200 with the updated {@link PoolResponse}
     * @throws ForbiddenException if the caller is not an administrator
     */
    @PutMapping("/{id}/counterparty-limits")
    @Operation(
            summary = "Update per-pool counterparty single-swap caps (ADMIN only)",
            description = "Sets the per-pool maximum single-swap nominal caps for each side (X and Y). Either side "
                    + "may be null to leave it uncapped; the full target state is replayed on every call, so the "
                    + "admin UI should pre-fill current values to avoid accidentally removing a cap. ADMIN only — the "
                    + "caller is resolved from the JWT-populated security context and must hold the ADMIN (or "
                    + "SUPER_ADMIN) role. Returns the updated pool.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Counterparty limits updated"),
            @ApiResponse(responseCode = "400", description = "Validation failed"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid authentication token"),
            @ApiResponse(responseCode = "403", description = "Caller is not an administrator"),
            @ApiResponse(responseCode = "404", description = "Pool not found")
    })
    public ResponseEntity<PoolResponse> updateCounterpartyLimits(
            @Parameter(description = "Pool ID") @PathVariable UUID id,
            @Valid @RequestBody UpdateCounterpartyLimitsRequest request) {
        requireAdmin(getCurrentUser());
        return ResponseEntity.ok(poolService.updateCounterpartyLimits(id,
                request.maxSingleSwapNominalX(), request.maxSingleSwapNominalY()));
    }

    /**
     * Adds liquidity across the requested bin range, deducting the supplied
     * token amounts and opening (or topping up) an LP position. The caller must
     * be KYC-verified and not self-restricted (115-ФЗ); the pool must be
     * ACTIVE. Idempotent on the request's key; audited.
     *
     * @param request the validated add-liquidity parameters (pool, bin range, amounts, idempotency key)
     * @return 201 with the resulting {@link AddLiquidityResponse} (the LP position)
     * @throws ForbiddenException if the caller is not authenticated, not KYC-verified, or self-restricted
     */
    @PostMapping("/add-liquidity")
    @Operation(
            summary = "Add liquidity to a pool (KYC verified users)",
            description = "Adds liquidity to a pool across the requested bin range, deducting the supplied token "
                    + "amounts and opening (or topping up) an LP position. The caller is resolved from the "
                    + "JWT-populated security context and must be KYC-verified and not self-restricted (115-ФЗ); the "
                    + "target pool must exist and be ACTIVE. Supports an idempotency key. The action is audited. "
                    + "Returns the resulting position.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Liquidity added; returns the LP position"),
            @ApiResponse(responseCode = "400", description = "Validation failed, or pool/token is not active"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid authentication token"),
            @ApiResponse(responseCode = "403", description = "Not authenticated, KYC not verified, or self-restricted (115-ФЗ)"),
            @ApiResponse(responseCode = "404", description = "Pool or token not found")
    })
    @UserAudit(action = "ADD_LIQUIDITY", targetType = "POOL")
    public ResponseEntity<AddLiquidityResponse> addLiquidity(
            @Valid @RequestBody AddLiquidityRequest request) {
        JwtUserDetails user = getCurrentUser();
        AddLiquidityResponse response = liquidityService.addLiquidity(request, user.userIdAsUUID());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * Sprint 11 G-22 — server-computed preview of an add-liquidity call.
     *
     * <p>Read-only "what-if": returns TVL share, in-range chip data,
     * per-bin allocation, fee-per-day projection, and human-readable
     * warnings without mutating any state. UI fetches this on every
     * form-field change (debounced) so the user sees the picture
     * before clicking Submit.
     *
     * <p>No auth requirement (matches {@code /swap/quote}) — the
     * compute is cheap and read-only, and the gateway already
     * rate-limits per IP for unauthenticated requests.
     *
     * @param request the validated add-liquidity parameters to preview (the pool must be ACTIVE and the bin range valid)
     * @return 200 with the {@link PreviewAddLiquidityResponse} projection (no state is mutated)
     */
    @PostMapping("/preview-add-liquidity")
    @Operation(
            summary = "Preview an add-liquidity call (TVL share, in-range, warnings) — read-only",
            description = "Server-computed, read-only \"what-if\" for an add-liquidity request: returns projected "
                    + "TVL share, in-range chip data, per-bin allocation, fee-per-day estimate, and human-readable "
                    + "warnings without mutating any state. Intended to be called (debounced) on every form change. "
                    + "No auth required (mirrors /swap/quote) — the compute is cheap and read-only. The target pool "
                    + "must exist and be ACTIVE and the bin range must be valid.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Preview projection (TVL share, in-range, per-bin allocation, warnings)"),
            @ApiResponse(responseCode = "400", description = "Validation failed, invalid bin range, or pool is not active"),
            @ApiResponse(responseCode = "404", description = "Pool not found")
    })
    public ResponseEntity<PreviewAddLiquidityResponse> previewAddLiquidity(
            @Valid @RequestBody AddLiquidityRequest request) {
        return ResponseEntity.ok(liquidityService.previewAddLiquidity(request));
    }

    /**
     * Removes a percentage (in bps) of liquidity from one of the caller's
     * active positions, returning the proportional token amounts plus accrued
     * fees. The position must belong to the caller and still be open.
     * Idempotent on the request's key; audited.
     *
     * @param request the validated remove-liquidity parameters (position, percentage in bps, idempotency key)
     * @return 200 with the {@link RemoveLiquidityResponse} (amounts withdrawn)
     * @throws ForbiddenException if the caller is not authenticated, not KYC-verified, or the position belongs to another user
     */
    @PostMapping("/remove-liquidity")
    @Operation(
            summary = "Remove liquidity from a position (KYC verified users)",
            description = "Removes a percentage (in bps) of liquidity from one of the caller's active LP "
                    + "positions, returning the proportional token amounts plus accrued fees. The caller is resolved "
                    + "from the JWT-populated security context; the position must belong to them and still be open. "
                    + "Supports an idempotency key. The action is audited. Returns the withdrawal result.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Liquidity removed; returns token amounts withdrawn"),
            @ApiResponse(responseCode = "400", description = "Validation failed, invalid percentage, or position is already closed"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid authentication token"),
            @ApiResponse(responseCode = "403", description = "Not authenticated, KYC not verified, or the position belongs to another user"),
            @ApiResponse(responseCode = "404", description = "Position or pool not found")
    })
    @UserAudit(action = "REMOVE_LIQUIDITY", targetType = "POSITION")
    public ResponseEntity<RemoveLiquidityResponse> removeLiquidity(
            @Valid @RequestBody RemoveLiquidityRequest request) {
        JwtUserDetails user = getCurrentUser();
        return ResponseEntity.ok(liquidityService.removeLiquidity(request, user.userIdAsUUID()));
    }

    /**
     * Returns all active LP positions owned by the authenticated caller, with
     * their bin ranges, liquidity, and accrued fees. The user is resolved from
     * the security context, so only their own positions are returned.
     *
     * @return 200 with the caller's list of {@link PositionResponse}
     * @throws ForbiddenException if there is no authenticated principal
     */
    @GetMapping("/positions/me")
    @Operation(
            summary = "Get current user's active positions",
            description = "Returns all active LP positions owned by the authenticated caller, with their bin "
                    + "ranges, liquidity, and accrued fees. The user is resolved from the JWT-populated security "
                    + "context. Requires authentication.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "List of the current user's active positions"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid authentication token"),
            @ApiResponse(responseCode = "403", description = "Authentication required (no authenticated principal)")
    })
    public ResponseEntity<List<PositionResponse>> getUserPositions() {
        JwtUserDetails user = getCurrentUser();
        return ResponseEntity.ok(liquidityService.getUserPositions(user.userIdAsUUID()));
    }

    /**
     * Platform-wide count of open LP positions. Used by admin-bff to fill
     * in {@code activePositions} on the dashboard — calling /positions/me
     * for every user would be O(users) round-trips.
     *
     * @return 200 with a single-entry map {@code {activePositions}} holding the platform-wide open-position count
     */
    @GetMapping("/positions/count")
    @Operation(
            summary = "Total active LP positions across the platform",
            description = "Returns a single-entry map {activePositions} with the platform-wide count of open LP "
                    + "positions. Used by the admin-bff to populate the dashboard without fanning out per-user "
                    + "position lookups. Does not expose any per-user data.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Map with the total active position count")
    })
    public ResponseEntity<java.util.Map<String, Long>> getActivePositionsCount() {
        return ResponseEntity.ok(java.util.Map.of("activePositions", liquidityService.countActivePositions()));
    }

    /**
     * Executes a token swap against a pool, crossing bins and applying the
     * dynamic (volatility-driven) fee within the caller's slippage bound. The
     * caller must be KYC-verified and not self-restricted (115-ФЗ); the pool
     * and input token must be active and per-pool counterparty caps are
     * enforced. Idempotent on the request's key — a duplicate is rejected with
     * 409. Audited.
     *
     * @param request the validated swap parameters (pool, input token, amount, slippage bound, idempotency key)
     * @return 200 with the executed {@link SwapResponse} (amount out, fee, price impact)
     * @throws ForbiddenException if the caller is not authenticated, not KYC-verified, or self-restricted
     */
    @PostMapping("/swap")
    @Operation(
            summary = "Execute a token swap (KYC verified users)",
            description = "Executes a token swap against a pool, crossing bins and applying the dynamic "
                    + "(volatility-driven) fee, subject to the caller's slippage bound. The caller is resolved from "
                    + "the JWT-populated security context and must be KYC-verified and not self-restricted (115-ФЗ); "
                    + "the pool and input token must be active and per-pool counterparty caps are enforced. Supports "
                    + "an idempotency key — a duplicate key is rejected with 409. The action is audited. Returns the "
                    + "executed swap (amount out, fee, price impact).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Swap executed"),
            @ApiResponse(responseCode = "400", description = "Validation failed, slippage exceeded, insufficient liquidity, counterparty cap exceeded, or pool/token not active"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid authentication token"),
            @ApiResponse(responseCode = "403", description = "Not authenticated, KYC not verified, or self-restricted (115-ФЗ)"),
            @ApiResponse(responseCode = "404", description = "Pool not found"),
            @ApiResponse(responseCode = "409", description = "Duplicate request for the same idempotency key")
    })
    @UserAudit(action = "SWAP", targetType = "POOL")
    public ResponseEntity<SwapResponse> swap(@Valid @RequestBody SwapRequest request) {
        JwtUserDetails user = getCurrentUser();
        return ResponseEntity.ok(swapService.swap(request, user.userIdAsUUID()));
    }

    /**
     * Computes a read-only quote for a prospective swap — estimated amount
     * out, total fee, effective fee bps, bins crossed, execution price, and
     * price impact — without deducting balances or mutating state. No auth
     * required; intended for the trade form.
     *
     * @param request the validated quote parameters (pool, input token, amount)
     * @return 200 with the {@link SwapQuoteResponse} (no state is mutated)
     */
    @PostMapping("/swap/quote")
    @Operation(
            summary = "Get a swap quote without executing",
            description = "Computes a read-only quote for a prospective swap — estimated amount out, total fee, "
                    + "effective fee bps, bins crossed, execution price, and price impact — without deducting "
                    + "balances or mutating state. No auth required; intended for the trade form. The target pool "
                    + "must exist and have enough liquidity for the requested size.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Swap quote (amount out, fee, price impact)"),
            @ApiResponse(responseCode = "400", description = "Validation failed or insufficient liquidity for the requested swap"),
            @ApiResponse(responseCode = "404", description = "Pool not found")
    })
    public ResponseEntity<SwapQuoteResponse> swapQuote(@Valid @RequestBody SwapQuoteRequest request) {
        return ResponseEntity.ok(swapService.quote(request));
    }

    /**
     * Extracts the current caller from the JWT-populated Spring
     * {@code SecurityContext} into a {@link JwtUserDetails} (principal = user
     * id, credentials = KYC status, first authority = role with the
     * {@code ROLE_} prefix stripped).
     *
     * @return the authenticated caller's details
     * @throws ForbiddenException if there is no authenticated, non-anonymous principal
     */
    private JwtUserDetails getCurrentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth.getPrincipal() == null
                || "anonymousUser".equals(auth.getPrincipal())) {
            throw new ForbiddenException("Authentication required");
        }
        String userId = auth.getPrincipal().toString();
        String kycStatus = auth.getCredentials() == null ? null : auth.getCredentials().toString();
        String role = auth.getAuthorities().stream()
                .map(a -> a.getAuthority().startsWith("ROLE_") ? a.getAuthority().substring(5) : a.getAuthority())
                .findFirst()
                .orElse(null);
        return new JwtUserDetails(userId, role, kycStatus);
    }

    /**
     * Guard for the ADMIN-only mutations on this controller.
     *
     * @param user the resolved caller (from {@link #getCurrentUser})
     * @throws ForbiddenException if the caller does not hold the ADMIN (or SUPER_ADMIN) role
     */
    private void requireAdmin(JwtUserDetails user) {
        if (!user.isAdmin()) {
            throw new ForbiddenException("Admin access required");
        }
    }
}
