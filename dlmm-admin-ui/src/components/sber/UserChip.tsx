import { Space, Tooltip, Typography } from 'antd'
import { Link } from 'react-router-dom'
import { shortId } from '@/lib/format'

const { Text } = Typography

/**
 * Sprint 9 (post-DS-handoff) — user chip.
 *
 * <p>Replaces the "…000003" short-ID rendering on every list page that
 * shows a user (Transactions.user column, OTC initiator/counterparty).
 * Shows a small avatar circle with initials + email if joined, or just
 * the short ID. Always wraps the full UUID in a tooltip and links to
 * `/users/:id` so the operator can drill in.
 */
export interface UserChipProps {
  userId: string | null | undefined
  email?: string | null
  /** Visual size — sm for table cells, md for detail headers. */
  size?: 'sm' | 'md'
  /** Disable the router link when used inside a deeper Link wrapper. */
  noLink?: boolean
}

function initials(input: string): string {
  // "ivanov@example.com" → "iv"; "Иван Петров" → "ИП"
  const local = input.includes('@') ? input.split('@')[0] : input
  const parts = local.split(/[ ._-]/).filter(Boolean)
  if (parts.length >= 2) return (parts[0][0] + parts[1][0]).toUpperCase()
  return local.slice(0, 2).toUpperCase()
}

function colourForKey(key: string): string {
  const palette = [
    '#21A038', // green
    '#296AE3', // blue
    '#9B59B6', // purple
    '#16A085', // teal
    '#D14D00', // orange
    '#E74C3C', // red
  ]
  let h = 0
  for (let i = 0; i < key.length; i++) h = (h * 31 + key.charCodeAt(i)) | 0
  return palette[Math.abs(h) % palette.length]
}

export default function UserChip({ userId, email, size = 'sm', noLink }: UserChipProps) {
  if (!userId) return <Text type="secondary">—</Text>
  const display = email ?? shortId(userId)
  const key = email ?? userId
  const avatarSize = size === 'md' ? 24 : 20
  const fontSize = size === 'md' ? 13 : 12

  const body = (
    <Space size={6}>
      <span
        aria-hidden
        style={{
          display: 'inline-flex',
          alignItems: 'center',
          justifyContent: 'center',
          width: avatarSize,
          height: avatarSize,
          borderRadius: '50%',
          background: colourForKey(key),
          color: '#fff',
          fontSize: avatarSize <= 20 ? 9 : 11,
          fontWeight: 600,
          flexShrink: 0,
        }}
      >
        {initials(display)}
      </span>
      <Text
        style={{
          fontSize,
          fontFamily: email ? undefined : 'JetBrains Mono, monospace',
          maxWidth: 220,
          display: 'inline-block',
          overflow: 'hidden',
          textOverflow: 'ellipsis',
          whiteSpace: 'nowrap',
          verticalAlign: 'middle',
        }}
      >
        {display}
      </Text>
    </Space>
  )

  const tooltip = (
    <span style={{ fontFamily: 'JetBrains Mono, monospace' }}>{userId}</span>
  )

  if (noLink) {
    return <Tooltip title={tooltip}>{body}</Tooltip>
  }
  return (
    <Tooltip title={tooltip}>
      <Link to={`/users/${userId}`} style={{ color: 'inherit', textDecoration: 'none' }}>
        {body}
      </Link>
    </Tooltip>
  )
}
