package com.sber.dlmm.common.dto;

/**
 * Inbound pagination + sort parameters shared by list endpoints.
 *
 * <p>Self-sanitising: the compact constructor coerces out-of-range or missing
 * values to safe defaults, so controllers can pass user-supplied query params
 * straight in without separate validation. Pairs with {@link PageResponse} on
 * the way out.
 *
 * @param page    zero-based page index
 * @param size    page size (number of items per page)
 * @param sortBy  field name to sort by
 * @param sortDir sort direction, {@code "ASC"} or {@code "DESC"}
 */
public record PageRequest(
        int page,
        int size,
        String sortBy,
        String sortDir
) {
    /**
     * Canonical constructor that normalises inputs to safe defaults:
     * negative {@code page} → 0, non-positive {@code size} → 20, blank/null
     * {@code sortBy} → {@code "createdAt"}, blank/null {@code sortDir} →
     * {@code "DESC"}.
     */
    public PageRequest {
        if (page < 0) page = 0;
        if (size <= 0) size = 20;
        if (sortBy == null || sortBy.isBlank()) sortBy = "createdAt";
        if (sortDir == null || sortDir.isBlank()) sortDir = "DESC";
    }

    /** Creates a default request: page 0, size 20, sorted by {@code createdAt} DESC. */
    public PageRequest() {
        this(0, 20, "createdAt", "DESC");
    }
}
