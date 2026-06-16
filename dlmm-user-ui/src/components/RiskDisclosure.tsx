import { Alert, Space, Typography } from 'antd'
import { WarningFilled } from '@ant-design/icons'
import { useTranslation } from 'react-i18next'
import Glossary from './Glossary'

const { Text } = Typography

interface Props {
  /** Drives the wording: LP = impermanent loss prominence; SWAP =
   *  slippage prominence; HEDGE = counterparty / settlement. */
  variant: 'lp' | 'swap' | 'hedge'
}

/**
 * Sprint 12 G-14 — risk disclosure banner.
 *
 * Always-on banner on the add-liquidity / swap / hedge flows. Compliance
 * required — Anna-driven feedback: «Стоп. Вы только что сказали что я могу
 * потерять. А до этого говорили "это просто как депозит"».
 *
 * Not dismissible by design — compliance + АСВ/DIA disclosure must be visible
 * EVERY time, not once on first visit. Tone: neutral-informative, not FUD;
 * warning Alert, never danger.
 *
 * i18n (2026-06-17): wording lives under `risk.*` (ru+en). The inline
 * <Glossary>/<Text strong> emphasis is preserved by composing several t()
 * calls per sentence rather than a single <Trans> — same render, simpler keys.
 */
export default function RiskDisclosure({ variant }: Props) {
  const { t } = useTranslation()

  const content = {
    lp: (
      <Space direction="vertical" size={4}>
        <Text style={{ fontSize: 'var(--text-sm)' }}>
          <Glossary term="il"><Text strong>{t('risk.lp.ilTerm')}</Text></Glossary>{' '}{t('risk.lp.ilBody')}
        </Text>
        <Text style={{ fontSize: 'var(--text-sm)' }}>
          <Text strong>{t('risk.lp.notDeposit')}</Text>{' '}{t('risk.lp.asvBefore')}{' '}<Text strong>{t('risk.lp.asvNot')}</Text>{' '}{t('risk.lp.asvAfter')}
        </Text>
        <Text style={{ fontSize: 'var(--text-sm)' }}>
          {t('risk.lp.pastPerf')}
        </Text>
      </Space>
    ),
    swap: (
      <Space direction="vertical" size={4}>
        <Text style={{ fontSize: 'var(--text-sm)' }}>
          <Glossary term="slippage"><Text strong>{t('risk.swap.slipTerm')}</Text></Glossary>{' '}{t('risk.swap.slipBody')}
        </Text>
        <Text style={{ fontSize: 'var(--text-sm)' }}>
          {t('risk.swap.reverse')}
        </Text>
      </Space>
    ),
    hedge: (
      <Space direction="vertical" size={4}>
        <Text style={{ fontSize: 'var(--text-sm)' }}>
          {t('risk.hedge.fixedRate')}
        </Text>
        <Text style={{ fontSize: 'var(--text-sm)' }}>
          {t('risk.hedge.oppBefore')}{' '}<Text strong>{t('risk.hedge.oppCost')}</Text>{' '}{t('risk.hedge.oppAfter')}
        </Text>
        <Text style={{ fontSize: 'var(--text-sm)' }}>
          {t('risk.hedge.obligation')}
        </Text>
      </Space>
    ),
  }[variant]

  return (
    <Alert
      type="warning"
      showIcon
      icon={<WarningFilled />}
      message={<Text strong>{t('risk.title')}</Text>}
      description={content}
      style={{ borderRadius: 'var(--radius-sm)' }}
    />
  )
}
