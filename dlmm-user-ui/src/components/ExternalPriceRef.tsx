import { Card, Typography, Space, Tag, Tooltip } from 'antd'
import { GlobalOutlined, RiseOutlined, FallOutlined } from '@ant-design/icons'
import { useTranslation } from 'react-i18next'
import { useExternalRef } from '@/lib/externalPrice'
import { formatRub } from '@/lib/format'

const { Text } = Typography

/**
 * External real-world price reference for a pool's base asset.
 *
 * Shows the live CoinGecko price (in ₽) next to our internal pool price, plus
 * the deviation, so the user has a real-market «ориентир». Renders nothing
 * when the base asset has no external analog (e.g. Russian equities — those
 * need a MOEX backend proxy) or when the quote isn't SRUB (the reference is in
 * ₽, so the comparison is only meaningful against a SRUB-quoted price).
 */
export default function ExternalPriceRef({
  baseSymbol,
  internalPriceRub,
}: {
  baseSymbol: string | undefined
  /** Internal pool price of 1 base token, expressed in SRUB. */
  internalPriceRub: number
}) {
  const { t } = useTranslation()
  const ref = useExternalRef(baseSymbol)
  if (!ref || !(internalPriceRub > 0)) return null

  const delta = ((internalPriceRub - ref.priceRub) / ref.priceRub) * 100
  const positive = delta >= 0
  const absDelta = Math.abs(delta)
  const deltaColor = absDelta < 2 ? 'green' : absDelta < 10 ? 'orange' : 'red'
  const deltaStr = `${positive ? '+' : ''}${delta.toFixed(2)}`

  return (
    <Card
      size="small"
      className="sber-card"
      style={{ borderRadius: 'var(--radius-md)', border: '1px solid var(--border-light)', marginBottom: 'var(--space-4)' }}
      styles={{ body: { padding: 'var(--space-3) var(--space-4)' } }}
    >
      <Space size={12} wrap align="center" style={{ width: '100%', justifyContent: 'space-between' }}>
        <Space size={10} align="center">
          <GlobalOutlined style={{ color: 'var(--sber-violet, #6E5BFF)' }} />
          <div style={{ lineHeight: 1.25 }}>
            <Text type="secondary" style={{ fontSize: 'var(--text-xs)', display: 'block' }}>
              {t('poolDetail.externalRef.label')} · {ref.source}
            </Text>
            <Text strong style={{ fontVariantNumeric: 'tabular-nums', fontSize: 'var(--text-md)' }}>
              {formatRub(ref.priceRub)}
            </Text>
          </div>
        </Space>
        <Tooltip
          title={t('poolDetail.externalRef.tooltip', {
            internal: formatRub(internalPriceRub),
            source: ref.source,
            delta: deltaStr,
          })}
        >
          <Tag
            color={deltaColor}
            style={{ marginInlineEnd: 0, borderRadius: 'var(--radius-pill)', fontVariantNumeric: 'tabular-nums' }}
          >
            {positive ? <RiseOutlined /> : <FallOutlined />} {t('poolDetail.externalRef.deviationTag', { delta: deltaStr })}
          </Tag>
        </Tooltip>
      </Space>
    </Card>
  )
}
