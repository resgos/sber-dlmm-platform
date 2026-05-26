import { useState } from 'react'
import { useNavigate, Link } from 'react-router-dom'
import { Form, Input, Button, Card, Typography, Alert, Space } from 'antd'
import { UserOutlined, LockOutlined } from '@ant-design/icons'
import { useTranslation } from 'react-i18next'
import { auth } from '@/api/services'
import { authStore } from '@/store/authStore'

const { Title, Text } = Typography

function SberLogoLarge() {
  // Logo lockup is now a CSS gradient pill so it picks up brand tokens — see
  // .sber-brand-mark / --grad-brand. The SVG inside is just the checkmark.
  return (
    <div className="sber-brand-mark" aria-hidden>
      <svg width="34" height="34" viewBox="0 0 34 34" fill="none" xmlns="http://www.w3.org/2000/svg">
        <path d="M9 17L14.5 22.5L25 12" stroke="white" strokeWidth="3" strokeLinecap="round" strokeLinejoin="round" />
      </svg>
    </div>
  )
}

export default function LoginPage() {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [form] = Form.useForm()

  const handleSubmit = async (values: { email: string; password: string }) => {
    setLoading(true)
    setError(null)
    try {
      const response = await auth.login(values.email, values.password)
      // R-04 — persist BOTH tokens so the apiClient interceptor can
      // transparently refresh on 401 without forcing the user back to
      // /login mid-session. Previously only accessToken was saved.
      authStore.setTokens(response.accessToken, response.refreshToken)
      authStore.setUser({
        userId: response.user.id,
        email: response.user.email,
        role: response.user.role,
        kycStatus: response.user.kycStatus,
      })
      navigate('/', { replace: true })
    } catch (err: unknown) {
      const axiosError = err as { response?: { data?: { message?: string } } }
      setError(axiosError?.response?.data?.message || t('auth.login.errorFallback'))
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
            {t('brand.sber')} <span className="sber-brand-title-accent">{t('brand.product')}</span>
          </Title>
          <Text style={{ color: 'var(--text-secondary)', fontSize: 'var(--text-base)' }}>{t('auth.login.subtitle')}</Text>
        </Space>

        {error && (
          <Alert
            message={error}
            type="error"
            showIcon
            closable
            onClose={() => setError(null)}
            style={{ marginBottom: 24, borderRadius: 'var(--radius-sm)' }}
          />
        )}

        <Form form={form} layout="vertical" onFinish={handleSubmit} autoComplete="off" size="large">
          <Form.Item
            name="email"
            label={<span style={{ fontWeight: 500, color: '#374151' }}>{t('auth.login.emailLabel')}</span>}
            rules={[
              { required: true, message: t('auth.login.emailRequired') },
              { type: 'email', message: t('auth.login.emailInvalid') },
            ]}
          >
            <Input
              prefix={<UserOutlined style={{ color: '#9CA3AF' }} />}
              placeholder={t('auth.login.emailPlaceholder')}
              autoComplete="email"
              style={{ height: 44, borderRadius: 'var(--radius-sm)' }}
            />
          </Form.Item>

          <Form.Item
            name="password"
            label={<span style={{ fontWeight: 500, color: '#374151' }}>{t('auth.login.passwordLabel')}</span>}
            rules={[{ required: true, message: t('auth.login.passwordRequired') }]}
          >
            <Input.Password
              prefix={<LockOutlined style={{ color: '#9CA3AF' }} />}
              placeholder="--------"
              autoComplete="current-password"
              style={{ height: 44, borderRadius: 'var(--radius-sm)' }}
            />
          </Form.Item>

          <Form.Item style={{ marginBottom: 16, marginTop: 8 }}>
            <Button
              type="primary"
              htmlType="submit"
              loading={loading}
              block
              style={{ height: 48, fontSize: 15, fontWeight: 600, borderRadius: 'var(--radius-sm)' }}
            >
              {t('auth.login.submit')}
            </Button>
          </Form.Item>
        </Form>

        <div style={{ textAlign: 'center' }}>
          <Text style={{ color: '#6B7280' }}>
            {t('auth.login.noAccount')}{' '}
            <Link to="/register" style={{ color: '#21A038', fontWeight: 500 }}>
              {t('auth.login.register')}
            </Link>
          </Text>
        </div>
      </Card>
    </div>
  )
}
