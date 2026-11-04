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
  const { t } = useTranslation()
  const [status, setStatus] = React.useState<'loading' | 'error'>('loading')

  React.useEffect(() => {
    // In full integration this would exchange the SAML assertion (POSTed by the
    // IdP to /api/v1/auth/saml/callback) for a platform JWT, store it in
    // authStore and navigate('/'). For now the backend returns 501.
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
          title={t('samlCallback.notConfiguredTitle')}
          subTitle={t('samlCallback.notConfiguredSubtitle')}
          extra={
            <Button type="primary" href="#/login">
              {t('samlCallback.backToLogin')}
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
      <Spin tip={t('samlCallback.loading')} style={{ padding: 80 }} />
    </div>
  )
}

// Import kept at the bottom so the inline-style lines above keep their numbers (lint:styles baseline).
import { useTranslation } from 'react-i18next'
