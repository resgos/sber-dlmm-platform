import { Space, Typography } from 'antd'
import { SwapOutlined } from '@ant-design/icons'

const { Text } = Typography

/**
 * Sprint 9 (post-DS-handoff) — token pair chip.
 *
 * <p>Renders "BTC ⇌ USDT" with a small swap glyph. Used in Pools (Pair
 * column), Transactions (direction), OTC desk. Optionally renders a
 * gradient avatar before each symbol for stronger visual identity.
 *
 * <p>If no x symbol is passed, renders just `—`.
 */
export interface TokenPairChipProps {
  x?: string | null
  y?: string | null
  /** Use bigger fonts for detail-page hero blocks. */
  size?: 'sm' | 'md' | 'lg'
}

function colourForSymbol(sym: string): string {
  // Deterministic colour per token: hash the symbol to one of 6 hues.
  // Keeps the same token consistent across pages without a token-meta lookup.
  const palette = [
    'linear-gradient(135deg, #21A038, #14702A)',  // sber green
    'linear-gradient(135deg, #F2994A, #D14D00)',  // amber
    'linear-gradient(135deg, #2E6BFF, #1D4FCC)',  // blue
    'linear-gradient(135deg, #9B59B6, #6C3483)',  // purple
    'linear-gradient(135deg, #16A085, #0E6E5A)',  // teal
    'linear-gradient(135deg, #E74C3C, #B83328)',  // red
  ]
  let h = 0
  for (let i = 0; i < sym.length; i++) h = (h * 31 + sym.charCodeAt(i)) | 0
  return palette[Math.abs(h) % palette.length]
}

function Avatar({ sym, size }: { sym: string; size: number }) {
  return (
    <span
      aria-hidden
      style={{
        display: 'inline-flex',
        alignItems: 'center',
        justifyContent: 'center',
        width: size,
        height: size,
        borderRadius: '50%',
        background: colourForSymbol(sym),
        color: '#fff',
        fontSize: size <= 18 ? 9 : 11,
        fontWeight: 700,
        letterSpacing: 0,
        flexShrink: 0,
      }}
    >
      {sym.slice(0, 2)}
    </span>
  )
}

export default function TokenPairChip({ x, y, size = 'md' }: TokenPairChipProps) {
  if (!x && !y) return <Text type="secondary">—</Text>
  const avatarSize = size === 'lg' ? 22 : size === 'sm' ? 16 : 18
  const fontSize = size === 'lg' ? 15 : size === 'sm' ? 12 : 13
  return (
    <Space size={6}>
      {x && (
        <Space size={4}>
          <Avatar sym={x} size={avatarSize} />
          <Text strong style={{ fontSize }}>{x}</Text>
        </Space>
      )}
      {x && y && (
        <SwapOutlined
          aria-label="and"
          style={{ color: 'var(--text-muted, #9CA3AF)', fontSize: fontSize - 2 }}
        />
      )}
      {y && (
        <Space size={4}>
          <Avatar sym={y} size={avatarSize} />
          <Text strong style={{ fontSize }}>{y}</Text>
        </Space>
      )}
    </Space>
  )
}
