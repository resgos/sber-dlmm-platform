package com.sber.dlmm.admin.controller;

import com.sber.dlmm.admin.dto.CohortDataPoint;
import com.sber.dlmm.admin.service.CohortService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Cohort analytics endpoint: DAU / MAU / D7 / D30 retention metrics.
 *
 * <p>All metrics are derived from the {@code transactions} table in the shared
 * {@code dlmm} Postgres database. No downstream service calls are involved.
 *
 * <p>Example:
 * <pre>GET /api/v1/admin/cohorts?metric=DAU&amp;days=30</pre>
 */
@RestController
@RequestMapping("/api/v1/admin/cohorts")
@Tag(name = "Cohort Analytics", description = "DAU/MAU/D7/D30 retention metrics")
@RequiredArgsConstructor
public class CohortController {

    private final CohortService cohortService;

    /**
     * Returns a time-series of the requested cohort metric.
     *
     * @param metric one of {@code DAU}, {@code MAU}, {@code D7}, {@code D30}
     *               (default: {@code DAU})
     * @param days   number of trailing calendar days (default: 30, max: 365)
     * @param auth   forwarded Authorization header (injected by gateway; unused
     *               directly — Spring Security handles bearer validation)
     * @return list of {@link CohortDataPoint} ordered by date ascending
     */
    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @Operation(
            summary     = "Get cohort analytics",
            description = "Admin backend-for-frontend endpoint returning DAU, MAU, D7 or D30 retention data points "
                    + "for the requested period. Metrics are derived directly from the shared database "
                    + "(transactions table); no downstream service calls are involved."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Cohort time-series returned, ordered by date ascending"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid authentication"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the ADMIN or SUPER_ADMIN role")
    })
    public ResponseEntity<List<CohortDataPoint>> getCohorts(
            @Parameter(description = "Metric: DAU | MAU | D7 | D30", example = "DAU")
            @RequestParam(defaultValue = "DAU") String metric,

            @Parameter(description = "Trailing calendar days (1–365)", example = "30")
            @RequestParam(defaultValue = "30") int days,

            @RequestHeader(value = "Authorization", required = false) String auth) {

        List<CohortDataPoint> data = cohortService.getCohortData(metric, days);
        return ResponseEntity.ok(data);
    }
}
