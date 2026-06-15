import { useState } from 'react'
import { Card, InputNumber, Segmented, Typography, Space, Divider } from 'antd'
import { CalculatorOutlined, InfoCircleOutlined } from '@ant-design/icons'
import { useTranslation } from 'react-i18next'
import { projectFeeIncome, periodReturnPct } from '@/lib/yield'
import { formatRub } from '@/lib/format'

const { Text, Title } = Typography

interface YieldCalculatorProps {
  /** Pool's current annualised APY as a whole-number percent (e.g. 12.5). */
  apyPct: number
}

const DAY_OPTIONS = [7, 30, 90, 365]
const DEFAULT_AMOUNT = 100_000

/**
 * Pool-page yield calculator — turns the abstract APY into rubles a retail
 * investor understands ("deposit 100 000 ₽ for 30 days → ≈ N ₽ in fees").
 * Pure client-side projection over the live `apyPct` (see lib/yield); states
 * the current-APY / no-IL caveat so the estimate is never mistaken for a
 * guarantee. Degrades to a neutral "no APY data" line when the pool has none.
 */
export default function YieldCalculator({ apyPct }: YieldCalculatorProps) {
  const { t } = useTranslation()
  const [amount, setAmount] = useState<number>(DEFAULT_AMOUNT)
  const [days, setDays] = useState<number>(30)

  const hasApy = apyPct > 0
  const fees = projectFeeIncome(amount ?? 0, apyPct, days)
  const periodPct = periodReturnPct(apyPct, days)

  return (
    <Card
      size="small"
      className="sber-card"
      title={
        <Space size={8}>
          <CalculatorOutlined style={{ color: 'var(--sber-green)' }} />
          {t('poolDetail.yieldCalc.title')}
        </Space>
      }
    >
      <Space direction="vertical" size="middle" style={{ width: '100%' }}>
        <Space direction="vertical" size={4} style={{ width: '100%' }}>
          <Text type="secondary" style={{ fontSize: 'var(--text-xs)' }}>
            {t('poolDetail.yieldCalc.amountLabel')}
          </Text>
          <InputNumber
            value={amount}
            onChange={(v) => setAmount(typeof v === 'number' ? v : 0)}
            min={0}
            step={10_000}
            style={{ width: '100%' }}
            addonAfter="₽"
            formatter={(v) => `${v}`.replace(/\B(?=(\d{3})+(?!\d))/g, ' ')}
            parser={(v) => Number((v ?? '').replace(/\s/g, '')) || 0}
            aria-label={t('poolDetail.yieldCalc.amountLabel')}
          />
        </Space>

        <Space direction="vertical" size={4} style={{ width: '100%' }}>
          <Text type="secondary" style={{ fontSize: 'var(--text-xs)' }}>
            {t('poolDetail.yieldCalc.periodLabel')}
          </Text>
          <Segmented
            block
            value={days}
            onChange={(v) => setDays(Number(v))}
            options={DAY_OPTIONS.map((d) => ({ label: t('poolDetail.yieldCalc.daysOption', { count: d }), value: d }))}
          />
        </Space>

        <Divider style={{ margin: 0 }} />

        {hasApy ? (
          <Space direction="vertical" size={2} style={{ width: '100%' }}>
            <Text type="secondary" style={{ fontSize: 'var(--text-xs)' }}>
              {t('poolDetail.yieldCalc.resultLabel')}
            </Text>
            <Title level={4} style={{ margin: 0, color: 'var(--sber-green)' }}>
              ≈ {formatRub(fees)}
            </Title>
            <Text type="secondary" style={{ fontSize: 'var(--text-sm)' }}>
              {t('poolDetail.yieldCalc.resultSub', { pct: periodPct.toFixed(2), apy: apyPct.toFixed(2) })}
            </Text>
          </Space>
        ) : (
          <Text type="secondary">{t('poolDetail.yieldCalc.noApy')}</Text>
        )}

        <Text type="secondary" style={{ fontSize: 'var(--text-xs)' }}>
          <Space size={4} align="start">
            <InfoCircleOutlined />
            <span>{t('poolDetail.yieldCalc.disclaimer')}</span>
          </Space>
        </Text>
      </Space>
    </Card>
  )
}
