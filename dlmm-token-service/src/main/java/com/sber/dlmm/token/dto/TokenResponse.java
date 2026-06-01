package com.sber.dlmm.token.dto;

import com.sber.dlmm.common.enums.TokenType;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Response payload describing a token from the catalog.
 *
 * <p>Returned by the token read/lookup endpoints ({@code GET /tokens},
 * {@code /tokens/{id}}, {@code /tokens/symbol/{symbol}}, {@code /tokens/batch})
 * and by the lifecycle mutations (create, pause, unpause) to reflect the
 * token's current state.
 *
 * <p>Supply fields are raw integer quantities on the uniform ×10⁴ platform
 * scale; the token's {@code decimals} column is metadata and is not applied
 * to these values.
 *
 * @param id              unique token id
 * @param name            human-readable token name
 * @param symbol          ticker symbol (e.g. {@code SRUB})
 * @param decimals        nominal display precision (0–18); metadata only, not applied to amounts
 * @param totalSupply     amount currently in circulation, raw ×10⁴
 * @param maxSupply       hard cap on total supply, raw ×10⁴; 0 means uncapped
 * @param tokenType       classification of the token — see {@link TokenType}
 * @param underlyingAsset identifier of the backing asset, or null when not asset-backed
 * @param mintable        whether further supply may be minted
 * @param burnable        whether supply may be burned
 * @param active          whether the token is active; a paused token is inactive and blocks mint/transfer
 * @param createdAt       timestamp the token was registered
 */
public record TokenResponse(
        UUID id,
        String name,
        String symbol,
        int decimals,
        long totalSupply,
        long maxSupply,
        TokenType tokenType,
        String underlyingAsset,
        boolean mintable,
        boolean burnable,
        boolean active,
        LocalDateTime createdAt
) {
}
