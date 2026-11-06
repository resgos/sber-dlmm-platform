import { Card, Tag, Typography, Space, Alert, Spin, Divider } from 'antd'
import { CheckCircleOutlined, WarningOutlined, InfoCircleOutlined } from '@ant-design/icons'
import { useQuery } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { pools } from '@/api/services'
import type { LiquidityStrategy } from '@/api/types'
import { formatCompact } from '@/lib/format'
import { useDebouncedValue } from '@/lib/useDebouncedValue'

const { Text } = Typography

/**
 * Sprint 11 G-22 — Add-liquidity price-impact preview panel.
 *
 * <p>Inputs are debounced (300ms) so rapid typing fires one request
 * per pause instead of one per keystroke. Query is also gated on
 * having a complete-enough set of inputs so no call fires until the
 * user has filled both amount fields and the bin range.
 *
 * <p>React Query's 5s staleTime additionally absorbs the toggle-back
 * case (user nudges a value, then reverts).
 *
 * <p>Shows: TVL share, in-range chip, est. fee/day, warnings list.
 * Hidden entirely while inputs are insufficient.
 */
export interface AddLiquidityPreviewProps {
  poolId: string
  amountX: number | null
  amountY: number | null
  binMin: number | null
  binMax: number | null
  strategy: LiquidityStrategy
  tokenYSymbol: string
}

export default function AddLiquidityPreview({
  poolId,
  amountX,
  amountY,
  binMin,
  binMax,
  strategy,
  tokenYSymbol,
}: AddLiquidityPreviewProps) {
  const { t } = useTranslation()
  // Debounce typing — without this, "100000" issues 6 round-trips, one
  // per keystroke. 300ms = fast enough to feel live, slow enough to
  // catch the next keystroke. Strategy doesn't get debounced (one-click
  // change, no rapid sequence to absorb).
  const dAmountX = useDebouncedValue(amountX, 300)
  const dAmountY = useDebouncedValue(amountY, 300)
  const dBinMin = useDebouncedValue(binMin, 300)
  const dBinMax = useDebouncedValue(binMax, 300)

  // Gate: need both amounts > 0 AND both bin bounds. Backend rejects
  // amount=0 with @Min(1), and a one-sided range is meaningless.
  const enabled = !!(
    poolId &&
    dAmountX != null && dAmountX > 0 &&
    dAmountY != null && dAmountY > 0 &&
    dBinMin != null &&
    dBinMax != null &&
    dBinMin <= dBinMax
  )

  const { data, isLoading, isError } = useQuery({
    queryKey: ['preview-add-liquidity', poolId, dAmountX, dAmountY, dBinMin, dBinMax, strategy],
    queryFn: () => pools.previewAddLiquidity({
      poolId,
      amountX: dAmountX!,
      amountY: dAmountY!,
      binRangeMin: dBinMin!,
      binRangeMax: dBinMax!,
      strategy,
    }),
    enabled,
    staleTime: 5000,
    retry: false,
  })

  if (!enabled) return null

  return (
    <Card
      size="small"
      className="sber-card"
      data-testid="add-liquidity-preview"
      title={
        <Space>
          <InfoCircleOutlined />
          <Text strong>{t('addLiquidityPreview.title')}</Text>
        </Space>
      }
    >
      {isLoading && (
        <div style={{ textAlign: 'center', padding: '16px 0' }}>
          <Spin size="small" />
          <div style={{ marginTop: 8, fontSize: 'var(--text-xs)', color: 'var(--color-text-secondary)' }}>
            {t('addLiquidityPreview.loading')}
          </div>
        </div>
      )}
      {isError && (
        <Alert
          type="error"
          showIcon
          message={t('addLiquidityPreview.errorTitle')}
          description={t('addLiquidityPreview.errorHint')}
        />
      )}
      {data && (
        <Space direction="vertical" size={12} style={{ width: '100%' }}>
          <Space size={8} wrap>
            {data.inRange ? (
              <Tag icon={<CheckCircleOutlined />} color="success">
                {t('addLiquidityPreview.inRange')}
              </Tag>
            ) : (
              <Tag icon={<WarningOutlined />} color="orange">
                {t('addLiquidityPreview.outOfRange')}
              </Tag>
            )}
            <Tag color="blue">
              {t('addLiquidityPreview.tvlShare', {
                pct: Math.min(100, Math.max(0, Number.isFinite(data.tvlSharePct) ? data.tvlSharePct : 0)).toFixed(2),
              })}
            </Tag>
            {data.priceImpactBps > 0 && (
              <Tag color={data.priceImpactBps > 500 ? 'red' : 'orange'}>
                {t('addLiquidityPreview.priceImpact', { pct: (data.priceImpactBps / 100).toFixed(2) })}
              </Tag>
            )}
          </Space>

          <Divider style={{ margin: '4px 0' }} />

          <Space direction="vertical" size={4} style={{ width: '100%' }}>
            <div style={{ display: 'flex', justifyContent: 'space-between' }}>
              <Text type="secondary" style={{ fontSize: 'var(--text-xs)' }}>
                {t('addLiquidityPreview.binsUsed')}
              </Text>
              <Text style={{ fontVariantNumeric: 'tabular-nums' }}>
                {data.binAllocations.length}
              </Text>
            </div>
            <div style={{ display: 'flex', justifyContent: 'space-between' }}>
              <Text type="secondary" style={{ fontSize: 'var(--text-xs)' }}>
                {t('addLiquidityPreview.feesPerDay')}
              </Text>
              <Text style={{ fontVariantNumeric: 'tabular-nums' }}>
                {data.estimatedFeesPerDayY > 0
                  ? `≈ ${formatCompact(data.estimatedFeesPerDayY)} ${tokenYSymbol}`
                  : '—'}
              </Text>
            </div>
          </Space>

          {data.warnings.length > 0 && (
            <Alert
              type="warning"
              showIcon
              message={
                <Space direction="vertical" size={4} style={{ width: '100%' }}>
                  {data.warnings.map((w, i) => (
                    <Text key={i} style={{ fontSize: 'var(--text-sm)' }}>
                      {w}
                    </Text>
                  ))}
                </Space>
              }
            />
          )}
        </Space>
      )}
    </Card>
  )
}
