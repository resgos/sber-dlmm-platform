import { useState } from 'react'
import { useNavigate, Link } from 'react-router-dom'
import { Form, Input, Button, Card, Typography, Alert, Space } from 'antd'
import { UserOutlined, LockOutlined, MailOutlined, IdcardOutlined, PhoneOutlined } from '@ant-design/icons'
import { useTranslation } from 'react-i18next'
import { auth } from '@/api/services'
import { authStore } from '@/store/authStore'

const { Title, Text } = Typography

function SberLogoLarge() {
  // Gradient pill brand mark — matches LoginPage. See .sber-brand-mark in sber-theme.css.
  return (
    <div className="sber-brand-mark">
      <svg width="34" height="34" viewBox="0 0 34 34" fill="none" xmlns="http://www.w3.org/2000/svg">
        <path d="M9 17L14.5 22.5L25 12" stroke="white" strokeWidth="3" strokeLinecap="round" strokeLinejoin="round" />
      </svg>
    </div>
  )
}

export default function RegisterPage() {
  // 2026-06-17 — wired to i18n. The auth.register.* copy existed in BOTH
  // locale bundles for a while but the page stayed hardcoded Russian — the
  // exact "dead keys" class the lint:i18n-dead ratchet now guards against.
  // An EN investor saw a Russian registration form as the first screen.
  const { t } = useTranslation()
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
      setError(axiosError?.response?.data?.message || t('auth.register.errorFallback'))
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="sber-login-bg">
      <Card className="sber-glass-card" styles={{ body: { padding: '44px' } }} style={{ width: 480 }}>
        <Space direction="vertical" size={12} style={{ width: '100%', marginBottom: 28, textAlign: 'center' }}>
          <SberLogoLarge />
          <Title level={3} className="sber-brand-title" style={{ margin: 0 }}>
            СБЕР <span className="sber-brand-title-accent">DLMM</span>
          </Title>
          <Text style={{ color: 'var(--text-secondary)', fontSize: 'var(--text-base)' }}>{t('auth.register.subtitle')}</Text>
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
          <Space style={{ width: '100%' }} size={12}>
            <Form.Item
              name="firstName"
              label={<span style={{ fontWeight: 500, color: '#374151' }}>{t('auth.register.firstNameLabel')}</span>}
              rules={[
                { required: true, message: t('auth.register.firstNameRequired') },
                { min: 2, message: t('auth.register.firstNameMin') },
              ]}
              style={{ flex: 1 }}
            >
              <Input
                prefix={<UserOutlined style={{ color: '#9CA3AF' }} />}
                placeholder={t('auth.register.firstNamePlaceholder')}
                style={{ height: 44, borderRadius: 'var(--radius-sm)' }}
              />
            </Form.Item>
            <Form.Item
              name="lastName"
              label={<span style={{ fontWeight: 500, color: '#374151' }}>{t('auth.register.lastNameLabel')}</span>}
              rules={[
                { required: true, message: t('auth.register.lastNameRequired') },
                { min: 2, message: t('auth.register.lastNameMin') },
              ]}
              style={{ flex: 1 }}
            >
              <Input placeholder={t('auth.register.lastNamePlaceholder')} style={{ height: 44, borderRadius: 'var(--radius-sm)' }} />
            </Form.Item>
          </Space>

          <Form.Item
            name="sberId"
            label={<span style={{ fontWeight: 500, color: '#374151' }}>{t('auth.register.sberIdLabel')}</span>}
            rules={[{ required: true, message: t('auth.register.sberIdRequired') }]}
          >
            <Input
              prefix={<IdcardOutlined style={{ color: '#9CA3AF' }} />}
              placeholder={t('auth.register.sberIdPlaceholder')}
              style={{ height: 44, borderRadius: 'var(--radius-sm)' }}
            />
          </Form.Item>

          <Form.Item
            name="phone"
            label={<span style={{ fontWeight: 500, color: '#374151' }}>{t('auth.register.phoneLabel')}</span>}
            rules={[
              { required: true, message: t('auth.register.phoneRequired') },
              { pattern: /^\+7\d{10}$/, message: t('auth.register.phonePattern') },
            ]}
          >
            <Input
              prefix={<PhoneOutlined style={{ color: '#9CA3AF' }} />}
              placeholder={t('auth.register.phonePlaceholder')}
              style={{ height: 44, borderRadius: 'var(--radius-sm)' }}
            />
          </Form.Item>

          <Form.Item
            name="email"
            label={<span style={{ fontWeight: 500, color: '#374151' }}>{t('auth.register.emailLabel')}</span>}
            rules={[
              { required: true, message: t('auth.register.emailRequired') },
              { type: 'email', message: t('auth.register.emailInvalid') },
            ]}
          >
            <Input
              prefix={<MailOutlined style={{ color: '#9CA3AF' }} />}
              placeholder="user@example.com"
              autoComplete="email"
              style={{ height: 44, borderRadius: 'var(--radius-sm)' }}
            />
          </Form.Item>

          <Form.Item
            name="password"
            label={<span style={{ fontWeight: 500, color: '#374151' }}>{t('auth.register.passwordLabel')}</span>}
            rules={[
              { required: true, message: t('auth.register.passwordRequired') },
              { min: 8, message: t('auth.register.passwordMin') },
              { pattern: /^(?=.*[A-Za-z])(?=.*\d).+$/, message: t('auth.register.passwordPattern') },
            ]}
          >
            <Input.Password
              prefix={<LockOutlined style={{ color: '#9CA3AF' }} />}
              placeholder="--------"
              style={{ height: 44, borderRadius: 'var(--radius-sm)' }}
            />
          </Form.Item>

          <Form.Item
            name="confirmPassword"
            label={<span style={{ fontWeight: 500, color: '#374151' }}>{t('auth.register.confirmPasswordLabel')}</span>}
            dependencies={['password']}
            rules={[
              { required: true, message: t('auth.register.confirmPasswordRequired') },
              ({ getFieldValue }) => ({
                validator(_, value) {
                  if (!value || getFieldValue('password') === value) return Promise.resolve()
                  return Promise.reject(new Error(t('auth.register.passwordMismatch')))
                },
              }),
            ]}
          >
            <Input.Password
              prefix={<LockOutlined style={{ color: '#9CA3AF' }} />}
              placeholder="--------"
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
              {t('auth.register.submit')}
            </Button>
          </Form.Item>
        </Form>

        <div style={{ textAlign: 'center' }}>
          <Text style={{ color: '#6B7280' }}>
            {t('auth.register.hasAccount')}{' '}
            <Link to="/login" style={{ color: '#21A038', fontWeight: 500 }}>
              {t('auth.register.loginLink')}
            </Link>
          </Text>
        </div>
      </Card>
    </div>
  )
}
