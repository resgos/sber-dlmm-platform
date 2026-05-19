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
import { SearchOutlined, UserOutlined, FilterOutlined } from '@ant-design/icons'
import { useQuery } from '@tanstack/react-query'
import type { ColumnsType } from 'antd/es/table'
import { users as userService } from '@/api/services'
import type { User, KycStatus, UserRole } from '@/api/types'
import dayjs from 'dayjs'

const { Title, Text } = Typography
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
    // Sprint 9 — dropped the ФИО column. The admin-bff user listing
    // doesn't include fullName, so every row rendered "—" and the
    // column was visual dead weight. Names live on the user detail
    // page where they're actually populated.
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
      width: 180,
      render: (date: string) => (
        <span style={{ fontFamily: 'JetBrains Mono, monospace', fontSize: 12 }}>
          {dayjs(date).format('DD.MM.YYYY HH:mm')}
        </span>
      ),
      sorter: (a, b) => dayjs(a.createdAt).unix() - dayjs(b.createdAt).unix(),
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
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <Title level={4} className="sber-page-title">
          Пользователи
        </Title>
        <Space>
          <Search
            placeholder="Поиск по email..."
            value={searchInput}
            onChange={(e) => setSearchInput(e.target.value)}
            onSearch={handleSearch}
            allowClear
            style={{ width: 280 }}
            prefix={<SearchOutlined />}
            enterButton={<Button type="primary" icon={<SearchOutlined />}>Найти</Button>}
          />
        </Space>
      </div>

      {/* Sprint 9 — KYC funnel summary chips, before the filter row. */}
      <Space size={20} wrap style={{ padding: '4px 4px 8px' }}>
        <Text type="secondary" style={{ fontSize: 12 }}>
          Всего: <Text strong style={{ fontSize: 13 }}>{summary.total}</Text>
        </Text>
        <Text type="secondary" style={{ fontSize: 12 }}>
          Верифицировано:{' '}
          <Text strong style={{ fontSize: 13, color: '#21A038' }}>{summary.verified}</Text>
        </Text>
        <Text type="secondary" style={{ fontSize: 12 }}>
          На проверке:{' '}
          <Text strong style={{ fontSize: 13, color: '#D97706' }}>{summary.pending}</Text>
        </Text>
        <Text type="secondary" style={{ fontSize: 12 }}>
          Заблокировано:{' '}
          <Text strong style={{ fontSize: 13, color: summary.blocked > 0 ? '#DC2626' : undefined }}>
            {summary.blocked}
          </Text>
        </Text>
      </Space>

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
