#requires -Version 5.1
<#
.SYNOPSIS
  Sber DLMM platform — synthetic load + commission report.

.DESCRIPTION
  Logs in as one of the seed users and:
    1. Lists all pools
    2. Pulls a swap quote for each pool against SRUB
    3. Executes a series of swaps and add-liquidity operations
    4. Aggregates fee revenue per pool from /api/v1/transactions
    5. Prints a leaderboard

  Run with the docker-compose stack already up (`cd docker && docker-compose up -d`).

.PARAMETER GatewayUrl
  Base URL of dlmm-gateway. Defaults to http://localhost:8080.

.PARAMETER SwapsPerPool
  Number of swap operations to execute per pool. Default 3.

.PARAMETER Email
  Seed user to authenticate as. Default ivanov@example.com (Demo1234).

.EXAMPLE
  .\simulate-trading.ps1
  .\simulate-trading.ps1 -SwapsPerPool 5 -Email admin@sber-dlmm.ru
#>
param(
    [string]$GatewayUrl = 'http://localhost:8080',
    [int]$SwapsPerPool = 3,
    [string]$Email = 'ivanov@example.com',
    [string]$Password = 'Demo1234'
)

$ErrorActionPreference = 'Continue'
$ProgressPreference = 'SilentlyContinue'

function Login {
    $body = @{ email = $Email; password = $Password } | ConvertTo-Json
    Invoke-RestMethod -Uri "$GatewayUrl/api/v1/auth/login" -Method Post -ContentType 'application/json' -Body $body
}

function Get-Pools {
    param([hashtable]$Headers)
    $r = Invoke-RestMethod -Uri "$GatewayUrl/api/v1/pools?page=0&size=100" -Headers $Headers
    return $r.content
}

function Get-Tokens {
    param([hashtable]$Headers)
    $r = Invoke-RestMethod -Uri "$GatewayUrl/api/v1/tokens?page=0&size=100" -Headers $Headers
    $map = @{}
    foreach ($t in $r.content) { $map[$t.id] = $t }
    return $map
}

function Get-SwapQuote {
    param([hashtable]$Headers, [string]$PoolId, [string]$TokenInId, [long]$AmountIn)
    $body = @{
        poolId = $PoolId
        tokenInId = $TokenInId
        amountIn = $AmountIn
    } | ConvertTo-Json
    try {
        return Invoke-RestMethod -Uri "$GatewayUrl/api/v1/pools/swap/quote" -Method Post -ContentType 'application/json' -Body $body -Headers $Headers
    } catch {
        return $null
    }
}

function Execute-Swap {
    param([hashtable]$Headers, [string]$PoolId, [string]$TokenInId, [long]$AmountIn, [string]$IdempotencyKey)
    $body = @{
        poolId = $PoolId
        tokenInId = $TokenInId
        amountIn = $AmountIn
        minAmountOut = 0
        idempotencyKey = $IdempotencyKey
    } | ConvertTo-Json
    try {
        return Invoke-RestMethod -Uri "$GatewayUrl/api/v1/pools/swap" -Method Post -ContentType 'application/json' -Body $body -Headers $Headers
    } catch {
        $code = $_.Exception.Response.StatusCode.value__
        Write-Verbose "  swap failed: status=$code  $($_.Exception.Message)"
        return $null
    }
}

function Format-Big {
    param([double]$N)
    if ([double]::IsNaN($N)) { return 'n/a' }
    if ($N -ge 1e9) { return ('{0:N2}B' -f ($N / 1e9)) }
    if ($N -ge 1e6) { return ('{0:N2}M' -f ($N / 1e6)) }
    if ($N -ge 1e3) { return ('{0:N1}K' -f ($N / 1e3)) }
    return ('{0:N2}' -f $N)
}

# ─── Run ────────────────────────────────────────────────────────────────────
Write-Host '=== Sber DLMM trading simulation ===' -ForegroundColor Green
Write-Host "Gateway: $GatewayUrl    user: $Email    swaps/pool: $SwapsPerPool"

Write-Host '`nLogging in...' -ForegroundColor Yellow
$auth = Login
$headers = @{ Authorization = "Bearer $($auth.accessToken)" }
Write-Host "  ok — userId=$($auth.user.id) role=$($auth.user.role)"

Write-Host '`nFetching catalog...' -ForegroundColor Yellow
$tokens = Get-Tokens -Headers $headers
$pools = Get-Pools -Headers $headers
Write-Host "  $($tokens.Count) tokens, $($pools.Count) pools"

# Aggregate stats
$stats = @{}
$totalSwapsRequested = 0
$totalSwapsConfirmed = 0
$totalFeesRub = [double]0

Write-Host "`nExecuting $SwapsPerPool swaps per pool..." -ForegroundColor Yellow
foreach ($pool in $pools) {
    $tx = if ($tokens.ContainsKey($pool.tokenXId)) { $tokens[$pool.tokenXId] } else { $null }
    $ty = if ($tokens.ContainsKey($pool.tokenYId)) { $tokens[$pool.tokenYId] } else { $null }
    $pairLabel = if ($tx -and $ty) { "$($tx.symbol)/$($ty.symbol)" } else { "POOL-$($pool.id.Substring(0,8))" }
    $stats[$pool.id] = @{ pair = $pairLabel; requested = 0; confirmed = 0; fees = [double]0; volume = [double]0 }

    for ($i = 1; $i -le $SwapsPerPool; $i++) {
        # Swap small amount of token Y (SRUB) into token X
        $amount = [long](Get-Random -Minimum 1000000 -Maximum 50000000)
        $idem = "sim-$([Guid]::NewGuid().ToString('N').Substring(0,16))"
        $stats[$pool.id].requested++
        $totalSwapsRequested++

        $quote = Get-SwapQuote -Headers $headers -PoolId $pool.id -TokenInId $pool.tokenYId -AmountIn $amount
        if ($null -ne $quote) {
            $result = Execute-Swap -Headers $headers -PoolId $pool.id -TokenInId $pool.tokenYId -AmountIn $amount -IdempotencyKey $idem
            if ($null -ne $result -and $result.status -eq 'CONFIRMED') {
                $stats[$pool.id].confirmed++
                $stats[$pool.id].fees += [double]$result.feeAmount
                $stats[$pool.id].volume += [double]$amount
                $totalSwapsConfirmed++
                $totalFeesRub += [double]$result.feeAmount
            }
        }
        Start-Sleep -Milliseconds 50
    }
    Write-Host ("  {0,-14} req={1}  ok={2}  fees={3}" -f $pairLabel, $stats[$pool.id].requested, $stats[$pool.id].confirmed, (Format-Big $stats[$pool.id].fees))
}

Write-Host "`n=== Aggregated commission report ===" -ForegroundColor Green
Write-Host ("Total swap requests: {0}" -f $totalSwapsRequested)
Write-Host ("Total confirmed:     {0}" -f $totalSwapsConfirmed)
Write-Host ("Total fees collected (smallest unit): {0}" -f (Format-Big $totalFeesRub))

Write-Host "`nTop 10 pools by fee revenue:"
$leaderboard = $stats.GetEnumerator() | Sort-Object { -[double]$_.Value.fees } | Select-Object -First 10
foreach ($entry in $leaderboard) {
    $v = $entry.Value
    if ($v.confirmed -gt 0) {
        Write-Host ("  {0,-14}  swaps={1,3}  volume={2,8}  fees={3,8}" -f $v.pair, $v.confirmed, (Format-Big $v.volume), (Format-Big $v.fees))
    }
}

Write-Host "`nDone." -ForegroundColor Green
