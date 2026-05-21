import { Row, Col, Card, Typography, Space, Form, Input, Button, Descriptions, Avatar, Alert, Divider, message, Tag } from 'antd'
import {
  UserOutlined,
  SaveOutlined,
  WalletOutlined,
  PieChartOutlined,
  ThunderboltFilled,
  HistoryOutlined,
} from '@ant-design/icons'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { useEffect, useMemo } from 'react'
import { users, balances, pools as poolsApi, fees, transactions as txApi } from '@/api/services'
import { authStore } from '@/store/authStore'
import KycStatusBadge from '@/components/KycStatusBadge'
import SelfRestrictionPanel from '@/components/SelfRestrictionPanel'
import ThemeToggle from '@/components/ThemeToggle'
import KycUploadPanel from '@/components/KycUploadPanel'
import { formatRub } from '@/components/StatCard'
import type { User, TokenBalance, Position, Transaction } from '@/api/types'
import dayjs from 'dayjs'
import relativeTime from 'dayjs/plugin/relativeTime'
import 'dayjs/locale/ru'

dayjs.extend(relativeTime)
dayjs.locale('ru')

const { Title, Text } = Typography

// Sprint 9 — same labels as the rest of the user-ui tx tables, kept
// inline here so the activity feed renders without pulling in a
// shared module just for two consts.
const txTypeLabel: Record<string, string> = {
  SWAP: 'Обмен',
  ADD_LIQUIDITY: 'Добавление',
  REMOVE_LIQUIDITY: 'Удаление',
  CLAIM_FEE: 'Комиссии',
  TRANSFER: 'Перевод',
  MINT: 'Выпуск',
  BURN: 'Сжигание',
}

export default function ProfilePage() {
  const queryClient = useQueryClient()
  const [form] = Form.useForm()

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
      message.success('Профиль обновлён')
      queryClient.invalidateQueries({ queryKey: ['me'] })
    },
    onError: (err: any) => {
      message.error(err?.response?.data?.message || 'Ошибка обновления профиля')
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
      <Title level={4} className="sber-page-title">Профиль</Title>

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
            style={{ backgroundColor: '#21A038', fontSize: 28 }}
          />
          <div>
            <Text strong style={{ fontSize: 18, display: 'block' }}>
              {user?.fullName || `${user?.firstName || ''} ${user?.lastName || ''}`}
            </Text>
            <Text type="secondary">{user?.email}</Text>
          </div>
        </div>

        <div style={{ marginBottom: 16 }}>
          <Text type="secondary" style={{ display: 'block', marginBottom: 8 }}>Статус верификации (KYC)</Text>
          <KycStatusBadge status={kycStatus} large />
        </div>

        {kycStatus === 'PENDING' && (
          <Alert
            message="Ваша заявка на верификацию находится на рассмотрении"
            description="Обычно проверка занимает 1-2 рабочих дня. После верификации вам будут доступны все функции платформы."
            type="info"
            showIcon
            style={{ borderRadius: 8 }}
          />
        )}
        {kycStatus === 'NOT_SUBMITTED' && (
          <Alert
            message="Верификация не пройдена"
            description="Для доступа к торговле и управлению ликвидностью необходимо пройти KYC верификацию."
            type="warning"
            showIcon
            style={{ borderRadius: 8 }}
          />
        )}
        {kycStatus === 'REJECTED' && (
          <Alert
            message="Верификация отклонена"
            description="Ваша заявка на верификацию была отклонена. Пожалуйста, свяжитесь с поддержкой."
            type="error"
            showIcon
            style={{ borderRadius: 8 }}
          />
        )}
      </Card>

      {/* Sprint 9-DS-r4 P2-8 — KYC document upload (Sber ID stub).
          Renders the Dragger only when the user is in NOT_SUBMITTED or
          REJECTED; for PENDING/VERIFIED users it shows an info card
          and bails out. */}
      <KycUploadPanel kycStatus={kycStatus as 'NOT_SUBMITTED' | 'PENDING' | 'VERIFIED' | 'REJECTED'} />

      {/* Sprint 6 #6.7 — 115-ФЗ самозапрет */}
      <SelfRestrictionPanel />

      {/* Edit form */}
      <Card className="sber-card" title={<Text strong>Редактирование профиля</Text>}>
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
            label={<span style={{ fontWeight: 500, color: '#374151' }}>Имя</span>}
            rules={[{ required: true, message: 'Введите имя' }]}
          >
            <Input placeholder="Иван" style={{ height: 44, borderRadius: 8 }} />
          </Form.Item>

          <Form.Item
            name="lastName"
            label={<span style={{ fontWeight: 500, color: '#374151' }}>Фамилия</span>}
            rules={[{ required: true, message: 'Введите фамилию' }]}
          >
            <Input placeholder="Иванов" style={{ height: 44, borderRadius: 8 }} />
          </Form.Item>

          <Form.Item
            label={<span style={{ fontWeight: 500, color: '#374151' }}>Электронная почта</span>}
          >
            <Input value={user?.email} disabled style={{ height: 44, borderRadius: 8 }} />
          </Form.Item>

          <Button
            type="primary"
            htmlType="submit"
            icon={<SaveOutlined />}
            loading={updateMutation.isPending}
            style={{ height: 44, borderRadius: 8 }}
          >
            Сохранить
          </Button>
        </Form>
      </Card>

      {/* Account info */}
      <Card className="sber-card" title={<Text strong>Информация об аккаунте</Text>}>
        <Descriptions bordered column={1} size="small">
          <Descriptions.Item label="ID пользователя">
            <Text copyable={{ text: user?.id }}>{user?.id}</Text>
          </Descriptions.Item>
          <Descriptions.Item label="Роль">{user?.role || '—'}</Descriptions.Item>
          <Descriptions.Item label="Дата регистрации">
            {user?.createdAt ? dayjs(user.createdAt).format('DD.MM.YYYY HH:mm') : '—'}
          </Descriptions.Item>
        </Descriptions>
      </Card>

      </Space>
      </Col>

      {/* Sprint 9 — right-rail. Previously the profile page stopped at the
          640px maxWidth and the whole right half was empty white space.
          Now: account snapshot + recent activity, so the page reads as
          "your account" instead of "your settings form". */}
      <Col xs={24} lg={10}>
      <Space direction="vertical" size={24} style={{ width: '100%' }}>

        <Card
          className="sber-card"
          title={<Text strong>Сводка по аккаунту</Text>}
          styles={{ body: { padding: 0 } }}
        >
          <div style={{ padding: '14px 18px', borderBottom: '1px solid var(--border-light)' }}>
            <Space size={10}>
              <div style={{
                width: 36, height: 36, borderRadius: 10,
                background: 'var(--sber-green-light)',
                display: 'flex', alignItems: 'center', justifyContent: 'center',
              }}>
                <WalletOutlined style={{ color: 'var(--sber-green)', fontSize: 18 }} />
              </div>
              <div>
                <Text type="secondary" style={{ fontSize: 12, display: 'block' }}>
                  Стоимость портфеля
                </Text>
                <Text strong style={{ fontSize: 22, fontVariantNumeric: 'tabular-nums' }}>
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
              <Text type="secondary" style={{ fontSize: 11 }}>Активов в кошельке</Text>
              <div style={{ fontSize: 18, fontWeight: 600, fontVariantNumeric: 'tabular-nums' }}>
                {tokensHeld}
              </div>
            </div>
            <div style={{ padding: 14 }}>
              <Text type="secondary" style={{ fontSize: 11 }}>Активных позиций</Text>
              <div style={{ fontSize: 18, fontWeight: 600, color: 'var(--sber-green)', fontVariantNumeric: 'tabular-nums' }}>
                {activePositionCount}
              </div>
            </div>
          </div>

          <div style={{ padding: 14 }}>
            <Text type="secondary" style={{ fontSize: 11 }}>Заработано на ликвидности</Text>
            <div style={{ fontSize: 16, fontWeight: 600, color: 'var(--sber-green)', fontVariantNumeric: 'tabular-nums' }}>
              {formatRub(totalEarned)}
            </div>
            {feeSummary && feeSummary.totalUnclaimed > 0 && (
              <Tag color="green" style={{ marginTop: 6, borderRadius: 999 }}>
                <ThunderboltFilled style={{ fontSize: 10, marginRight: 4 }} />
                {formatRub(feeSummary.totalUnclaimed)} к получению
              </Tag>
            )}
          </div>
        </Card>

        <Card
          className="sber-card"
          title={
            <Space>
              <HistoryOutlined style={{ color: 'var(--text-secondary)' }} />
              <Text strong>Последняя активность</Text>
            </Space>
          }
          styles={{ body: { padding: 0 } }}
        >
          {!recentTx?.content?.length ? (
            <div style={{ padding: 18 }}>
              <Text type="secondary">Активности пока нет</Text>
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
                    <div style={{ fontSize: 13, fontWeight: 500 }}>
                      {txTypeLabel[tx.txType] ?? tx.txType}
                    </div>
                    <div style={{
                      fontSize: 11,
                      color: 'var(--text-secondary)',
                      fontFamily: 'JetBrains Mono, monospace',
                    }}>
                      {dayjs(tx.createdAt).fromNow()}
                    </div>
                  </div>
                  <Tag
                    color={tx.status === 'CONFIRMED' ? 'success' : tx.status === 'FAILED' ? 'error' : 'processing'}
                    style={{ borderRadius: 999, padding: '0 10px', marginInlineEnd: 0 }}
                  >
                    {tx.status === 'CONFIRMED' ? 'Исполнена' : tx.status === 'FAILED' ? 'Ошибка' : tx.status}
                  </Tag>
                </div>
              ))}
            </Space>
          )}
        </Card>

        {/* Sprint 9-DS-r4 P2-15 — theme picker. Lives in the right rail
            so the user can find it without hunting through nav menus;
            the toggle uses themeStore (localStorage) and applies via
            <html data-theme="dark"> instantly. */}
        <ThemeToggle />

      </Space>
      </Col>
      </Row>
    </Space>
  )
}
