package com.sber.dlmm.oracle.controller;

import com.sber.dlmm.oracle.dto.PriceFeedResponse;
import com.sber.dlmm.oracle.dto.TwapResponse;
import com.sber.dlmm.oracle.service.PriceOracleService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;

@RestController
@RequestMapping("/api/v1/oracle")
@RequiredArgsConstructor
public class PriceOracleController {

    private final PriceOracleService priceOracleService;

    @GetMapping("/price/{symbol}")
    public ResponseEntity<PriceFeedResponse> getPrice(@PathVariable String symbol) {
        PriceFeedResponse response = priceOracleService.getPrice(symbol);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/twap/{symbol}")
    public ResponseEntity<TwapResponse> getTwapPrice(
            @PathVariable String symbol,
            @RequestParam(defaultValue = "15") int period) {
        BigDecimal twapPrice = priceOracleService.getTwapPrice(symbol, period);
        return ResponseEntity.ok(new TwapResponse(symbol.toUpperCase(), twapPrice, period));
    }

    @GetMapping("/prices")
    public ResponseEntity<List<PriceFeedResponse>> getAllPrices() {
        List<PriceFeedResponse> prices = priceOracleService.getAllPrices();
        return ResponseEntity.ok(prices);
    }
}
