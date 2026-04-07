package com.sber.dlmm.common.dto;

public record PageRequest(
        int page,
        int size,
        String sortBy,
        String sortDir
) {
    public PageRequest {
        if (page < 0) page = 0;
        if (size <= 0) size = 20;
        if (sortBy == null || sortBy.isBlank()) sortBy = "createdAt";
        if (sortDir == null || sortDir.isBlank()) sortDir = "DESC";
    }

    public PageRequest() {
        this(0, 20, "createdAt", "DESC");
    }
}
