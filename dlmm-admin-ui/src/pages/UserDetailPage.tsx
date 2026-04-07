import { useState } from 'react'
import { useParams, useNavigate } from 'react-router-dom'
import {
  Card,
  Descriptions,
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
  Divider,
  Row,
  Col,
} from 'antd'
import {
  ArrowLeftOutlined,
  StopOutlined,
  CheckCircleOutlined,
} from '@ant-design/icons'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import type { ColumnsType } from 'antd/es/table'
import { users as userService } from '@/api/services'
import type { User, Transaction, KycStatus, UserRole, TxType, TxStatus } from '@/api/types'
import dayjs from 'dayjs'

const { Title } = Typography

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

const txStatusColor: Record<TxStatus, string> = {
  PENDING: 'orange',
  CONFIRMED: 'green',
  FAILED: 'red',
  CANCELLED: 'default',
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

  const txColumns: ColumnsType<Transaction> = [
    {
      title: 'Тип',
      dataIndex: 'txType',
      key: 'txType',
      render: (type: TxType) => <Tag color="blue">{type}</Tag>,
    },
    {
      title: 'Статус',
      dataIndex: 'status',
      key: 'status',
      render: (status: TxStatus) => (
        <Tag color={txStatusColor[status]}>{status}</Tag>
      ),
    },
    {
      title: 'Сумма входа',
      dataIndex: 'amountIn',
      key: 'amountIn',
      render: (val: number | null) => (val !== null ? val.toLocaleString() : '—'),
    },
    {
      title: 'Сумма выхода',
      dataIndex: 'amountOut',
      key: 'amountOut',
      render: (val: number | null) => (val !== null ? val.toLocaleString() : '—'),
    },
    {
      title: 'Комиссия',
      dataIndex: 'feeAmount',
      key: 'feeAmount',
      render: (val: number | null) => (val !== null ? val.toLocaleString() : '—'),
    },
    {
      title: 'Дата',
      dataIndex: 'createdAt',
      key: 'createdAt',
      render: (date: string) => dayjs(date).format('YYYY-MM-DD HH:mm'),
    },
  ]

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

  return (
    <>
      {contextHolder}
      <Space direction="vertical" size={16} style={{ width: '100%' }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
          <Button icon={<ArrowLeftOutlined />} onClick={() => navigate('/users')} style={{ borderRadius: 8 }}>
            Назад
          </Button>
          <Title level={4} className="sber-page-title">
            Пользователь: {user.email}
          </Title>
        </div>

        <Row gutter={[16, 16]}>
          <Col xs={24} lg={16}>
            <Card
              className="sber-card"
              title={<span style={{ fontWeight: 600 }}>Информация о пользователе</span>}
              style={{ borderRadius: 12, border: '1px solid #E5E7EB' }}
            >
              <Descriptions column={{ xs: 1, sm: 2 }} bordered size="small">
                <Descriptions.Item label="ID">{user.id}</Descriptions.Item>
                <Descriptions.Item label="Эл. почта">{user.email}</Descriptions.Item>
                <Descriptions.Item label="ФИО">{user.fullName || '—'}</Descriptions.Item>
                <Descriptions.Item label="Роль">
                  <Tag color="purple">{user.role}</Tag>
                </Descriptions.Item>
                <Descriptions.Item label="Статус KYC">
                  <Tag color={kycStatusColor[user.kycStatus]}>{user.kycStatus}</Tag>
                </Descriptions.Item>
                <Descriptions.Item label="Статус аккаунта">
                  <Tag color={user.blocked ? 'red' : 'green'}>
                    {user.blocked ? 'Заблокирован' : 'Активен'}
                  </Tag>
                </Descriptions.Item>
                <Descriptions.Item label="Дата создания">
                  {dayjs(user.createdAt).format('YYYY-MM-DD HH:mm:ss')}
                </Descriptions.Item>
              </Descriptions>
            </Card>
          </Col>

          <Col xs={24} lg={8}>
            <Space direction="vertical" size={12} style={{ width: '100%' }}>
              <Card
                className="sber-card"
                title={<span style={{ fontWeight: 600 }}>Управление KYC</span>}
                style={{ borderRadius: 12, border: '1px solid #E5E7EB' }}
              >
                <Space direction="vertical" style={{ width: '100%' }}>
                  <Select<KycStatus>
                    placeholder="Выберите статус KYC"
                    value={selectedKyc}
                    onChange={setSelectedKyc}
                    style={{ width: '100%' }}
                    options={kycStatusOptions}
                  />
                  <Button
                    type="primary"
                    block
                    disabled={!selectedKyc}
                    loading={kycMutation.isPending}
                    onClick={() => selectedKyc && kycMutation.mutate(selectedKyc)}
                    icon={<CheckCircleOutlined />}
                    style={{ borderRadius: 8 }}
                  >
                    Обновить статус KYC
                  </Button>
                </Space>
              </Card>

              <Card
                className="sber-card"
                title={<span style={{ fontWeight: 600 }}>Управление ролями</span>}
                style={{ borderRadius: 12, border: '1px solid #E5E7EB' }}
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
                    disabled={!selectedRole}
                    loading={roleMutation.isPending}
                    onClick={() => selectedRole && roleMutation.mutate(selectedRole)}
                    style={{ borderRadius: 8 }}
                  >
                    Обновить роль
                  </Button>
                </Space>
              </Card>

              <Card
                className="sber-card"
                title={<span style={{ fontWeight: 600 }}>Действия с аккаунтом</span>}
                style={{ borderRadius: 12, border: '1px solid #E5E7EB' }}
              >
                <Popconfirm
                  title={user.blocked ? 'Разблокировать пользователя?' : 'Заблокировать пользователя?'}
                  description={
                    user.blocked
                      ? 'Пользователь снова получит доступ к платформе.'
                      : 'Пользователь потеряет доступ к платформе.'
                  }
                  onConfirm={() => blockMutation.mutate()}
                  okText="Да"
                  cancelText="Нет"
                  okButtonProps={{ danger: !user.blocked }}
                >
                  <Button
                    danger={!user.blocked}
                    type={user.blocked ? 'primary' : 'default'}
                    block
                    loading={blockMutation.isPending}
                    icon={<StopOutlined />}
                    style={{ borderRadius: 8 }}
                  >
                    {user.blocked ? 'Разблокировать' : 'Заблокировать'}
                  </Button>
                </Popconfirm>
              </Card>
            </Space>
          </Col>
        </Row>

        <Divider />

        <Card
          className="sber-card sber-table"
          title={<span style={{ fontWeight: 600 }}>История транзакций</span>}
          style={{ borderRadius: 12, border: '1px solid #E5E7EB' }}
        >
          <Table<Transaction>
            columns={txColumns}
            dataSource={txData?.content}
            rowKey="id"
            loading={txLoading}
            pagination={{
              current: txPage + 1,
              pageSize: 10,
              total: txData?.totalElements,
              onChange: (p) => setTxPage(p - 1),
              showTotal: (total) => `Всего ${total} транзакций`,
            }}
            size="small"
          />
        </Card>
      </Space>
    </>
  )
}
