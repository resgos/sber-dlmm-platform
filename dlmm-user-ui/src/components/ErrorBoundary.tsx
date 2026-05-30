import { Component, type ErrorInfo, type ReactNode } from 'react'
import { Result, Button } from 'antd'

interface Props {
  children: ReactNode
}
interface State {
  hasError: boolean
  error?: Error
}

/**
 * Route-level error boundary.
 *
 * Until this existed, a render-time throw in ANY page component (e.g. the
 * OrderBook `pool.bins.filter` crash) propagated to the React root with no
 * boundary, unmounting the WHOLE app — the user got a blank white screen and
 * lost the sidebar/header too. Now a page crash is contained: the layout
 * (nav, header, Сберкот) stays, and the user sees a recoverable message.
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
  // "На главную" button when the crashed page IS already the root route
  // ('/'), where navigation alone doesn't change `key` and so wouldn't reset.
  handleReset = () => this.setState({ hasError: false, error: undefined })

  render() {
    if (this.state.hasError) {
      return (
        <Result
          status="error"
          title="Что-то пошло не так"
          subTitle="Эта страница не загрузилась. Обновите её или вернитесь назад — остальное приложение работает."
          extra={[
            <Button type="primary" key="reload" onClick={() => window.location.reload()}>
              Обновить страницу
            </Button>,
            <Button key="home" onClick={() => { window.location.hash = '#/'; this.handleReset() }}>
              На главную
            </Button>,
          ]}
        />
      )
    }
    return this.props.children
  }
}
