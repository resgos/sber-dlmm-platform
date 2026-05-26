import { Card, Typography, Table, Tag, Button, Space, Row, Col } from 'antd'
import { CheckCircleFilled, CloseCircleFilled, ThunderboltFilled, CrownFilled, RocketFilled, ArrowRightOutlined } from '@ant-design/icons'
import { useNavigate } from 'react-router-dom'

const { Title, Text, Paragraph } = Typography

/**
 * QW-2 (Batch #4, Sprint 14) — public pricing page.
 *
 * <p>No auth — sits next to /login, /register. Marketing pages нужен
 * landing для outbound sales и для self-serve prospect research.
 *
 * <p>Numbers are placeholders per PO decision pending — see commit
 * description for the decision point. Easy to swap when PO finalises.
 *
 * <p>"Cвязаться" CTA на ENTERPRISE → /register с pre-filled subject;
 * "Начать с PRO" → /register с pre-filled tier=PRO query param.
 */

interface TierFeatures {
  key: string
  feature: string
  free: boolean | string
  pro: boolean | string
  enterprise: boolean | string
}

const FEATURES: TierFeatures[] = [
  { key: 'rps', feature: 'API rate limit', free: '10 RPS', pro: '100 RPS', enterprise: '1000 RPS' },
  { key: 'pools', feature: 'Доступ ко всем пулам', free: true, pro: true, enterprise: true },
  { key: 'swap', feature: 'Свопы + добавление ликвидности', free: true, pro: true, enterprise: true },
  { key: 'analytics', feature: 'Базовая аналитика (TVL, APY, volume)', free: true, pro: true, enterprise: true },
  { key: 'pro-metrics', feature: 'Pro-метрики (Sharpe, MaxDD, 30d Vol)', free: false, pro: true, enterprise: true },
  { key: 'cohort', feature: 'Cohort analytics dashboard', free: false, pro: false, enterprise: true },
  { key: 'csv', feature: 'CSV export', free: false, pro: true, enterprise: true },
  { key: 'api-export', feature: 'Программный API + webhooks', free: false, pro: false, enterprise: true },
  { key: 'multi-org', feature: 'Multi-user / multi-org (Team)', free: false, pro: true, enterprise: true },
  { key: '2fa', feature: '2FA (TOTP)', free: true, pro: true, enterprise: true },
  { key: 'sso', feature: 'SAML SSO (Azure AD / Sber Federation)', free: false, pro: false, enterprise: true },
  { key: 'audit', feature: 'Audit log self-serve export', free: false, pro: false, enterprise: true },
  { key: 'sla', feature: 'SLA с гарантиями (99.9%)', free: false, pro: false, enterprise: true },
  { key: 'support', feature: 'Поддержка', free: 'Email · 48ч', pro: 'Email + chat · 8ч', enterprise: 'Dedicated CSM · 1ч' },
  { key: 'discount', feature: 'Volume rebate (10% при >10M ₽/день)', free: false, pro: true, enterprise: true },
  { key: 'kyc', feature: 'KYC (Знай своего клиента)', free: 'Обязательно', pro: 'Обязательно', enterprise: 'Обязательно + KYB' },
]

const TIERS = [
  {
    key: 'free',
    name: 'FREE',
    price: '0 ₽',
    period: 'навсегда',
    description: 'Старт для индивидуальных треjдеров',
    cta: 'Зарегистрироваться',
    ctaType: 'default' as const,
    icon: <ThunderboltFilled />,
    accent: 'var(--sber-green)',
  },
  {
    key: 'pro',
    name: 'PRO',
    price: '30 000 ₽',
    period: '/мес',
    description: 'Для активных treasury команд',
    cta: 'Начать с PRO',
    ctaType: 'primary' as const,
    icon: <RocketFilled />,
    accent: 'var(--sber-amber, #fa8c16)',
    highlight: true,
  },
  {
    key: 'enterprise',
    name: 'ENTERPRISE',
    price: 'от 500 000 ₽',
    period: '/мес',
    description: 'Под крупный корпорат / B2B issuer',
    cta: 'Связаться с командой',
    ctaType: 'primary' as const,
    icon: <CrownFilled />,
    accent: '#722ed1',
  },
]

function FeatureCell({ value }: { value: boolean | string }) {
  if (value === true) return <CheckCircleFilled style={{ color: 'var(--sber-green)', fontSize: 'var(--text-md)' }} />
  if (value === false) return <CloseCircleFilled style={{ color: 'var(--text-muted)', fontSize: 'var(--text-md)' }} />
  return <Text>{value}</Text>
}

export default function PricingPage() {
  const navigate = useNavigate()

  return (
    <div style={{ maxWidth: 1200, margin: '0 auto', padding: '48px 24px' }}>
      <div style={{ textAlign: 'center', marginBottom: 48 }}>
        <Title level={1} style={{ marginBottom: 12 }}>Тарифы Sber DLMM</Title>
        <Paragraph type="secondary" style={{ fontSize: 'var(--text-md)', maxWidth: 720, margin: '0 auto' }}>
          Прозрачная модель тарифов для индивидуальных трейдеров, treasury-команд и крупных корпоративных клиентов.
          Все тарифы включают полный доступ к пулам ликвидности — отличия в лимитах, аналитике и поддержке.
        </Paragraph>
      </div>

      <Row gutter={[24, 24]} justify="center" style={{ marginBottom: 48 }}>
        {TIERS.map((tier) => (
          <Col xs={24} md={8} key={tier.key}>
            <Card
              className="sber-card"
              hoverable
              style={{
                height: '100%',
                border: tier.highlight ? `2px solid ${tier.accent}` : '1px solid var(--border-light)',
                position: 'relative',
              }}
            >
              {tier.highlight && (
                <Tag color="orange" style={{ position: 'absolute', top: -12, right: 16, fontWeight: 600 }}>
                  Популярный выбор
                </Tag>
              )}
              <Space direction="vertical" size={16} style={{ width: '100%' }}>
                <Space>
                  <div style={{ color: tier.accent, fontSize: 'var(--text-xl)' }}>{tier.icon}</div>
                  <Title level={3} style={{ margin: 0, color: tier.accent }}>{tier.name}</Title>
                </Space>
                <div>
                  <Text strong style={{ fontSize: 'var(--text-2xl)' }}>{tier.price}</Text>
                  <Text type="secondary"> {tier.period}</Text>
                </div>
                <Paragraph type="secondary" style={{ margin: 0, minHeight: 44 }}>
                  {tier.description}
                </Paragraph>
                <Button
                  block
                  size="large"
                  type={tier.ctaType}
                  onClick={() => navigate(tier.key === 'enterprise' ? '/register?contact=enterprise' : `/register?tier=${tier.key}`)}
                >
                  {tier.cta} <ArrowRightOutlined />
                </Button>
              </Space>
            </Card>
          </Col>
        ))}
      </Row>

      <Card className="sber-card" title={<Title level={4} style={{ margin: 0 }}>Сравнение тарифов</Title>}>
        <Table<TierFeatures>
          dataSource={FEATURES}
          pagination={false}
          rowKey="key"
          columns={[
            { title: 'Возможность', dataIndex: 'feature', key: 'feature', width: '40%' },
            { title: <Text strong style={{ color: 'var(--sber-green)' }}>FREE</Text>, dataIndex: 'free', key: 'free', align: 'center', render: (v) => <FeatureCell value={v} /> },
            { title: <Text strong style={{ color: 'var(--sber-amber, #fa8c16)' }}>PRO</Text>, dataIndex: 'pro', key: 'pro', align: 'center', render: (v) => <FeatureCell value={v} /> },
            { title: <Text strong style={{ color: '#722ed1' }}>ENTERPRISE</Text>, dataIndex: 'enterprise', key: 'enterprise', align: 'center', render: (v) => <FeatureCell value={v} /> },
          ]}
        />
      </Card>

      <Card className="sber-card" style={{ marginTop: 24 }}>
        <Title level={4}>Часто задаваемые вопросы</Title>
        <Space direction="vertical" size={16} style={{ width: '100%', marginTop: 16 }}>
          <div>
            <Text strong>Можно ли перейти с FREE на PRO в любой момент?</Text>
            <Paragraph type="secondary">Да, апгрейд активируется мгновенно, биллинг пропорциональный. Даунгрейд — с начала следующего расчётного периода.</Paragraph>
          </div>
          <div>
            <Text strong>Как работает Volume rebate?</Text>
            <Paragraph type="secondary">Автоматическое снижение комиссии на 10% для PRO/ENTERPRISE при ежедневном volume &gt;10M ₽ за 30-дневный rolling window. Применяется к следующему расчётному периоду.</Paragraph>
          </div>
          <div>
            <Text strong>Что входит в ENTERPRISE SLA?</Text>
            <Paragraph type="secondary">99.9% uptime гарантия с financial credits при нарушении. RPO ≤ 15 мин, RTO ≤ 30 мин. Dedicated CSM для onboarding и эскалаций.</Paragraph>
          </div>
          <div>
            <Text strong>Как получить ENTERPRISE-тариф?</Text>
            <Paragraph type="secondary">Свяжитесь с командой — мы подготовим коммерческое предложение под ваш volume и use case. Типичный pilot-период 30 дней.</Paragraph>
          </div>
        </Space>
      </Card>

      <div style={{ textAlign: 'center', marginTop: 48 }}>
        <Text type="secondary" style={{ fontSize: 'var(--text-xs)' }}>
          Все цены без НДС. Договор оферты публикуется отдельно. KYC обязателен на любом тарифе. © 2026 ПАО Сбербанк.
        </Text>
      </div>
    </div>
  )
}
