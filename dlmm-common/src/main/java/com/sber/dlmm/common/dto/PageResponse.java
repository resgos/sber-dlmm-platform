package com.sber.dlmm.common.dto;

import java.util.List;

/**
 * Outbound page of results shared by list endpoints — the response
 * counterpart of {@link PageRequest}.
 *
 * @param <T>           element type of the page
 * @param content       items on this page (may be empty, never null by convention)
 * @param page          zero-based index of this page
 * @param size          requested page size
 * @param totalElements total number of matching items across all pages
 * @param totalPages    total number of pages for the current {@code size}
 */
public record PageResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages
) {}
