package com.sber.dlmm.pool.client;

import com.sber.dlmm.common.exception.InsufficientBalanceException;
import com.sber.dlmm.common.exception.TokenNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.UUID;

@Component
public class TokenServiceClient {

    private static final Logger log = LoggerFactory.getLogger(TokenServiceClient.class);
    private final WebClient webClient;

    public TokenServiceClient(@Value("${dlmm.services.token-service.url}") String tokenServiceUrl,
                               WebClient.Builder webClientBuilder) {
        this.webClient = webClientBuilder.baseUrl(tokenServiceUrl).build();
    }

    public TokenInfo getToken(UUID tokenId) {
        return webClient.get()
                .uri("/api/v1/tokens/{id}", tokenId)
                .retrieve()
                .onStatus(status -> status.value() == HttpStatus.NOT_FOUND.value(),
                        response -> Mono.error(new TokenNotFoundException("Token not found: " + tokenId)))
                .bodyToMono(TokenInfo.class)
                .block();
    }

    public boolean isTokenActive(UUID tokenId) {
        TokenInfo token = getToken(tokenId);
        return token != null && token.active();
    }

    public void deductBalance(UUID userId, UUID tokenId, long amount) {
        DeductRequest request = new DeductRequest(userId, tokenId, amount);
        webClient.post()
                .uri("/api/v1/tokens/internal/deduct")
                .bodyValue(request)
                .retrieve()
                .onStatus(status -> status.value() == HttpStatus.BAD_REQUEST.value(),
                        response -> Mono.error(new InsufficientBalanceException(
                                "Insufficient balance for user " + userId + " token " + tokenId)))
                .toBodilessEntity()
                .block();
    }

    public void creditBalance(UUID userId, UUID tokenId, long amount) {
        CreditRequest request = new CreditRequest(userId, tokenId, amount);
        webClient.post()
                .uri("/api/v1/tokens/internal/credit")
                .bodyValue(request)
                .retrieve()
                .toBodilessEntity()
                .block();
    }

    public boolean isUserKycVerified(UUID userId) {
        UserKycResponse response = webClient.get()
                .uri("/api/v1/users/internal/{userId}/kyc", userId)
                .retrieve()
                .bodyToMono(UserKycResponse.class)
                .onErrorReturn(new UserKycResponse(false))
                .block();
        return response != null && response.verified();
    }

    public record TokenInfo(UUID id, String name, String symbol, boolean active) {
    }

    public record DeductRequest(UUID userId, UUID tokenId, long amount) {
    }

    public record CreditRequest(UUID userId, UUID tokenId, long amount) {
    }

    public record UserKycResponse(boolean verified) {
    }
}
