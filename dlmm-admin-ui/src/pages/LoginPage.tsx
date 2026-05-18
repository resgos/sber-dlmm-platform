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

function SberLogoLarge() {
  // Sprint 8 UX-A11Y-1 — decorative brand mark; the heading below already
  // announces "СБЕР DLMM". aria-hidden prevents redundant screen-reader output.
  return (
    <div className="sber-brand-mark" aria-hidden>
      <svg width="34" height="34" viewBox="0 0 34 34" fill="none" xmlns="http://www.w3.org/2000/svg">
        <path d="M9 17L14.5 22.5L25 12" stroke="white" strokeWidth="3" strokeLinecap="round" strokeLinejoin="round" />
      </svg>
    </div>
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
      <Card className="sber-glass-card" styles={{ body: { padding: '44px' } }}>
        <Space direction="vertical" size={12} style={{ width: '100%', marginBottom: 32, textAlign: 'center' }}>
          <SberLogoLarge />
          <Title level={3} className="sber-brand-title" style={{ margin: 0 }}>
            СБЕР <span className="sber-brand-title-accent">DLMM</span>
          </Title>
          <Text style={{ color: 'var(--text-secondary)', fontSize: 14 }}>Панель администратора платформы</Text>
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
              style={{ height: 48, fontSize: 15, fontWeight: 600, borderRadius: 10 }}
            >
              Войти
            </Button>
          </Form.Item>
        </Form>
      </Card>
    </div>
  )
}
