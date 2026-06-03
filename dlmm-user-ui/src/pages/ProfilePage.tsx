import { Row, Col, Card, Typography, Space, Form, Input, Button, Descriptions, Avatar, Alert, Divider, message, Tag, Tabs, Switch } from 'antd'
import {
  UserOutlined,
  SaveOutlined,
  WalletOutlined,
  PieChartOutlined,
  ThunderboltFilled,
  HistoryOutlined,
  DashboardOutlined,
  SafetyOutlined,
  SettingOutlined,
} from '@ant-design/icons'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { useEffect, useMemo, useSyncExternalStore } from 'react'
import { useTranslation } from 'react-i18next'
import i18n from '@/i18n'
import { users, balances, pools as poolsApi, fees, transactions as txApi } from '@/api/services'
import { authStore } from '@/store/authStore'
import { uiPrefStore } from '@/store/uiPrefStore'
import KycStatusBadge from '@/components/KycStatusBadge'
import SelfRestrictionPanel from '@/components/SelfRestrictionPanel'
import ThemeToggle from '@/components/ThemeToggle'
import KycUploadPanel from '@/components/KycUploadPanel'
import AutoClaimSettings from '@/components/AutoClaimSettings'
import TwoFactorSettings from '@/components/TwoFactorSettings'
import { formatRub } from '@/components/StatCard'
import type { User, TokenBalance, Position, Transaction } from '@/api/types'
import dayjs from 'dayjs'
import relativeTime from 'dayjs/plugin/relativeTime'
import 'dayjs/locale/ru'

dayjs.extend(relativeTime)
dayjs.locale('ru')

const { Title, Text } = Typography

// Sprint 9 — same labels as the rest of the user-ui tx tables, resolved
// via i18n (reuses the shared dashboard.txType.* short labels) so the
// activity feed stays in sync with the active language.
const txTypeLabel = (key: string) => i18n.t(`dashboard.txType.${key}`, { defaultValue: key })

export default function ProfilePage() {
  const { t } = useTranslation()
  const queryClient = useQueryClient()
  const [form] = Form.useForm()
  const uiPrefs = useSyncExternalStore(uiPrefStore.subscribe, uiPrefStore.getSnapshot)

  const { data: user, isLoading } = useQuery<User>({
    queryKey: ['me'],
    queryFn: users.getMe,
  })

  useEffect(() => {
    if (user) {
      form.setFieldsValue({ firstName: user.firstName, lastName: user.lastName })
    }
  }, [user, form])

  const updateMutation = useMutation({
    mutationFn: (values: { firstName: string; lastName: string }) =>
      users.updateMe(values),
    onSuccess: () => {
      message.success(t('profile.edit.updateSuccess'))
      queryClient.invalidateQueries({ queryKey: ['me'] })
    },
    onError: (err: any) => {
      message.error(err?.response?.data?.message || t('profile.edit.updateError'))
    },
  })

  const kycStatus = user?.kycStatus || authStore.getUser()?.kycStatus || 'NOT_SUBMITTED'

  // Sprint 9 — extra reads to populate the right-hand sidebar (was a
  // big empty void before). All four queries are already cached by
  // DashboardPage / PositionsPage / TransactionsPage so the profile
  // hit is usually a cache read.
  const { data: myBalances } = useQuery({
    queryKey: ['myBalances'],
    queryFn: balances.getMyBalances,
  })
  const { data: myPositions } = useQuery({
    queryKey: ['myPositions'],
    queryFn: poolsApi.getMyPositions,
  })
  const { data: feeSummary } = useQuery({
    queryKey: ['myFeeSummary'],
    queryFn: fees.getMyFeeSummary,
  })
  const { data: recentTx } = useQuery({
    queryKey: ['myTransactions', 0, 5],
    queryFn: () => txApi.getMyTransactions(0, 5),
  })
  const { data: poolPage } = useQuery({
    queryKey: ['pools', 0, 100],
    queryFn: () => poolsApi.getPools(0, 100),
  })

  // Same SRUB-anchored pricing trick as the dashboard.
  const rubPriceBySymbol = useMemo(() => {
    const m = new Map<string, number>()
    m.set('SRUB', 1)
    for (const pool of poolPage?.content ?? []) {
      if (pool.tokenYSymbol === 'SRUB') m.set(pool.tokenXSymbol, pool.currentPrice)
      else if (pool.tokenXSymbol === 'SRUB' && pool.currentPrice > 0) {
        m.set(pool.tokenYSymbol, 1 / pool.currentPrice)
      }
    }
    return m
  }, [poolPage])

  const tokensHeld = (myBalances ?? []).filter((b: TokenBalance) => (b.available + b.locked) > 0).length
  const totalRub = (myBalances ?? []).reduce((s: number, b: TokenBalance) => {
    const price = rubPriceBySymbol.get(b.symbol) ?? 0
    return s + (b.available + b.locked) * price
  }, 0)
  const activePositionCount = (myPositions ?? []).filter((p: Position) => p.isActive).length
  const totalEarned = (feeSummary?.totalClaimed ?? 0) + (feeSummary?.totalUnclaimed ?? 0)

  return (
    <Space direction="vertical" size={24} style={{ width: '100%' }}>
      <Title level={4} className="sber-page-title">{t('profile.title')}</Title>

      <Row gutter={[24, 24]}>
      <Col xs={24} lg={14}>
      <Space direction="vertical" size={24} style={{ width: '100%' }}>

      {/* Sprint 6 #6.7 — самозапрет 115-ФЗ panel. Placed AFTER the */}
      {/* identity card so the user sees their identity first, then the */}
      {/* protection toggle. */}

      {/* KYC Status */}
      <Card className="sber-card">
        <div style={{ display: 'flex', alignItems: 'center', gap: 20, marginBottom: 16 }}>
          <Avatar
            size={64}
            icon={<UserOutlined />}
            style={{ backgroundColor: '#21A038', fontSize: 'var(--text-xl)' }}
          />
          <div>
            <Text strong style={{ fontSize: 'var(--text-md)', display: 'block' }}>
              {user?.fullName || `${user?.firstName || ''} ${user?.lastName || ''}`}
            </Text>
            <Text type="secondary">{user?.email}</Text>
          </div>
        </div>

        <div style={{ marginBottom: 16 }}>
          <Text type="secondary" style={{ display: 'block', marginBottom: 8 }}>{t('profile.kyc.label')}</Text>
          <KycStatusBadge status={kycStatus} large />
        </div>

        {kycStatus === 'PENDING' && (
          <Alert
            message={t('profile.kyc.pendingTitle')}
            description={t('profile.kyc.pendingBody')}
            type="info"
            showIcon
            style={{ borderRadius: 'var(--radius-sm)' }}
          />
        )}
        {kycStatus === 'NOT_SUBMITTED' && (
          <Alert
            message={t('profile.kyc.notSubmittedTitle')}
            description={t('profile.kyc.notSubmittedBody')}
            type="warning"
            showIcon
            style={{ borderRadius: 'var(--radius-sm)' }}
          />
        )}
        {kycStatus === 'REJECTED' && (
          <Alert
            message={t('profile.kyc.rejectedTitle')}
            description={t('profile.kyc.rejectedBody')}
            type="error"
            showIcon
            style={{ borderRadius: 'var(--radius-sm)' }}
          />
        )}
      </Card>

      {/* Sprint 9-DS-r4 P2-8 — KYC document upload (Sber ID stub).
          Renders the Dragger only when the user is in NOT_SUBMITTED or
          REJECTED; for PENDING/VERIFIED users it shows an info card
          and bails out. */}
      <KycUploadPanel
        kycStatus={kycStatus as 'NOT_SUBMITTED' | 'PENDING' | 'VERIFIED' | 'REJECTED'}
        // Sprint 10 F-21 — surface the admin-supplied rejection reason
        // when REJECTED. The backend User DTO doesn't carry the field
        // on this branch yet (admin UI collects it in the audit log);
        // a future User contract extension will populate it directly.
        rejectionReason={(user as User & { kycRejectionReason?: string })?.kycRejectionReason}
      />

      {/* Sprint 6 #6.7 — 115-ФЗ самозапрет */}
      <SelfRestrictionPanel />

      {/* Edit form */}
      <Card className="sber-card" title={<Text strong>{t('profile.edit.title')}</Text>}>
        <Form
          form={form}
          layout="vertical"
          onFinish={(values) => updateMutation.mutate(values)}
          initialValues={{
            firstName: user?.firstName,
            lastName: user?.lastName,
          }}
        >
          <Form.Item
            name="firstName"
            label={<span style={{ fontWeight: 500, color: '#374151' }}>{t('profile.edit.firstName')}</span>}
            rules={[{ required: true, message: t('profile.edit.firstNameRequired') }]}
          >
            <Input placeholder={t('profile.edit.firstNamePlaceholder')} style={{ height: 44, borderRadius: 'var(--radius-sm)' }} />
          </Form.Item>

          <Form.Item
            name="lastName"
            label={<span style={{ fontWeight: 500, color: '#374151' }}>{t('profile.edit.lastName')}</span>}
            rules={[{ required: true, message: t('profile.edit.lastNameRequired') }]}
          >
            <Input placeholder={t('profile.edit.lastNamePlaceholder')} style={{ height: 44, borderRadius: 'var(--radius-sm)' }} />
          </Form.Item>

          <Form.Item
            label={<span style={{ fontWeight: 500, color: '#374151' }}>{t('profile.edit.email')}</span>}
          >
            <Input value={user?.email} disabled style={{ height: 44, borderRadius: 'var(--radius-sm)' }} />
          </Form.Item>

          <Button
            type="primary"
            htmlType="submit"
            icon={<SaveOutlined />}
            loading={updateMutation.isPending}
            style={{ height: 44, borderRadius: 'var(--radius-sm)' }}
          >
            {t('profile.edit.submit')}
          </Button>
        </Form>
      </Card>

      {/* Account info */}
      <Card className="sber-card" title={<Text strong>{t('profile.info.title')}</Text>}>
        <Descriptions bordered column={1} size="small">
          <Descriptions.Item label={t('profile.info.userId')}>
            <Text copyable={{ text: user?.id }}>{user?.id}</Text>
          </Descriptions.Item>
          <Descriptions.Item label={t('profile.info.role')}>{user?.role || '—'}</Descriptions.Item>
          <Descriptions.Item label={t('profile.info.createdAt')}>
            {user?.createdAt ? dayjs(user.createdAt).format('DD.MM.YYYY HH:mm') : '—'}
          </Descriptions.Item>
        </Descriptions>
      </Card>

      </Space>
      </Col>

      {/* Sprint 9 — right-rail. Previously the profile page stopped at the
          640px maxWidth and the whole right half was empty white space.
          Now: account snapshot + recent activity, so the page reads as
          "your account" instead of "your settings form".
          UI-CRITIQUE 2026-05-22 #6 — right-rail had grown to 5 stacked
          cards (Сводка + Recent activity + 2FA + AutoClaim + Theme) ≈
          1200px scroll. Wrapped в Tabs: Обзор / Безопасность /
          Настройки. ONE thing visible at a time → user finds the
          control they need without scroll hunt. */}
      <Col xs={24} lg={10}>
      <Tabs
        defaultActiveKey="overview"
        items={[
          {
            key: 'overview',
            label: <Space size={6}><DashboardOutlined />{t('profile.tabs.overview')}</Space>,
            children: (
              <Space direction="vertical" size={24} style={{ width: '100%' }}>

        <Card
          className="sber-card"
          title={<Text strong>{t('profile.summary.title')}</Text>}
          styles={{ body: { padding: 0 } }}
        >
          <div style={{ padding: '14px 18px', borderBottom: '1px solid var(--border-light)' }}>
            <Space size={10}>
              <div style={{
                width: 36, height: 36, borderRadius: 'var(--radius-sm)',
                background: 'var(--sber-green-light)',
                display: 'flex', alignItems: 'center', justifyContent: 'center',
              }}>
                <WalletOutlined style={{ color: 'var(--sber-green)', fontSize: 'var(--text-md)' }} />
              </div>
              <div>
                <Text type="secondary" style={{ fontSize: 'var(--text-xs)', display: 'block' }}>
                  {t('profile.summary.portfolioValue')}
                </Text>
                <Text strong style={{ fontSize: 'var(--text-lg)', fontVariantNumeric: 'tabular-nums' }}>
                  {formatRub(totalRub)}
                </Text>
              </div>
            </Space>
          </div>

          <div style={{
            display: 'grid', gridTemplateColumns: '1fr 1fr',
            borderBottom: '1px solid var(--border-light)',
          }}>
            <div style={{ padding: 14, borderRight: '1px solid var(--border-light)' }}>
              <Text type="secondary" style={{ fontSize: 'var(--text-xs)' }}>{t('profile.summary.assetsHeld')}</Text>
              <div style={{ fontSize: 'var(--text-md)', fontWeight: 600, fontVariantNumeric: 'tabular-nums' }}>
                {tokensHeld}
              </div>
            </div>
            <div style={{ padding: 14 }}>
              <Text type="secondary" style={{ fontSize: 'var(--text-xs)' }}>{t('profile.summary.activePositions')}</Text>
              <div style={{ fontSize: 'var(--text-md)', fontWeight: 600, color: 'var(--sber-green)', fontVariantNumeric: 'tabular-nums' }}>
                {activePositionCount}
              </div>
            </div>
          </div>

          <div style={{ padding: 14 }}>
            <Text type="secondary" style={{ fontSize: 'var(--text-xs)' }}>{t('profile.summary.earnedOnLiquidity')}</Text>
            <div style={{ fontSize: 'var(--text-md)', fontWeight: 600, color: 'var(--sber-green)', fontVariantNumeric: 'tabular-nums' }}>
              {formatRub(totalEarned)}
            </div>
            {feeSummary && feeSummary.totalUnclaimed > 0 && (
              <Tag color="green" style={{ marginTop: 6, borderRadius: 'var(--radius-pill)' }}>
                <ThunderboltFilled style={{ fontSize: 10, marginRight: 4 }} />
                {t('profile.summary.toClaim', { amount: formatRub(feeSummary.totalUnclaimed) })}
              </Tag>
            )}
          </div>
        </Card>

        <Card
          className="sber-card"
          title={
            <Space>
              <HistoryOutlined style={{ color: 'var(--text-secondary)' }} />
              <Text strong>{t('profile.activity.title')}</Text>
            </Space>
          }
          styles={{ body: { padding: 0 } }}
        >
          {!recentTx?.content?.length ? (
            <div style={{ padding: 18 }}>
              <Text type="secondary">{t('profile.activity.empty')}</Text>
            </div>
          ) : (
            <Space direction="vertical" size={0} style={{ width: '100%' }}>
              {recentTx.content.map((tx: Transaction, i: number) => (
                <div
                  key={tx.id}
                  style={{
                    padding: '12px 16px',
                    borderBottom: i < recentTx.content.length - 1 ? '1px solid var(--border-light)' : 'none',
                    display: 'flex',
                    justifyContent: 'space-between',
                    alignItems: 'center',
                  }}
                >
                  <div style={{ minWidth: 0 }}>
                    <div style={{ fontSize: 'var(--text-sm)', fontWeight: 500 }}>
                      {txTypeLabel(tx.txType)}
                    </div>
                    <div style={{
                      fontSize: 'var(--text-xs)',
                      color: 'var(--text-secondary)',
                      fontFamily: 'JetBrains Mono, monospace',
                    }}>
                      {dayjs(tx.createdAt).fromNow()}
                    </div>
                  </div>
                  <Tag
                    color={tx.status === 'CONFIRMED' ? 'success' : tx.status === 'FAILED' ? 'error' : 'processing'}
                    style={{ borderRadius: 'var(--radius-pill)', padding: '0 10px', marginInlineEnd: 0 }}
                  >
                    {tx.status === 'CONFIRMED' ? t('profile.activity.executed') : tx.status === 'FAILED' ? t('profile.activity.failed') : tx.status}
                  </Tag>
                </div>
              ))}
            </Space>
          )}
        </Card>

              </Space>
            ),
          },
          {
            key: 'security',
            label: <Space size={6}><SafetyOutlined />{t('profile.tabs.security')}</Space>,
            children: (
              <Space direction="vertical" size={24} style={{ width: '100%' }}>
                {/* Sprint 11 G-20 — 2FA TOTP settings. Frontend MVP — verify
                    stub accepts any 6-digit code; real backend TOTP validate
                    lands in Sprint 12. */}
                <TwoFactorSettings />
              </Space>
            ),
          },
          {
            key: 'settings',
            label: <Space size={6}><SettingOutlined />{t('profile.tabs.settings')}</Space>,
            children: (
              <Space direction="vertical" size={24} style={{ width: '100%' }}>
                {/* S14-03 — simple-mode toggle. Hides advanced sidebar items
                    (Ребаланс, Команда) for users who prefer a cleaner UX.
                    Persisted to localStorage via uiPrefStore. */}
                <Card className="sber-card" title={<Text strong>{t('profile.settings.interfaceModeTitle')}</Text>}>
                  <Space direction="vertical" size={8} style={{ width: '100%' }}>
                    <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
                      <div>
                        <Text strong style={{ display: 'block' }}>{t('profile.settings.simpleMode')}</Text>
                        <Text type="secondary" style={{ fontSize: 'var(--text-xs)' }}>
                          {t('profile.settings.simpleModeHint')}
                        </Text>
                      </div>
                      <Switch
                        checked={uiPrefs.simpleMode}
                        onChange={(checked) => uiPrefStore.setSimpleMode(checked)}
                      />
                    </div>
                  </Space>
                </Card>

                {/* Sprint 10 (new feature) — auto-claim toggle. Pure-frontend
                    MVP; the watcher hook on PositionsPage fires fees.claimFees()
                    on every refresh for positions over threshold. */}
                <AutoClaimSettings />

                {/* Sprint 9-DS-r4 P2-15 — theme picker. */}
                <ThemeToggle />
              </Space>
            ),
          },
        ]}
      />
      </Col>
      </Row>
    </Space>
  )
}
