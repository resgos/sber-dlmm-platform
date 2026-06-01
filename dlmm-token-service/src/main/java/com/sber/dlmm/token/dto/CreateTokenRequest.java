package com.sber.dlmm.token.dto;

import com.sber.dlmm.common.enums.TokenType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Request body to register a brand-new token in the catalog.
 *
 * <p>Sent to the admin-only {@code POST /tokens} endpoint. On success the
 * token is created with {@code initialSupply} minted to the creating admin
 * and a {@link TokenResponse} is returned. The created-by admin is taken
 * from the JWT, not from this body.
 *
 * <p>Note on scale: {@code decimals} records the token's nominal precision
 * for display/metadata only — the backend works in raw integer amounts on
 * the uniform ×10⁴ platform scale and does not apply this column when
 * moving balances. Supply fields below are therefore raw ×10⁴ quantities.
 *
 * @param name            human-readable token name (required, non-blank)
 * @param symbol          ticker symbol, 2–10 chars (required, e.g. {@code SBTC})
 * @param decimals        nominal display precision, 0–18; metadata only, not applied to balances
 * @param initialSupply   amount minted to the creator at creation, raw ×10⁴ (must be ≥ 1)
 * @param maxSupply       hard cap on total supply, raw ×10⁴; 0 means uncapped
 * @param tokenType       classification of the token (e.g. stablecoin / yield-bearing / utility) — see {@link TokenType}
 * @param underlyingAsset identifier of the backing asset for asset-backed tokens; null/empty when not applicable
 * @param priceOracleId   id of the price feed used to value the token; null when the token has no oracle
 * @param mintable        whether further supply may be minted after creation
 * @param burnable        whether supply may be burned
 */
public record CreateTokenRequest(
        @NotBlank String name,
        @NotBlank @Size(min = 2, max = 10) String symbol,
        @Min(0) @Max(18) int decimals,
        @Min(1) long initialSupply,
        long maxSupply,
        @NotNull TokenType tokenType,
        String underlyingAsset,
        String priceOracleId,
        boolean mintable,
        boolean burnable
) {
}
