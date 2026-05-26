import React from 'react'
import { Button, Result, Spin } from 'antd'

/**
 * SAML 2.0 SSO callback landing page.
 *
 * In full integration (Sprint 15) this page would exchange the SAML assertion
 * for a platform JWT and redirect the user to the dashboard. For now it
 * surfaces the 501 NOT_IMPLEMENTED state gracefully while the IdP integration
 * is being completed.
 */
export default function SamlCallbackPage() {
  const [status, setStatus] = React.useState<'loading' | 'error'>('loading')

  React.useEffect(() => {
    // In full integration this would call the backend to exchange the SAML
    // assertion (POSTed by the IdP to /api/v1/auth/saml/callback) for a
    // platform JWT, then store it in authStore and navigate('/').
    // For now the backend returns 501 — surface that gracefully.
    setStatus('error')
  }, [])

  if (status === 'error') {
    return (
      <div
        style={{
          minHeight: '100vh',
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
        }}
      >
        <Result
          status="info"
          title="Корпоративный SSO не настроен"
          subTitle="IdP не сконфигурирован. Обратитесь к администратору или воспользуйтесь стандартным входом."
          extra={
            <Button type="primary" href="#/login">
              Вернуться ко входу
            </Button>
          }
        />
      </div>
    )
  }

  return (
    <div
      style={{
        minHeight: '100vh',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
      }}
    >
      <Spin tip="Завершение корпоративного входа..." style={{ padding: 80 }} />
    </div>
  )
}
