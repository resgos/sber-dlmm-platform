import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { Form, Input, Button, Card, Typography, Alert, Space } from 'antd'
import { UserOutlined, LockOutlined } from '@ant-design/icons'
import { auth } from '@/api/services'
import { authStore } from '@/store/authStore'

const { Title, Text } = Typography

interface LoginFormValues {
  email: string
  password: string
}

/* Sber-style checkmark logo SVG */
function SberLogoLarge() {
  return (
    <svg width="56" height="56" viewBox="0 0 56 56" fill="none" xmlns="http://www.w3.org/2000/svg">
      <circle cx="28" cy="28" r="28" fill="#21A038" />
      <path
        d="M15 28L23 36L41 18"
        stroke="white"
        strokeWidth="4"
        strokeLinecap="round"
        strokeLinejoin="round"
      />
    </svg>
  )
}

export default function LoginPage() {
  const navigate = useNavigate()
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [form] = Form.useForm<LoginFormValues>()

  const handleSubmit = async (values: LoginFormValues) => {
    setLoading(true)
    setError(null)
    try {
      const response = await auth.login(values.email, values.password)
      authStore.setToken(response.accessToken)
      authStore.setUser({
        userId: response.user.id,
        email: response.user.email,
        role: response.user.role,
      })
      navigate('/dashboard', { replace: true })
    } catch (err: unknown) {
      const axiosError = err as { response?: { data?: { message?: string } } }
      setError(axiosError?.response?.data?.message || 'Неверный email или пароль. Попробуйте снова.')
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="sber-login-bg">
      <Card
        style={{
          width: 420,
          borderRadius: 16,
          boxShadow: '0 4px 24px rgba(0,0,0,0.08)',
          border: '1px solid #E5E7EB',
        }}
        styles={{ body: { padding: '40px' } }}
      >
        <Space direction="vertical" size={8} style={{ width: '100%', marginBottom: 32, textAlign: 'center' }}>
          <div style={{ display: 'flex', justifyContent: 'center', marginBottom: 8 }}>
            <SberLogoLarge />
          </div>
          <Title level={3} style={{ margin: 0, color: '#1F2937', fontWeight: 700 }}>
            СБЕР <span style={{ color: '#21A038' }}>DLMM</span>
          </Title>
          <Text style={{ color: '#6B7280', fontSize: 14 }}>Панель администратора платформы</Text>
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

        <Form
          form={form}
          layout="vertical"
          onFinish={handleSubmit}
          autoComplete="off"
          size="large"
        >
          <Form.Item
            name="email"
            label={<span style={{ fontWeight: 500, color: '#374151' }}>Электронная почта</span>}
            rules={[
              { required: true, message: 'Введите электронную почту' },
              { type: 'email', message: 'Введите корректный адрес электронной почты' },
            ]}
          >
            <Input
              prefix={<UserOutlined style={{ color: '#9CA3AF' }} />}
              placeholder="admin@example.com"
              autoComplete="email"
              style={{ height: 44, borderRadius: 8 }}
            />
          </Form.Item>

          <Form.Item
            name="password"
            label={<span style={{ fontWeight: 500, color: '#374151' }}>Пароль</span>}
            rules={[{ required: true, message: 'Введите пароль' }]}
          >
            <Input.Password
              prefix={<LockOutlined style={{ color: '#9CA3AF' }} />}
              placeholder="••••••••"
              autoComplete="current-password"
              style={{ height: 44, borderRadius: 8 }}
            />
          </Form.Item>

          <Form.Item style={{ marginBottom: 0, marginTop: 8 }}>
            <Button
              type="primary"
              htmlType="submit"
              loading={loading}
              block
              style={{
                height: 48,
                fontSize: 15,
                fontWeight: 600,
                borderRadius: 10,
                background: '#21A038',
                borderColor: '#21A038',
              }}
            >
              Войти
            </Button>
          </Form.Item>
        </Form>
      </Card>
    </div>
  )
}
