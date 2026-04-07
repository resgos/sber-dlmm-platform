package com.sber.dlmm.oracle.dto;

import java.math.BigDecimal;

public record TwapResponse(
        String symbol,
        BigDecimal twapPrice,
        int periodMinutes
) {}
