import { useState, useMemo } from 'react'
import { useParams, useNavigate } from 'react-router-dom'
import ActivityLogPanel from '@/components/ActivityLogPanel'
import {
  Card,
  Tag,
  Select,
  Button,
  Space,
  Typography,
  Table,
  Popconfirm,
  message,
  Spin,
  Alert,
  Row,
  Col,
  Tooltip,
  Tabs,
} from 'antd'
import {
  ArrowLeftOutlined,
  StopOutlined,
  CheckCircleOutlined,
  ClockCircleOutlined,
  SafetyCertificateOutlined,
  RiseOutlined,
  CopyOutlined,
  IdcardOutlined,
  MailOutlined,
} from '@ant-design/icons'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import type { ColumnsType } from 'antd/es/table'
import { users as userService, tokens as tokensApi } from '@/api/services'
import type { User, Transaction, KycStatus, UserRole, TxType, TxStatus, Token } from '@/api/types'
import dayjs from 'dayjs'
import { KpiRow, TokenPairChip } from '@/components/sber'
import { formatTokenAmount, formatCompact } from '@/lib/format'

const { Text, Title } = Typography

/**
 * Sprint 9-DS — admin UserDetailPage refactor.
 *
 * <p>Was a flat two-column form: Descriptions on the left + stacked
 * action cards on the right + flat transactions table at the bottom.
 * New layout matches the rest of the refactored admin pages:
 *  - HeroCard at the top: avatar + display-name + email + status pills
 *    + copy-ID action, accent the role-specific colour
 *  - 4-up KpiRow under it: KYC stage / role / account age / tx volume
 *  - Tabbed body: Профиль | KYC & роли | История
 *  - Action cards live inside the "KYC & роли" tab so they don't
 *    compete with the read-only Profile tab
 */

const kycStatusOptions: { value: KycStatus; label: string }[] = [
  { value: 'NOT_SUBMITTED', label: 'Не подана' },
  { value: 'PENDING', label: 'На рассмотрении' },
  { value: 'VERIFIED', label: 'Верифицирован' },
  { value: 'REJECTED', label: 'Отклонена' },
]

const roleOptions: { value: UserRole; label: string }[] = [
  { value: 'USER', label: 'Пользователь' },
  { value: 'OPERATOR', label: 'Оператор' },
  { value: 'ADMIN', label: 'Администратор' },
  { value: 'SUPER_ADMIN', label: 'Суперадмин' },
]

const kycStatusColor: Record<KycStatus, string> = {
  PENDING: 'orange',
  VERIFIED: 'green',
  REJECTED: 'red',
  NOT_SUBMITTED: 'default',
}

const kycStatusLabel: Record<KycStatus, string> = {
  PENDING: 'На проверке',
  VERIFIED: 'Верифицирован',
  REJECTED: 'Отклонён',
  NOT_SUBMITTED: 'Не подана',
}

const roleColor: Record<UserRole, string> = {
  USER: 'blue',
  OPERATOR: 'cyan',
  ADMIN: 'purple',
  SUPER_ADMIN: 'red',
}

const roleLabel: Record<UserRole, string> = {
  USER: 'Пользователь',
  OPERATOR: 'Оператор',
  ADMIN: 'Администратор',
  SUPER_ADMIN: 'Суперадмин',
}

const txStatusColor: Record<TxStatus, string> = {
  PENDING: 'orange',
  CONFIRMED: 'green',
  FAILED: 'red',
  CANCELLED: 'default',
}

const txTypeLabel: Record<TxType, string> = {
  SWAP: 'Обмен',
  ADD_LIQUIDITY: 'Добавить ликвидность',
  REMOVE_LIQUIDITY: 'Снять ликвидность',
  MINT: 'Выпуск',
  BURN: 'Сжигание',
  TRANSFER: 'Перевод',
}

function initials(input: string): string {
  const local = input.includes('@') ? input.split('@')[0] : input
  const parts = local.split(/[ ._-]/).filter(Boolean)
  if (parts.length >= 2) return (parts[0][0] + parts[1][0]).toUpperCase()
  return local.slice(0, 2).toUpperCase()
}

function colourForKey(key: string): string {
  const palette = ['#21A038', '#296AE3', '#9B59B6', '#16A085', '#D14D00', '#E74C3C']
  let h = 0
  for (let i = 0; i < key.length; i++) h = (h * 31 + key.charCodeAt(i)) | 0
  return palette[Math.abs(h) % palette.length]
}

export default function UserDetailPage() {
  const { id } = useParams<{ id: string }>()
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const [messageApi, contextHolder] = message.useMessage()

  const [selectedKyc, setSelectedKyc] = useState<KycStatus | null>(null)
  const [selectedRole, setSelectedRole] = useState<UserRole | null>(null)
  const [txPage, setTxPage] = useState(0)

  const { data: user, isLoading, error } = useQuery({
    queryKey: ['user', id],
    queryFn: () => userService.getUser(id!),
    enabled: !!id,
  })

  const { data: txData, isLoading: txLoading } = useQuery({
    queryKey: ['userTransactions', id, txPage],
    queryFn: () => userService.getUserTransactions(id!, txPage, 10),
    enabled: !!id,
  })

  // Token catalogue for the pair column join (same pattern as TransactionsPage).
  const { data: tokenPage } = useQuery({
    queryKey: ['tokens'],
    queryFn: () => tokensApi.getTokens(0, 200),
  })
  const symbolByTokenId = useMemo(() => {
    const m = new Map<string, string>()
    for (const t of tokenPage?.content ?? []) m.set(t.id, t.symbol)
    return m
  }, [tokenPage])

  const kycMutation = useMutation({
    mutationFn: (status: KycStatus) => userService.updateKyc(id!, status),
    onSuccess: () => {
      messageApi.success('Статус KYC успешно обновлён')
      queryClient.invalidateQueries({ queryKey: ['user', id] })
      setSelectedKyc(null)
    },
    onError: () => messageApi.error('Не удалось обновить статус KYC'),
  })

  const roleMutation = useMutation({
    mutationFn: (role: UserRole) => userService.updateRole(id!, role),
    onSuccess: () => {
      messageApi.success('Роль пользователя успешно обновлена')
      queryClient.invalidateQueries({ queryKey: ['user', id] })
      setSelectedRole(null)
    },
    onError: () => messageApi.error('Не удалось обновить роль пользователя'),
  })

  const blockMutation = useMutation({
    mutationFn: () =>
      user?.blocked ? userService.unblockUser(id!) : userService.blockUser(id!),
    onSuccess: () => {
      messageApi.success(user?.blocked ? 'Пользователь разблокирован' : 'Пользователь заблокирован')
      queryClient.invalidateQueries({ queryKey: ['user', id] })
    },
    onError: () => messageApi.error('Не удалось обновить статус блокировки'),
  })

  const copyId = () => {
    if (!user?.id) return
    navigator.clipboard.writeText(user.id).then(
      () => messageApi.success('ID скопирован в буфер обмена'),
      () => messageApi.error('Не удалось скопировать'),
    )
  }

  if (isLoading) {
    return (
      <div style={{ textAlign: 'center', padding: '80px' }}>
        <Spin size="large" />
      </div>
    )
  }

  if (error || !user) {
    return (
      <Alert
        message="Не удалось загрузить пользователя"
        description="Пользователь не найден или произошла ошибка."
        type="error"
        showIcon
        style={{ borderRadius: 8 }}
        action={<Button onClick={() => navigate('/users')}>К пользователям</Button>}
      />
    )
  }

  // Stats over the loaded tx page (good enough for KPI; full counts via API).
  const allTxs: Transaction[] = txData?.content ?? []
  const swapCount = allTxs.filter((t) => t.txType === 'SWAP').length
  const totalSwapVolume = allTxs
    .filter((t) => t.txType === 'SWAP' && t.amountIn)
    .reduce((acc, t) => acc + (t.amountIn ?? 0), 0)
  const accountAgeDays = dayjs().diff(dayjs(user.createdAt), 'day')

  const accent = colourForKey(user.email ?? user.id)

  const txColumns: ColumnsType<Transaction> = [
    {
      title: 'Тип',
      dataIndex: 'txType',
      key: 'txType',
      width: 160,
      render: (type: TxType) => <Tag color="blue">{txTypeLabel[type] ?? type}</Tag>,
    },
    {
      title: 'Пара',
      key: 'pair',
      width: 180,
      render: (_, r) => (
        <TokenPairChip
          x={r.tokenInId ? symbolByTokenId.get(r.tokenInId) : undefined}
          y={r.tokenOutId ? symbolByTokenId.get(r.tokenOutId) : undefined}
        />
      ),
    },
    {
      title: 'Статус',
      dataIndex: 'status',
      key: 'status',
      width: 130,
      render: (status: TxStatus) => <Tag color={txStatusColor[status]}>{status}</Tag>,
    },
    {
      title: 'Сумма входа',
      dataIndex: 'amountIn',
      key: 'amountIn',
      align: 'right',
      render: (val: number | null, r: Transaction) => (
        <span style={{ fontVariantNumeric: 'tabular-nums' }}>
          {formatTokenAmount(val, r.tokenInId ? symbolByTokenId.get(r.tokenInId) : undefined)}
        </span>
      ),
    },
    {
      title: 'Сумма выхода',
      dataIndex: 'amountOut',
      key: 'amountOut',
      align: 'right',
      render: (val: number | null, r: Transaction) => (
        <span style={{ fontVariantNumeric: 'tabular-nums' }}>
          {formatTokenAmount(val, r.tokenOutId ? symbolByTokenId.get(r.tokenOutId) : undefined)}
        </span>
      ),
    },
    {
      title: 'Комиссия',
      dataIndex: 'feeAmount',
      key: 'feeAmount',
      align: 'right',
      render: (val: number | null, r: Transaction) => (
        <span style={{ fontVariantNumeric: 'tabular-nums' }}>
          {formatTokenAmount(val, r.tokenInId ? symbolByTokenId.get(r.tokenInId) : undefined, { maxFractionDigits: 6 })}
        </span>
      ),
    },
    {
      title: 'Дата',
      dataIndex: 'createdAt',
      key: 'createdAt',
      width: 150,
      render: (date: string) => (
        <span style={{ fontFamily: 'JetBrains Mono, monospace', fontSize: 12 }}>
          {dayjs(date).format('DD.MM.YYYY HH:mm')}
        </span>
      ),
    },
  ]

  return (
    <>
      {contextHolder}
      <Space direction="vertical" size={16} style={{ width: '100%' }}>
        {/* Back link + hero. Hero card replaces the inline title + email
            with avatar + name + email + status pills + copy-ID action. */}
        <div>
          <Button
            type="text"
            icon={<ArrowLeftOutlined />}
            onClick={() => navigate('/users')}
            style={{ padding: 0, marginBottom: 8, color: 'var(--text-secondary)' }}
          >
            К списку пользователей
          </Button>
        </div>

        <Card
          className="sber-card"
          style={{ borderRadius: 16, border: '1px solid var(--border-light)', overflow: 'hidden' }}
          styles={{ body: { padding: 0 } }}
        >
          <div
            style={{
              padding: '24px 28px',
              background: `linear-gradient(135deg, ${accent}14 0%, ${accent}05 100%)`,
              borderBottom: '1px solid var(--border-light)',
            }}
          >
            <Row align="middle" gutter={[24, 16]}>
              <Col flex="none">
                <div
                  aria-hidden
                  style={{
                    width: 72,
                    height: 72,
                    borderRadius: 16,
                    background: accent,
                    color: '#fff',
                    display: 'flex',
                    alignItems: 'center',
                    justifyContent: 'center',
                    fontSize: 26,
                    fontWeight: 700,
                    flexShrink: 0,
                    boxShadow: `0 4px 18px ${accent}40`,
                  }}
                >
                  {initials(user.email ?? user.id)}
                </div>
              </Col>
              <Col flex="auto" style={{ minWidth: 0 }}>
                <Space size={8} wrap>
                  <Title level={4} className="sber-page-title" style={{ margin: 0 }}>
                    {user.fullName || user.email}
                  </Title>
                  <Tag color={roleColor[user.role]} style={{ marginInlineEnd: 0 }}>
                    {roleLabel[user.role]}
                  </Tag>
                  <Tag color={kycStatusColor[user.kycStatus]} style={{ marginInlineEnd: 0 }}>
                    {kycStatusLabel[user.kycStatus]}
                  </Tag>
                  <Tag color={user.blocked ? 'red' : 'green'} style={{ marginInlineEnd: 0 }}>
                    {user.blocked ? 'Заблокирован' : 'Активен'}
                  </Tag>
                </Space>
                <div style={{ marginTop: 6 }}>
                  <Space size={16} wrap>
                    <Space size={4}>
                      <MailOutlined style={{ color: 'var(--text-muted)' }} />
                      <Text type="secondary" style={{ fontSize: 13 }}>{user.email}</Text>
                    </Space>
                    <Space size={4}>
                      <IdcardOutlined style={{ color: 'var(--text-muted)' }} />
                      <Tooltip title={user.id}>
                        <Text
                          style={{
                            fontSize: 12,
                            fontFamily: 'JetBrains Mono, monospace',
                            color: 'var(--text-secondary)',
                          }}
                        >
                          …{user.id.slice(-12)}
                        </Text>
                      </Tooltip>
                      <Button
                        type="text"
                        size="small"
                        icon={<CopyOutlined />}
                        onClick={copyId}
                        aria-label="Скопировать ID"
                        style={{ padding: '0 4px', color: 'var(--text-muted)' }}
                      />
                    </Space>
                  </Space>
                </div>
              </Col>
              <Col flex="none">
                <Popconfirm
                  title={user.blocked ? 'Разблокировать пользователя?' : 'Заблокировать пользователя?'}
                  description={
                    user.blocked
                      ? 'Пользователь снова получит доступ к платформе.'
                      : 'Пользователь потеряет доступ к платформе.'
                  }
                  onConfirm={() => blockMutation.mutate()}
                  okText="Да"
                  cancelText="Отмена"
                  okButtonProps={{ danger: !user.blocked }}
                >
                  <Button
                    danger={!user.blocked}
                    type={user.blocked ? 'primary' : 'default'}
                    loading={blockMutation.isPending}
                    icon={<StopOutlined />}
                    style={{ borderRadius: 8 }}
                  >
                    {user.blocked ? 'Разблокировать' : 'Заблокировать'}
                  </Button>
                </Popconfirm>
              </Col>
            </Row>
          </div>
        </Card>

        <KpiRow
          tiles={[
            {
              label: 'KYC статус',
              value: kycStatusLabel[user.kycStatus],
              sub: user.kycStatus === 'VERIFIED' ? 'верификация пройдена' : 'требует разбора',
              icon: <SafetyCertificateOutlined style={{ color: user.kycStatus === 'VERIFIED' ? 'var(--sber-green)' : '#D97706' }} />,
              accent: user.kycStatus === 'VERIFIED' ? 'var(--sber-green)' : '#D97706',
            },
            {
              label: 'Возраст аккаунта',
              value: `${accountAgeDays}`,
              sub: `${accountAgeDays === 1 ? 'день' : accountAgeDays < 5 ? 'дня' : 'дней'} назад`,
              icon: <ClockCircleOutlined style={{ color: '#296AE3' }} />,
            },
            {
              label: 'Свопов в истории',
              value: swapCount.toLocaleString('ru-RU'),
              sub: `всего ${allTxs.length} операций`,
              icon: <RiseOutlined style={{ color: '#9B59B6' }} />,
            },
            {
              label: 'Совокупный объём',
              value: formatCompact(totalSwapVolume),
              sub: 'по amountIn свопов',
              icon: <CheckCircleOutlined style={{ color: 'var(--sber-green)' }} />,
            },
          ]}
        />

        <Card
          className="sber-card"
          style={{ borderRadius: 12, border: '1px solid var(--border-light)' }}
          styles={{ body: { padding: '8px 16px 16px' } }}
        >
          <Tabs
            defaultActiveKey="profile"
            items={[
              {
                key: 'profile',
                label: 'Профиль',
                children: (
                  <Row gutter={[16, 16]}>
                    <Col xs={24} md={12}>
                      <ProfileRow label="Полное имя" value={user.fullName || '—'} />
                      <ProfileRow label="Эл. почта" value={user.email} mono />
                      <ProfileRow
                        label="ID пользователя"
                        value={user.id}
                        mono
                        small
                      />
                    </Col>
                    <Col xs={24} md={12}>
                      <ProfileRow
                        label="Создан"
                        value={dayjs(user.createdAt).format('DD.MM.YYYY HH:mm:ss')}
                        mono
                      />
                      <ProfileRow label="Роль" value={roleLabel[user.role]} />
                      <ProfileRow
                        label="Статус KYC"
                        value={kycStatusLabel[user.kycStatus]}
                      />
                    </Col>
                  </Row>
                ),
              },
              {
                key: 'manage',
                label: 'KYC и роли',
                children: (
                  <Row gutter={[16, 16]}>
                    <Col xs={24} md={12}>
                      <Card
                        size="small"
                        title={<Space><SafetyCertificateOutlined /> Управление KYC</Space>}
                        style={{ borderRadius: 12, border: '1px solid var(--border-light)' }}
                      >
                        <Space direction="vertical" style={{ width: '100%' }}>
                          <Select<KycStatus>
                            placeholder="Выберите новый статус KYC"
                            value={selectedKyc}
                            onChange={setSelectedKyc}
                            style={{ width: '100%' }}
                            options={kycStatusOptions}
                          />
                          <Button
                            type="primary"
                            block
                            disabled={!selectedKyc || selectedKyc === user.kycStatus}
                            loading={kycMutation.isPending}
                            onClick={() => selectedKyc && kycMutation.mutate(selectedKyc)}
                            icon={<CheckCircleOutlined />}
                            style={{ borderRadius: 8 }}
                          >
                            Обновить KYC
                          </Button>
                          <Text type="secondary" style={{ fontSize: 11 }}>
                            Текущий: {kycStatusLabel[user.kycStatus]}
                          </Text>
                        </Space>
                      </Card>
                    </Col>
                    <Col xs={24} md={12}>
                      <Card
                        size="small"
                        title={<Space><IdcardOutlined /> Управление ролью</Space>}
                        style={{ borderRadius: 12, border: '1px solid var(--border-light)' }}
                      >
                        <Space direction="vertical" style={{ width: '100%' }}>
                          <Select<UserRole>
                            placeholder="Выберите роль"
                            value={selectedRole}
                            onChange={setSelectedRole}
                            style={{ width: '100%' }}
                            options={roleOptions}
                          />
                          <Button
                            type="primary"
                            block
                            disabled={!selectedRole || selectedRole === user.role}
                            loading={roleMutation.isPending}
                            onClick={() => selectedRole && roleMutation.mutate(selectedRole)}
                            style={{ borderRadius: 8 }}
                          >
                            Обновить роль
                          </Button>
                          <Text type="secondary" style={{ fontSize: 11 }}>
                            Текущая: {roleLabel[user.role]}
                          </Text>
                        </Space>
                      </Card>
                    </Col>
                  </Row>
                ),
              },
              {
                key: 'history',
                label: `История (${txData?.totalElements ?? 0})`,
                children: (
                  <Table<Transaction>
                    columns={txColumns}
                    dataSource={allTxs}
                    rowKey="id"
                    loading={txLoading}
                    pagination={{
                      current: txPage + 1,
                      pageSize: 10,
                      total: txData?.totalElements,
                      onChange: (p) => setTxPage(p - 1),
                      showTotal: (total) => `Всего ${total} операций`,
                    }}
                    size="middle"
                    onRow={(record) => ({
                      onClick: () => navigate(`/transactions?id=${record.id}`),
                      style: { cursor: 'pointer' },
                    })}
                  />
                ),
              },
            ]}
          />
        </Card>
        {/* Batch #6 unit 2 — QW-5 ActivityLogPanel wired сюда. Shows
            audit_log entries targeting this user — compliance use case:
            "every action that touched user X". */}
        {id && <ActivityLogPanel targetType="USER" targetId={id} title="Журнал действий по пользователю" />}
      </Space>
    </>
  )
}

function ProfileRow({
  label,
  value,
  mono,
  small,
}: {
  label: string
  value: string
  mono?: boolean
  small?: boolean
}) {
  return (
    <div
      style={{
        display: 'flex',
        justifyContent: 'space-between',
        alignItems: 'baseline',
        padding: '10px 0',
        borderBottom: '1px solid var(--border-light)',
        gap: 12,
      }}
    >
      <Text type="secondary" style={{ fontSize: 12 }}>
        {label}
      </Text>
      <Text
        style={{
          fontSize: small ? 11 : 13,
          fontWeight: 500,
          textAlign: 'right',
          fontFamily: mono ? 'JetBrains Mono, monospace' : undefined,
          maxWidth: '70%',
          overflow: 'hidden',
          textOverflow: 'ellipsis',
        }}
      >
        {value}
      </Text>
    </div>
  )
}
