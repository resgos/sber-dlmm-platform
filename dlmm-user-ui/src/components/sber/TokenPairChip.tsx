import { Space, Typography } from 'antd'
import { SwapOutlined } from '@ant-design/icons'
import TokenIcon from '@/components/TokenIcon'

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

// Token avatars are rendered by the shared <TokenIcon> (currency glyph or
// monogram on a per-symbol gradient) — see components/TokenIcon.tsx. They are
// `decorative` here because the symbol text is shown right next to each one.

export default function TokenPairChip({ x, y, size = 'md' }: TokenPairChipProps) {
  if (!x && !y) return <Text type="secondary">—</Text>
  const avatarSize = size === 'lg' ? 22 : size === 'sm' ? 16 : 18
  const fontSize = size === 'lg' ? 15 : size === 'sm' ? 12 : 13
  return (
    <Space size={6}>
      {x && (
        <Space size={4}>
          <TokenIcon symbol={x} size={avatarSize} decorative />
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
          <TokenIcon symbol={y} size={avatarSize} decorative />
          <Text strong style={{ fontSize }}>{y}</Text>
        </Space>
      )}
    </Space>
  )
}
