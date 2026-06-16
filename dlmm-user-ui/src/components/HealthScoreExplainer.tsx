import { useState } from 'react'
import { Alert, Space, Typography, Tag, Button } from 'antd'
import { HeartFilled, CloseOutlined } from '@ant-design/icons'
import { useTranslation } from 'react-i18next'

const { Text } = Typography

const DISMISS_KEY = 'dlmm.user.healthExplainerDismissed'

/**
 * Sprint 10 wave 3 — Health Score explainer card.
 *
 * Mounts at the top of PositionsPage on first visit only. Explains in
 * plain language (i18n: positions.healthExplainer.*) what the "Health"
 * column means and how to act on it. Once dismissed, stays gone
 * (localStorage flag).
 *
 * Why a one-shot banner instead of permanent help text: the badge
 * itself carries a tooltip + popover, so the long-form explanation
 * is onboarding-only. After the first session the user knows what
 * the heart icon means; the banner just clutters.
 */
export default function HealthScoreExplainer() {
  const { t } = useTranslation()
  const initiallyDismissed = (() => {
    try { return localStorage.getItem(DISMISS_KEY) === 'true' } catch { return false }
  })()
  const [dismissed, setDismissed] = useState(initiallyDismissed)

  if (dismissed) return null

  const dismiss = () => {
    setDismissed(true)
    try { localStorage.setItem(DISMISS_KEY, 'true') } catch { /* ignore */ }
  }

  return (
    <Alert
      type="info"
      style={{ borderRadius: 'var(--radius-md)' }}
      showIcon
      icon={<HeartFilled style={{ color: 'var(--sber-green)' }} />}
      closable={false}
      message={
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 12 }}>
          <Text strong>{t('positions.healthExplainer.title')}</Text>
          <Button size="small" type="text" icon={<CloseOutlined />} onClick={dismiss} aria-label={t('positions.healthExplainer.dismiss')} />
        </div>
      }
      description={
        <Space direction="vertical" size={6} style={{ marginTop: 4 }}>
          <Text style={{ fontSize: 'var(--text-sm)' }}>
            {t('positions.healthExplainer.intro')}
          </Text>
          <Space size={6} wrap>
            <Tag color="green" style={{ borderRadius: 'var(--radius-pill)' }}>{t('positions.healthExplainer.bandExcellent')}</Tag>
            <Tag color="lime" style={{ borderRadius: 'var(--radius-pill)' }}>{t('positions.healthExplainer.bandGood')}</Tag>
            <Tag color="orange" style={{ borderRadius: 'var(--radius-pill)' }}>{t('positions.healthExplainer.bandFair')}</Tag>
            <Tag color="red" style={{ borderRadius: 'var(--radius-pill)' }}>{t('positions.healthExplainer.bandPoor')}</Tag>
          </Space>
          <Text type="secondary" style={{ fontSize: 'var(--text-xs)' }}>
            {t('positions.healthExplainer.hint')}
          </Text>
        </Space>
      }
    />
  )
}
