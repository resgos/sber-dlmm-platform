import { useState } from 'react'
import { useNavigate, Link } from 'react-router-dom'
import { Form, Input, Button, Card, Typography, Alert, Space } from 'antd'
import { UserOutlined, LockOutlined, MailOutlined, IdcardOutlined, PhoneOutlined } from '@ant-design/icons'
import { auth } from '@/api/services'
import { authStore } from '@/store/authStore'

const { Title, Text } = Typography

function SberLogoLarge() {
  return (
    <svg width="56" height="56" viewBox="0 0 56 56" fill="none" xmlns="http://www.w3.org/2000/svg">
      <circle cx="28" cy="28" r="28" fill="#21A038" />
      <path d="M15 28L23 36L41 18" stroke="white" strokeWidth="4" strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  )
}

export default function RegisterPage() {
  const navigate = useNavigate()
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [form] = Form.useForm()

  const handleSubmit = async (values: {
    sberId: string
    phone: string
    firstName: string
    lastName: string
    email: string
    password: string
  }) => {
    setLoading(true)
    setError(null)
    try {
      const response = await auth.register({
        sberId: values.sberId,
        phone: values.phone,
        firstName: values.firstName,
        lastName: values.lastName,
        email: values.email,
        password: values.password,
      })
      authStore.setToken(response.accessToken)
      authStore.setUser({
        userId: response.user.id,
        email: response.user.email,
        role: response.user.role,
        kycStatus: response.user.kycStatus,
      })
      navigate('/', { replace: true })
    } catch (err: unknown) {
      const axiosError = err as { response?: { data?: { message?: string } } }
      setError(axiosError?.response?.data?.message || 'Ошибка регистрации. Попробуйте снова.')
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="sber-login-bg">
      <Card
        style={{ width: 460, borderRadius: 16, boxShadow: '0 4px 24px rgba(0,0,0,0.08)', border: '1px solid #E5E7EB' }}
        styles={{ body: { padding: '40px' } }}
      >
        <Space direction="vertical" size={8} style={{ width: '100%', marginBottom: 24, textAlign: 'center' }}>
          <div style={{ display: 'flex', justifyContent: 'center', marginBottom: 8 }}>
            <SberLogoLarge />
          </div>
          <Title level={3} style={{ margin: 0, color: '#1F2937', fontWeight: 700 }}>
            СБЕР <span style={{ color: '#21A038' }}>DLMM</span>
          </Title>
          <Text style={{ color: '#6B7280', fontSize: 14 }}>Регистрация</Text>
        </Space>

        {error && (
          <Alert
            message={error}
            type="error"
            showIcon
            closable
            onClose={() => setError(null)}
            style={{ marginBottom: 24, borderRadius: 8 }}
          />
        )}

        <Form form={form} layout="vertical" onFinish={handleSubmit} autoComplete="off" size="large">
          <Space style={{ width: '100%' }} size={12}>
            <Form.Item
              name="firstName"
              label={<span style={{ fontWeight: 500, color: '#374151' }}>Имя</span>}
              rules={[{ required: true, message: 'Введите имя' }, { min: 2, message: 'Минимум 2 символа' }]}
              style={{ flex: 1 }}
            >
              <Input
                prefix={<UserOutlined style={{ color: '#9CA3AF' }} />}
                placeholder="Иван"
                style={{ height: 44, borderRadius: 8 }}
              />
            </Form.Item>
            <Form.Item
              name="lastName"
              label={<span style={{ fontWeight: 500, color: '#374151' }}>Фамилия</span>}
              rules={[{ required: true, message: 'Введите фамилию' }, { min: 2, message: 'Минимум 2 символа' }]}
              style={{ flex: 1 }}
            >
              <Input placeholder="Иванов" style={{ height: 44, borderRadius: 8 }} />
            </Form.Item>
          </Space>

          <Form.Item
            name="sberId"
            label={<span style={{ fontWeight: 500, color: '#374151' }}>Сбер ID</span>}
            rules={[{ required: true, message: 'Введите Сбер ID' }]}
          >
            <Input
              prefix={<IdcardOutlined style={{ color: '#9CA3AF' }} />}
              placeholder="SBER-USR-12345"
              style={{ height: 44, borderRadius: 8 }}
            />
          </Form.Item>

          <Form.Item
            name="phone"
            label={<span style={{ fontWeight: 500, color: '#374151' }}>Телефон</span>}
            rules={[
              { required: true, message: 'Введите номер телефона' },
              { pattern: /^\+7\d{10}$/, message: 'Формат: +7XXXXXXXXXX' },
            ]}
          >
            <Input
              prefix={<PhoneOutlined style={{ color: '#9CA3AF' }} />}
              placeholder="+79001234567"
              style={{ height: 44, borderRadius: 8 }}
            />
          </Form.Item>

          <Form.Item
            name="email"
            label={<span style={{ fontWeight: 500, color: '#374151' }}>Электронная почта</span>}
            rules={[
              { required: true, message: 'Введите email' },
              { type: 'email', message: 'Введите корректный email' },
            ]}
          >
            <Input
              prefix={<MailOutlined style={{ color: '#9CA3AF' }} />}
              placeholder="user@example.com"
              autoComplete="email"
              style={{ height: 44, borderRadius: 8 }}
            />
          </Form.Item>

          <Form.Item
            name="password"
            label={<span style={{ fontWeight: 500, color: '#374151' }}>Пароль</span>}
            rules={[
              { required: true, message: 'Введите пароль' },
              { min: 8, message: 'Минимум 8 символов' },
              { pattern: /^(?=.*[A-Za-z])(?=.*\d).+$/, message: 'Должен содержать буквы и цифры' },
            ]}
          >
            <Input.Password
              prefix={<LockOutlined style={{ color: '#9CA3AF' }} />}
              placeholder="--------"
              style={{ height: 44, borderRadius: 8 }}
            />
          </Form.Item>

          <Form.Item
            name="confirmPassword"
            label={<span style={{ fontWeight: 500, color: '#374151' }}>Подтверждение пароля</span>}
            dependencies={['password']}
            rules={[
              { required: true, message: 'Подтвердите пароль' },
              ({ getFieldValue }) => ({
                validator(_, value) {
                  if (!value || getFieldValue('password') === value) return Promise.resolve()
                  return Promise.reject(new Error('Пароли не совпадают'))
                },
              }),
            ]}
          >
            <Input.Password
              prefix={<LockOutlined style={{ color: '#9CA3AF' }} />}
              placeholder="--------"
              style={{ height: 44, borderRadius: 8 }}
            />
          </Form.Item>

          <Form.Item style={{ marginBottom: 16, marginTop: 8 }}>
            <Button
              type="primary"
              htmlType="submit"
              loading={loading}
              block
              style={{ height: 48, fontSize: 15, fontWeight: 600, borderRadius: 10 }}
            >
              Зарегистрироваться
            </Button>
          </Form.Item>
        </Form>

        <div style={{ textAlign: 'center' }}>
          <Text style={{ color: '#6B7280' }}>
            Уже есть аккаунт?{' '}
            <Link to="/login" style={{ color: '#21A038', fontWeight: 500 }}>
              Войти
            </Link>
          </Text>
        </div>
      </Card>
    </div>
  )
}
