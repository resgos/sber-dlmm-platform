import { Component, type ErrorInfo, type ReactNode } from 'react'
import { Result, Button } from 'antd'
import { useTranslation } from 'react-i18next'

interface Props {
  children: ReactNode
}
interface State {
  hasError: boolean
  error?: Error
}

/**
 * Fallback screen. A function component (not inline in the class render) so it
 * can use the `useTranslation` hook and re-render when the language switches.
 */
export function ErrorFallback({ onReset }: { onReset: () => void }) {
  const { t } = useTranslation()
  return (
    <Result
      status="error"
      title={t('errorBoundary.title')}
      subTitle={t('errorBoundary.subtitle')}
      extra={[
        <Button type="primary" key="reload" onClick={() => window.location.reload()}>
          {t('errorBoundary.reload')}
        </Button>,
        <Button key="home" onClick={() => { window.location.hash = '#/'; onReset() }}>
          {t('errorBoundary.home')}
        </Button>,
      ]}
    />
  )
}

/**
 * Route-level error boundary.
 *
 * Until this existed, a render-time throw in ANY page component (e.g. the
 * OrderBook `pool.bins.filter` crash) propagated to the React root with no
 * boundary, unmounting the WHOLE app — the user got a blank white screen and
 * lost the sidebar/header too. Now a page crash is contained: the layout
 * (nav, header, assistant mascot) stays, and the user sees a recoverable message.
 *
 * Mounted in UserLayout around <Outlet/> with `key={location.pathname}` so it
 * resets automatically when the user navigates to a different route.
 */
export default class ErrorBoundary extends Component<Props, State> {
  state: State = { hasError: false }

  static getDerivedStateFromError(error: Error): State {
    return { hasError: true, error }
  }

  componentDidCatch(error: Error, info: ErrorInfo) {
    // Keep a console trail for diagnostics; a real deployment would ship
    // this to Sentry/console aggregation.
    console.error('[ErrorBoundary] page crash:', error, info.componentStack)
  }

  // Manual retry — clears the error so children re-render. Needed for the
  // "home" button when the crashed page IS already the root route
  // ('/'), where navigation alone doesn't change `key` and so wouldn't reset.
  handleReset = () => this.setState({ hasError: false, error: undefined })

  render() {
    if (this.state.hasError) {
      return <ErrorFallback onReset={this.handleReset} />
    }
    return this.props.children
  }
}
