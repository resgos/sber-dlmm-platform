import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import {
  Table,
  Input,
  Space,
  Tag,
  Typography,
  Card,
  Button,
  TablePaginationConfig,
} from 'antd'
import { SearchOutlined, UserOutlined } from '@ant-design/icons'
import { useQuery } from '@tanstack/react-query'
import type { ColumnsType } from 'antd/es/table'
import { users as userService } from '@/api/services'
import type { User, KycStatus, UserRole } from '@/api/types'
import dayjs from 'dayjs'

const { Title } = Typography
const { Search } = Input

const kycStatusColor: Record<KycStatus, string> = {
  PENDING: 'orange',
  VERIFIED: 'green',
  REJECTED: 'red',
  NOT_SUBMITTED: 'default',
}

const roleColor: Record<UserRole, string> = {
  USER: 'blue',
  OPERATOR: 'cyan',
  ADMIN: 'purple',
  SUPER_ADMIN: 'red',
}

export default function UsersPage() {
  const navigate = useNavigate()
  const [page, setPage] = useState(0)
  const [pageSize, setPageSize] = useState(20)
  const [emailSearch, setEmailSearch] = useState('')
  const [searchInput, setSearchInput] = useState('')

  const { data, isLoading } = useQuery({
    queryKey: ['users', page, pageSize, emailSearch],
    queryFn: () => userService.getUsers(page, pageSize, emailSearch || undefined),
  })

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
    {
      title: 'ФИО',
      dataIndex: 'fullName',
      key: 'fullName',
      render: (name: string) => name || '—',
    },
    {
      title: 'Роль',
      dataIndex: 'role',
      key: 'role',
      render: (role: UserRole) => (
        <Tag color={roleColor[role] || 'blue'}>{role}</Tag>
      ),
    },
    {
      title: 'Статус KYC',
      dataIndex: 'kycStatus',
      key: 'kycStatus',
      render: (status: KycStatus) => (
        <Tag color={kycStatusColor[status] || 'default'}>{status.replace('_', ' ')}</Tag>
      ),
    },
    {
      title: 'Статус',
      dataIndex: 'blocked',
      key: 'blocked',
      render: (blocked: boolean) => (
        <Tag color={blocked ? 'red' : 'green'}>{blocked ? 'Заблокирован' : 'Активен'}</Tag>
      ),
    },
    {
      title: 'Дата создания',
      dataIndex: 'createdAt',
      key: 'createdAt',
      render: (date: string) => dayjs(date).format('YYYY-MM-DD HH:mm'),
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

      <Card className="sber-card sber-table" style={{ borderRadius: 12, border: '1px solid #E5E7EB' }}>
        <Table<User>
          columns={columns}
          dataSource={data?.content}
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
