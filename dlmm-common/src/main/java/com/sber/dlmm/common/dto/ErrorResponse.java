package com.sber.dlmm.common.dto;

public record ErrorResponse(
        String errorCode,
        String message,
        String timestamp,
        String traceId
) {}
