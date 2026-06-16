import { useMemo, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import {
  Table,
  Input,
  Space,
  Tag,
  Typography,
  Card,
  Button,
  Select,
  TablePaginationConfig,
} from 'antd'
import {
  SearchOutlined,
  UserOutlined,
  FilterOutlined,
  TeamOutlined,
  SafetyCertificateOutlined,
  StopOutlined,
  ClockCircleOutlined,
  DownloadOutlined,
} from '@ant-design/icons'
import { useQuery } from '@tanstack/react-query'
import type { ColumnsType } from 'antd/es/table'
import { users as userService } from '@/api/services'
import { exportToCsv } from '@/lib/csvExport'
import type { User, KycStatus, UserRole } from '@/api/types'
import dayjs from 'dayjs'
import { KpiRow, PageHeader } from '@/components/sber'

const { Text } = Typography
const { Search } = Input

const kycStatusColor: Record<KycStatus, string> = {
  PENDING: 'orange',
  VERIFIED: 'green',
  REJECTED: 'red',
  NOT_SUBMITTED: 'default',
}

// Sprint 9 — localised KYC labels. The table was rendering the raw enum
// ("VERIFIED" / "PENDING") which is fine for engineers but jars next to
// the rest of the Russian copy. Hyphen-replace was the previous bandaid.
const kycStatusLabel: Record<KycStatus, string> = {
  PENDING: 'На проверке',
  VERIFIED: 'Верифицирован',
  REJECTED: 'Отклонён',
  NOT_SUBMITTED: 'Не пройдена',
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
  SUPER_ADMIN: 'Супер-админ',
}

export default function UsersPage() {
  const navigate = useNavigate()
  const [page, setPage] = useState(0)
  const [pageSize, setPageSize] = useState(20)
  const [emailSearch, setEmailSearch] = useState('')
  const [searchInput, setSearchInput] = useState('')
  // Sprint 9 — client-side KYC + role filters. Admin only manages
  // ~handful of users in dev, hundreds in prod; client filter is fine.
  const [kycFilter, setKycFilter] = useState<KycStatus | 'ALL'>('ALL')
  const [roleFilter, setRoleFilter] = useState<UserRole | 'ALL'>('ALL')

  const { data, isLoading } = useQuery({
    queryKey: ['users', page, pageSize, emailSearch],
    queryFn: () => userService.getUsers(page, pageSize, emailSearch || undefined),
  })

  const filteredUsers = useMemo(() => {
    const list = data?.content ?? []
    return list.filter((u) => {
      if (kycFilter !== 'ALL' && u.kycStatus !== kycFilter) return false
      if (roleFilter !== 'ALL' && u.role !== roleFilter) return false
      return true
    })
  }, [data, kycFilter, roleFilter])

  // Sprint 9 — summary chips so the operator sees the KYC funnel at a
  // glance without having to scan the table. Counted over the current
  // backend page (~20 rows), not globally — good enough for the dev
  // workload, swap for a /admin/users/summary endpoint if the user base
  // grows past the first page.
  const summary = useMemo(() => {
    const list = data?.content ?? []
    const s = { total: list.length, verified: 0, pending: 0, blocked: 0 }
    for (const u of list) {
      if (u.kycStatus === 'VERIFIED') s.verified += 1
      if (u.kycStatus === 'PENDING') s.pending += 1
      if (u.blocked) s.blocked += 1
    }
    return s
  }, [data])

  const columns: ColumnsType<User> = [
    {
      title: 'Эл. почта',
      dataIndex: 'email',
      key: 'email',
      render: (email: string) => (
        <Space>
          <UserOutlined style={{ color: '#21A038' }} />
          <span>{email}</span>
        </Space>
      ),
    },
    // ФИО restored: the listing DOESN'T send fullName, but it DOES send
    // firstName + lastName — composed into fullName at the API boundary
    // (users.ts). The column was previously dropped on the wrong assumption
    // that names weren't available, so the admin saw only emails.
    {
      title: 'ФИО',
      dataIndex: 'fullName',
      key: 'fullName',
      render: (fullName: string | undefined) =>
        fullName ? <span>{fullName}</span> : <span style={{ color: 'var(--text-muted)' }}>—</span>,
    },
    {
      title: 'Роль',
      dataIndex: 'role',
      key: 'role',
      width: 160,
      render: (role: UserRole) => (
        <Tag color={roleColor[role] || 'blue'}>{roleLabel[role] || role}</Tag>
      ),
    },
    {
      title: 'Статус KYC',
      dataIndex: 'kycStatus',
      key: 'kycStatus',
      width: 160,
      render: (status: KycStatus) => (
        <Tag color={kycStatusColor[status] || 'default'}>
          {kycStatusLabel[status] || status}
        </Tag>
      ),
    },
    {
      title: 'Статус',
      dataIndex: 'blocked',
      key: 'blocked',
      width: 140,
      render: (blocked: boolean) => (
        <Tag color={blocked ? 'red' : 'green'}>{blocked ? 'Заблокирован' : 'Активен'}</Tag>
      ),
    },
    {
      title: 'Дата создания',
      dataIndex: 'createdAt',
      key: 'createdAt',
      width: 160,
      render: (date: string) => (
        <span style={{ fontFamily: 'JetBrains Mono, monospace', fontSize: 12 }}>
          {dayjs(date).format('DD.MM.YYYY HH:mm')}
        </span>
      ),
      sorter: (a, b) => dayjs(a.createdAt).unix() - dayjs(b.createdAt).unix(),
    },
    {
      // Batch #6 unit 3 — last_login_at column, populated by login flow
      // (Batch #6 unit 1) + initial seed backfill (07-seed-fix-backend-bugs).
      title: 'Последний вход',
      dataIndex: 'lastLoginAt',
      key: 'lastLoginAt',
      width: 160,
      render: (date: string | null) => date ? (
        <span style={{ fontFamily: 'JetBrains Mono, monospace', fontSize: 12 }}>
          {dayjs(date).format('DD.MM.YYYY HH:mm')}
        </span>
      ) : (
        <span style={{ color: 'var(--text-muted)', fontSize: 12 }}>—</span>
      ),
      sorter: (a: any, b: any) => {
        const ax = a.lastLoginAt ? dayjs(a.lastLoginAt).unix() : 0
        const bx = b.lastLoginAt ? dayjs(b.lastLoginAt).unix() : 0
        return ax - bx
      },
    },
  ]

  const handleTableChange = (pagination: TablePaginationConfig) => {
    setPage((pagination.current ?? 1) - 1)
    setPageSize(pagination.pageSize ?? 20)
  }

  const handleSearch = (value: string) => {
    setEmailSearch(value)
    setPage(0)
  }

  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      <PageHeader
        title="Пользователи"
        subtitle="Управление учётными записями платформы, KYC-статусы, права доступа"
        actions={
          <Search
            placeholder="Поиск по email…"
            value={searchInput}
            onChange={(e) => setSearchInput(e.target.value)}
            onSearch={handleSearch}
            allowClear
            style={{ width: 280 }}
            prefix={<SearchOutlined />}
            enterButton={<Button type="primary" icon={<SearchOutlined />}>Найти</Button>}
          />
        }
      />

      {/* Sprint 9-DS — replaced the inline chip strip with proper KPI tiles.
          Same numbers, but consistent with Pools / Transactions / OTC. */}
      <KpiRow
        tiles={[
          {
            label: 'Всего пользователей',
            value: summary.total.toLocaleString('ru-RU'),
            sub: 'на текущей странице',
            icon: <TeamOutlined style={{ color: '#296AE3' }} />,
          },
          {
            label: 'Верифицировано',
            value: summary.verified.toLocaleString('ru-RU'),
            sub: 'KYC пройден',
            icon: <SafetyCertificateOutlined style={{ color: 'var(--sber-green)' }} />,
            accent: 'var(--sber-green)',
          },
          {
            label: 'На проверке',
            value: summary.pending.toLocaleString('ru-RU'),
            sub: summary.pending === 0 ? 'нет ожидающих' : 'требуют разбора',
            icon: <ClockCircleOutlined style={{ color: '#D97706' }} />,
            accent: summary.pending > 0 ? '#D97706' : undefined,
          },
          {
            label: 'Заблокировано',
            value: summary.blocked.toLocaleString('ru-RU'),
            sub: summary.blocked === 0 ? 'все активны' : 'без доступа',
            icon: <StopOutlined style={{ color: summary.blocked > 0 ? '#DC2626' : 'var(--text-muted)' }} />,
            accent: summary.blocked > 0 ? '#DC2626' : undefined,
          },
        ]}
      />

      <Card
        className="sber-card sber-table"
        style={{ borderRadius: 12, border: '1px solid #E5E7EB' }}
        title={
          <Space wrap size={12}>
            <FilterOutlined style={{ color: '#6B7280' }} />
            <Text strong style={{ fontSize: 13 }}>KYC:</Text>
            <Select<KycStatus | 'ALL'>
              size="middle"
              style={{ minWidth: 170 }}
              value={kycFilter}
              onChange={(v) => { setKycFilter(v); setPage(0) }}
              options={[
                { value: 'ALL', label: 'Все' },
                { value: 'VERIFIED', label: kycStatusLabel.VERIFIED },
                { value: 'PENDING', label: kycStatusLabel.PENDING },
                { value: 'REJECTED', label: kycStatusLabel.REJECTED },
                { value: 'NOT_SUBMITTED', label: kycStatusLabel.NOT_SUBMITTED },
              ]}
            />
            <Text strong style={{ fontSize: 13, marginLeft: 8 }}>Роль:</Text>
            <Select<UserRole | 'ALL'>
              size="middle"
              style={{ minWidth: 160 }}
              value={roleFilter}
              onChange={(v) => { setRoleFilter(v); setPage(0) }}
              options={[
                { value: 'ALL', label: 'Все' },
                { value: 'USER', label: roleLabel.USER },
                { value: 'OPERATOR', label: roleLabel.OPERATOR },
                { value: 'ADMIN', label: roleLabel.ADMIN },
                { value: 'SUPER_ADMIN', label: roleLabel.SUPER_ADMIN },
              ]}
            />
            <Text type="secondary" style={{ fontSize: 12 }}>
              {filteredUsers.length}{data?.totalElements ? ` из ${data.totalElements}` : ''}
            </Text>
            {/* QW-1 (Batch #4) — CSV export of current filtered page.
                Compliance use case: regulator asks "show all PENDING KYC
                users at date X" — filter + download in 5 seconds. */}
            <Button
              size="small"
              icon={<DownloadOutlined />}
              onClick={() => {
                if (filteredUsers.length === 0) return
                const stamp = new Date().toISOString().slice(0, 10)
                // Columns typed against User so a wrong field name is a compile
                // error — the previous spec was cast to `any` and referenced
                // isBlocked (never on the payload → always "false") while ФИО
                // relied on a fullName the API never sends. Now: real fields,
                // localized role/KYC to match the on-screen table. The blocked
                // column is intentionally dropped — the API doesn't expose it,
                // so exporting "Активен" for everyone is misleading.
                exportToCsv<User>(
                  `dlmm-users-${stamp}.csv`,
                  filteredUsers,
                  [
                    { header: 'ID', accessor: (u) => u.id },
                    { header: 'Email', accessor: (u) => u.email },
                    { header: 'ФИО', accessor: (u) => u.fullName ?? '' },
                    { header: 'Sber ID', accessor: (u) => u.sberId ?? '' },
                    { header: 'Телефон', accessor: (u) => u.phone ?? '' },
                    { header: 'Роль', accessor: (u) => roleLabel[u.role] ?? u.role },
                    { header: 'KYC статус', accessor: (u) => kycStatusLabel[u.kycStatus] ?? u.kycStatus },
                    { header: 'Создан', accessor: (u) => u.createdAt },
                    { header: 'Последний вход', accessor: (u) => u.lastLoginAt ?? '' },
                  ],
                )
              }}
              disabled={filteredUsers.length === 0}
              style={{ marginLeft: 'auto' }}
            >
              CSV
            </Button>
          </Space>
        }
      >
        <Table<User>
          columns={columns}
          dataSource={filteredUsers}
          rowKey="id"
          loading={isLoading}
          pagination={{
            current: page + 1,
            pageSize,
            total: data?.totalElements,
            showSizeChanger: true,
            showQuickJumper: true,
            showTotal: (total) => `Всего ${total} пользователей`,
            pageSizeOptions: ['10', '20', '50', '100'],
          }}
          onChange={handleTableChange}
          onRow={(record) => ({
            onClick: () => navigate(`/users/${record.id}`),
            style: { cursor: 'pointer' },
          })}
          rowClassName="hoverable-row"
          size="middle"
        />
      </Card>
    </Space>
  )
}
