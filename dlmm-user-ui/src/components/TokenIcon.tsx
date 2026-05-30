import { useId } from 'react'

/**
 * TokenIcon — one beautiful, theme-agnostic token glyph used everywhere
 * (Мои токены, TokenChip, TokenPairChip, selects).
 *
 * Headline assets get their real-world currency/commodity glyph
 * (₿ Ξ ₽ $ € ¥ Au); everything else (Russian equities, indices) falls back to
 * a clean monogram on a deterministic per-symbol gradient — so every token
 * still reads as distinct instead of an identical pale-green square. Rendered
 * as inline SVG: crisp at any size, no raster, readable on light AND dark
 * cards (own gradient + white glyph). No external/brand logos (copyright-safe).
 */
interface IconDef {
  glyph: string
  from: string
  to: string
  /** glyph size as a fraction of the icon box (default 0.52 for 1 char). */
  scale?: number
}

const KNOWN: Record<string, IconDef> = {
  SRUB: { glyph: '₽', from: '#34C152', to: '#0E7D2A' },
  SBTC: { glyph: '₿', from: '#F7A14B', to: '#C0760A' },
  SETH: { glyph: 'Ξ', from: '#8A93F2', to: '#3C2FB8' },
  SUSDT: { glyph: '$', from: '#3FBF96', to: '#0E6E5A' },
  SEUR: { glyph: '€', from: '#4D86FF', to: '#1D4FCC' },
  SCNY: { glyph: '¥', from: '#F2566F', to: '#A30020' },
  SGOLD: { glyph: 'Au', from: '#F4CE5E', to: '#B8860B', scale: 0.4 },
}

const PALETTE: Array<[string, string]> = [
  ['#34C152', '#0E7D2A'], ['#4D86FF', '#1D4FCC'], ['#9B8BFF', '#3C2FB8'],
  ['#3FBF96', '#0E6E5A'], ['#F2566F', '#A30020'], ['#F4A24B', '#D14D00'],
  ['#3FB6F0', '#0369A1'], ['#C07BD8', '#6C3483'],
]

function fallbackDef(sym: string): IconDef {
  let h = 0
  for (let i = 0; i < sym.length; i++) h = (h * 31 + sym.charCodeAt(i)) >>> 0
  const [from, to] = PALETTE[h % PALETTE.length]
  return { glyph: sym.replace(/[^A-Za-z0-9]/g, '').slice(0, 2).toUpperCase(), from, to, scale: 0.4 }
}

export default function TokenIcon({
  symbol,
  size = 32,
  decorative = false,
}: {
  symbol?: string | null
  size?: number
  /** When the symbol is already shown as adjacent text, hide the icon from
   *  the a11y tree to avoid a double announce ("Токен SBER SBER"). */
  decorative?: boolean
}) {
  const uid = useId().replace(/:/g, '')
  const gid = `tki-${uid}`
  const def: IconDef = symbol
    ? (KNOWN[symbol] ?? fallbackDef(symbol))
    : { glyph: '—', from: '#C7CDD6', to: '#9AA3AE', scale: 0.5 }
  const fontSize = 40 * (def.scale ?? 0.52)

  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 40 40"
      {...(decorative
        ? { 'aria-hidden': true }
        : { role: 'img', 'aria-label': symbol ? `Токен ${symbol}` : 'Токен' })}
      style={{ flexShrink: 0, display: 'block' }}
    >
      <defs>
        <linearGradient id={gid} x1="0" y1="0" x2="1" y2="1">
          <stop offset="0" stopColor={def.from} />
          <stop offset="1" stopColor={def.to} />
        </linearGradient>
      </defs>
      <circle cx="20" cy="20" r="20" fill={`url(#${gid})`} />
      {/* soft top sheen for a glossy coin feel */}
      <ellipse cx="14" cy="12" rx="12" ry="7" fill="#FFFFFF" opacity="0.18" />
      <text
        x="20"
        y="21"
        textAnchor="middle"
        dominantBaseline="central"
        fill="#FFFFFF"
        fontWeight={700}
        fontSize={fontSize}
        fontFamily='SB Sans Text, Onest, Inter, "Segoe UI Symbol", system-ui, sans-serif'
        style={{ letterSpacing: def.glyph.length > 1 ? '-0.5px' : '0' }}
      >
        {def.glyph}
      </text>
    </svg>
  )
}
