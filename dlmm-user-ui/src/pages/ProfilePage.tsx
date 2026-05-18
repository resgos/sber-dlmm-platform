import { Card, Typography, Space, Form, Input, Button, Descriptions, Avatar, Alert, Divider, message } from 'antd'
import { UserOutlined, SaveOutlined } from '@ant-design/icons'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { useEffect } from 'react'
import { users } from '@/api/services'
import { authStore } from '@/store/authStore'
import KycStatusBadge from '@/components/KycStatusBadge'
import SelfRestrictionPanel from '@/components/SelfRestrictionPanel'
import type { User } from '@/api/types'
import dayjs from 'dayjs'
import relativeTime from 'dayjs/plugin/relativeTime'
import 'dayjs/locale/ru'

dayjs.extend(relativeTime)
dayjs.locale('ru')

const { Title, Text } = Typography

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

  return (
    <Space direction="vertical" size={24} style={{ width: '100%', maxWidth: 640 }}>
      <Title level={4} className="sber-page-title">Профиль</Title>

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
  )
}
