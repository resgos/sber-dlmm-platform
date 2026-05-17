#!/usr/bin/env pwsh
# Sber DLMM — Live FX hedge demo (Sprint 3 demo prep, task 3.D)
#
# Plays out the corp-CFO use case from docs/USE-CASE-FX-HEDGE.md:
#   * mid-cap RU importer needs to pay 50M ¥ to a Chinese supplier in 30 days
#   * needs to hedge SRUB→SCNY at current rate, lock the cost
#   * traditional: dealer desk, 30+ bps spread, T+2, min lot 50M ₽
#   * Sber DLMM: 10 bps base fee on SRUB/SCNY pool, T+0, any size
#
# Run from repo root against the local compose stack:
#   pwsh -File scripts/demo-fx-hedge.ps1
# Or smaller demo size:
#   pwsh -File scripts/demo-fx-hedge.ps1 -HedgeAmount 5000000
#
# Pre-reqs:
#   * docker-compose stack up (postgres + redis + kafka + 7 services)
#   * Seed user ivanov@example.com with sufficient SRUB balance

param(
    [string]$BaseUrl = "http://localhost:8080",
    [string]$Email = "ivanov@example.com",
    [string]$Password = "Demo1234",
    [int64]$HedgeAmount = 1000000000,    # 10M SRUB in smallest units (SRUB has 2 decimals)
    [string]$PoolId = "c0000000-0000-0000-0000-000000000111",     # SRUB/SCNY pool
    [string]$SrubTokenId = "b0000000-0000-0000-0000-000000000001",
    [string]$ScnyTokenId = "b0000000-0000-0000-0000-000000000111"
)

$ErrorActionPreference = "Stop"
$PSStyle.OutputRendering = "PlainText"  # avoid color escape codes in piped output

function Write-Step {
    param([string]$Title)
    Write-Host ""
    Write-Host ("=" * 70) -ForegroundColor Cyan
    Write-Host $Title -ForegroundColor Cyan
    Write-Host ("=" * 70) -ForegroundColor Cyan
}

function Invoke-Json {
    param(
        [string]$Method,
        [string]$Url,
        [hashtable]$Body = $null,
        [string]$Token = $null
    )
    $headers = @{ "Content-Type" = "application/json" }
    if ($Token) { $headers["Authorization"] = "Bearer $Token" }
    $params = @{
        Method  = $Method
        Uri     = $Url
        Headers = $headers
    }
    if ($Body) { $params.Body = ($Body | ConvertTo-Json -Depth 10 -Compress) }
    return Invoke-RestMethod @params
}

# ─────────────────────────────────────────────────────────────────────────
# Step 1 — narrative setup
# ─────────────────────────────────────────────────────────────────────────
Write-Step "Scenario: mid-cap RU importer hedges 10M SRUB exposure to CNY"
Write-Host "Client: Ivan Ivanov, CFO of a mid-cap importer."
Write-Host "Need: pay 50M CNY to a Chinese supplier in 30 days."
Write-Host "Risk: if RUB depreciates 5%, overpay ~32M RUB (one year of marketing)."
Write-Host ""
Write-Host "Alternatives today:"
Write-Host "  - Raiffeisen dealer desk:  30-100 bps, min lot 50M RUB, T+2, weeks-long KYC"
Write-Host "  - Moscow Exchange forward: 5-15 bps, min lot 100M, T+2, broker required"
Write-Host "  - Crypto stablecoin OTC:   50-200 bps, AML risk, offshore"
Write-Host ""
Write-Host "Sber DLMM: 10 bps on SRUB/SCNY pool, T+0, any size, SberID KYC reused."
Write-Host ""
Read-Host "Press Enter to continue..."

# ─────────────────────────────────────────────────────────────────────────
# Step 2 — login as the client (SberID single sign-on in real demo)
# ─────────────────────────────────────────────────────────────────────────
Write-Step "Step 1 — Client logs in via SberID (here: seed user $Email)"
$auth = Invoke-Json -Method POST -Url "$BaseUrl/api/v1/auth/login" -Body @{
    email    = $Email
    password = $Password
}
$token = $auth.accessToken
Write-Host "  ✓ Authenticated. Role: $($auth.user.role), KYC: $($auth.user.kycStatus)"

# ─────────────────────────────────────────────────────────────────────────
# Step 3 — quote (no execution yet)
# ─────────────────────────────────────────────────────────────────────────
Write-Step "Step 2 — Live quote: $HedgeAmount SRUB units → SCNY"
$quote = Invoke-Json -Method POST -Url "$BaseUrl/api/v1/pools/swap/quote" -Token $token -Body @{
    poolId    = $PoolId
    tokenInId = $SrubTokenId
    amountIn  = $HedgeAmount
}
Write-Host ("  Estimated CNY out: {0,15:N0}" -f $quote.estimatedAmountOut)
Write-Host ("  Fee (10 bps):       {0,15:N0} SRUB units" -f $quote.estimatedFee)
Write-Host ("  Execution price:    {0,15:N6} (CNY per SRUB unit)" -f $quote.estimatedPrice)
Write-Host ("  Price impact:       {0:N4}%" -f $quote.priceImpactPct)

# ─────────────────────────────────────────────────────────────────────────
# Step 4 — economics comparison
# ─────────────────────────────────────────────────────────────────────────
Write-Step "Step 3 — Cost vs dealer desk"
$dlmm_fee_rubles = $quote.estimatedFee
$dealer_30bps   = [int64]($HedgeAmount * 30 / 10000)
$dealer_100bps  = [int64]($HedgeAmount * 100 / 10000)
$savings_30bps  = $dealer_30bps - $dlmm_fee_rubles
$savings_100bps = $dealer_100bps - $dlmm_fee_rubles
Write-Host ""
Write-Host ("  Sber DLMM (10 bps):              {0,15:N0} SRUB units" -f $dlmm_fee_rubles)
Write-Host ("  Raiffeisen dealer desk (30 bps): {0,15:N0} SRUB units" -f $dealer_30bps)
Write-Host ("  Crypto OTC (100 bps):            {0,15:N0} SRUB units" -f $dealer_100bps)
Write-Host ""
Write-Host ("  Savings vs Raiffeisen:           {0,15:N0} per hedge ({1}× cheaper)" -f $savings_30bps, [math]::Round($dealer_30bps / [math]::Max(1, $dlmm_fee_rubles), 1))
Write-Host ("  Savings vs crypto OTC:           {0,15:N0} per hedge ({1}× cheaper)" -f $savings_100bps, [math]::Round($dealer_100bps / [math]::Max(1, $dlmm_fee_rubles), 1))
Write-Host ""
Write-Host "  Mid-cap with 50 hedges/year: ~1.25M RUB saved (one junior analyst's salary)."
Write-Host "  Plus T+0 settlement vs T+2 = no dealer-desk overhead."
Read-Host "Press Enter to execute the hedge..."

# ─────────────────────────────────────────────────────────────────────────
# Step 5 — execute
# ─────────────────────────────────────────────────────────────────────────
Write-Step "Step 4 — Execute the hedge"
$started = Get-Date
$idemKey = "fx-hedge-demo-$([DateTimeOffset]::Now.ToUnixTimeMilliseconds())"
$swap = Invoke-Json -Method POST -Url "$BaseUrl/api/v1/pools/swap" -Token $token -Body @{
    poolId         = $PoolId
    tokenInId      = $SrubTokenId
    amountIn       = $HedgeAmount
    minAmountOut   = 0
    idempotencyKey = $idemKey
}
$elapsed = (Get-Date) - $started
Write-Host "  ✓ Executed in $([math]::Round($elapsed.TotalMilliseconds))ms"
Write-Host ("  Tx ID:              {0}" -f $swap.txId)
Write-Host ("  CNY received:       {0,15:N0}" -f $swap.amountOut)
Write-Host ("  Fee paid:           {0,15:N0} ({1} bps)" -f $swap.feeAmount, $swap.feeBps)
Write-Host ("  Execution price:    {0,15:N6}" -f $swap.executionPrice)
Write-Host ""
Write-Host "  Compare: Raiffeisen dealer would have been a phone call,"
Write-Host "  ~2 days settlement, no machine-readable confirmation."

# ─────────────────────────────────────────────────────────────────────────
# Step 6 — show audit trail in DB (CFO's accountant view)
# ─────────────────────────────────────────────────────────────────────────
Write-Step "Step 5 — Audit trail (what corp accountant gets next morning)"
Write-Host "Run on the postgres container:"
Write-Host "  docker exec dlmm-postgres psql -U dlmm -d dlmm -c \"SELECT id, status, amount_in, fee_amount, confirmed_at FROM transactions WHERE id='$($swap.txId)';\""
Write-Host ""
Write-Host "The same row is published to Kafka via the transactional outbox"
Write-Host "(durable, even if Kafka was briefly down — see kill-Kafka drill"
Write-Host "in docs/DEMO-SCRIPT.md §4). Downstream: SBBOL integration,"
Write-Host "1С reconciliation, regulator reporting all consume from this stream."

Write-Step "Demo complete"
Write-Host "What we showed: SberID login → quote → executed hedge → audit trail."
Write-Host "What's not on this script (separate slides):"
Write-Host "  - 30-day fast-forward + reverse hedge for P&L"
Write-Host "  - Counterparty exposure limits (Sprint 4 #4.2)"
Write-Host "  - SBBOL single sign-on (Sprint 4 #4.C)"
Write-Host "  - Pilot client onboarding flow"
