/**
 * Sprint 7 dedup — extracted from SwapPage + PoolsPage where the same
 * pairAccent function + TokenChip component was copy-pasted (the
 * SwapPage version literally had the comment "Same accent function as
 * PoolsPage — keeps token chips consistent across the app." admitting
 * the copy). Now one source of truth.
 *
 * <p>Deterministic accent colour per token symbol via stable hash —
 * gives every chip a unique visual identity without requiring real
 * per-token branding assets.
 */

/**
 * Hash → 8-colour palette pair. Pure function, exported for unit testing.
 */
export function pairAccent(symbol: string): { from: string; to: string } {
  const palette: Array<{ from: string; to: string }> = [
    { from: '#21A038', to: '#00C853' },  // sber green
    { from: '#00B5A1', to: '#21A038' },  // aqua/green
    { from: '#6E5BFF', to: '#00B5A1' },  // violet/aqua
    { from: '#FFB320', to: '#FF6F61' },  // amber/coral
    { from: '#0EA5E9', to: '#6E5BFF' },  // blue/violet
    { from: '#21A038', to: '#FFB320' },  // green/amber
    { from: '#FF6F61', to: '#6E5BFF' },  // coral/violet
    { from: '#00C853', to: '#0EA5E9' },  // green/blue
  ]
  let hash = 0
  for (let i = 0; i < symbol.length; i++) hash = (hash * 31 + symbol.charCodeAt(i)) >>> 0
  return palette[hash % palette.length]
}

export interface TokenChipProps {
  symbol?: string
  /** Pixel size (square). Default 32 — matches SwapPage usage. */
  size?: number
  /** Font size override. Default 10 — fits 4-letter ticker at 32px. */
  fontSize?: number
}

export default function TokenChip({ symbol, size = 32, fontSize = 10 }: TokenChipProps) {
  if (!symbol) {
    return (
      <div
        className="sber-token-chip"
        style={{ background: '#E5E7EB', width: size, height: size, fontSize }}
        aria-label="Токен не выбран"
      >
        —
      </div>
    )
  }
  const c = pairAccent(symbol)
  return (
    <div
      className="sber-token-chip"
      style={{
        background: `linear-gradient(135deg, ${c.from}, ${c.to})`,
        width: size,
        height: size,
        fontSize,
      }}
      aria-label={`Токен ${symbol}`}
    >
      {symbol.slice(0, 4)}
    </div>
  )
}
