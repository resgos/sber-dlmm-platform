import { useState } from 'react'
import { Navigate, Outlet, useNavigate, useLocation } from 'react-router-dom'
import { Layout, Menu, Button, Avatar, Space, Typography, Dropdown } from 'antd'
import {
  DashboardOutlined,
  UserOutlined,
  BankOutlined,
  FundOutlined,
  TransactionOutlined,
  WarningOutlined,
  SettingOutlined,
  LogoutOutlined,
  MenuFoldOutlined,
  MenuUnfoldOutlined,
  ShopOutlined,
  ApiOutlined,
  BarChartOutlined,
  HeartOutlined,
} from '@ant-design/icons'
import { authStore } from '@/store/authStore'

const { Header, Sider, Content } = Layout
const { Text } = Typography

const menuItems = [
  {
    key: '/dashboard',
    icon: <DashboardOutlined />,
    label: 'Дашборд',
  },
  {
    key: '/users',
    icon: <UserOutlined />,
    label: 'Пользователи',
  },
  {
    key: '/tokens',
    icon: <BankOutlined />,
    label: 'Токены',
  },
  {
    key: '/pools',
    icon: <FundOutlined />,
    label: 'Пулы',
  },
  {
    key: '/transactions',
    icon: <TransactionOutlined />,
    label: 'Транзакции',
  },
  {
    key: '/transactions/suspicious',
    icon: <WarningOutlined />,
    label: 'Подозрительные',
  },
  {
    key: '/otc',
    icon: <ShopOutlined />,
    label: 'OTC desk',
  },
  {
    // Sprint 10 F-15 — gateway rate-limit analytics by tier.
    key: '/api-analytics',
    icon: <ApiOutlined />,
    label: 'API-аналитика',
  },
  {
    // S14-02 — cohort analytics (was added to dead AdminLayout.tsx by
    // mistake; ProtectedLayout is the real sidebar — fixed 2026-05-27 review).
    key: '/cohorts',
    icon: <BarChartOutlined />,
    label: 'Когорты',
  },
  {
    // B-06 (Batch #5) — pilot health dashboard. Same dead-file mistake;
    // now in the real sidebar.
    key: '/pilots',
    icon: <HeartOutlined />,
    label: 'Здоровье пилотов',
  },
  {
    key: '/settings',
    icon: <SettingOutlined />,
    label: 'Настройки',
  },
]

/* Sber-style checkmark logo SVG. Decorative — title text alongside
   announces "СБЕР DLMM"; mark this aria-hidden to avoid redundant
   screen-reader output (Sprint 8 UX-A11Y-1). */
function SberLogo({ size = 28 }: { size?: number }) {
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 28 28"
      fill="none"
      xmlns="http://www.w3.org/2000/svg"
      aria-hidden
      focusable="false"
    >
      <circle cx="14" cy="14" r="14" fill="#21A038" />
      <path
        d="M7.5 14.5L11.5 18.5L20.5 9.5"
        stroke="white"
        strokeWidth="2.5"
        strokeLinecap="round"
        strokeLinejoin="round"
      />
    </svg>
  )
}

export default function ProtectedLayout() {
  const [collapsed, setCollapsed] = useState(false)
  const navigate = useNavigate()
  const location = useLocation()
  const user = authStore.getUser()

  if (!authStore.isAuthenticated()) {
    return <Navigate to="/login" replace />
  }

  const handleMenuClick = ({ key }: { key: string }) => {
    navigate(key)
  }

  const handleLogout = () => {
    authStore.logout()
    navigate('/login')
  }

  const userMenuItems = [
    {
      key: 'logout',
      icon: <LogoutOutlined />,
      label: 'Выйти',
      onClick: handleLogout,
    },
  ]

  const selectedKey = menuItems.find((item) => location.pathname.startsWith(item.key))?.key || '/dashboard'

  return (
    <Layout style={{ minHeight: '100vh' }}>
      {/* Sprint 9 UX-A11Y-2 wave 2 — skip-to-content link. Visible only on
          keyboard focus (`:focus` reveals it from off-screen). Lets
          screen-reader + keyboard users bypass the Sider navigation tree
          and jump straight to <main id="main-content">. */}
      <a href="#main-content" className="sber-skip-link">
        Перейти к содержимому
      </a>
      <Sider
        trigger={null}
        collapsible
        collapsed={collapsed}
        className="sber-sidebar"
        style={{
          background: 'var(--bg-sidebar)',
          overflow: 'auto',
          height: '100vh',
          position: 'fixed',
          left: 0,
          top: 0,
          bottom: 0,
          zIndex: 100,
          borderRight: '1px solid var(--border-sidebar)',
        }}
        width={240}
        collapsedWidth={64}
      >
        {/* Logo */}
        <div
          style={{
            height: 64,
            display: 'flex',
            alignItems: 'center',
            justifyContent: collapsed ? 'center' : 'flex-start',
            padding: collapsed ? '0' : '0 20px',
            borderBottom: '1px solid var(--border-sidebar)',
            gap: 10,
          }}
        >
          <SberLogo size={collapsed ? 32 : 28} />
          {!collapsed && (
            <div style={{ display: 'flex', flexDirection: 'column', lineHeight: 1.2 }}>
              <Text
                style={{
                  fontWeight: 700,
                  fontSize: 15,
                  color: 'var(--text-primary)',
                  whiteSpace: 'nowrap',
                  letterSpacing: '-0.2px',
                }}
              >
                СБЕР <span style={{ color: 'var(--sber-green)' }}>DLMM</span>
              </Text>
              <Text
                style={{
                  fontSize: 11,
                  color: 'var(--text-muted)',
                  whiteSpace: 'nowrap',
                }}
              >
                Панель управления
              </Text>
            </div>
          )}
        </div>

        {/* Sprint 9 UX-A11Y-2 wave 2 — semantic <nav> wrap + id for
            aria-controls target. selectedKeys already drives aria-current=true
            via AntD Menu's internal aria, but we add the nav landmark
            so screen-readers see "navigation region". */}
        <nav id="admin-sider-navigation" aria-label="Основная навигация">
          <Menu
            theme="light"
            mode="inline"
            selectedKeys={[selectedKey]}
            items={menuItems}
            onClick={handleMenuClick}
            style={{ borderRight: 0, marginTop: 8, background: 'var(--bg-sidebar)' }}
          />
        </nav>
      </Sider>

      <Layout style={{ marginLeft: collapsed ? 64 : 240, transition: 'margin 0.2s', background: 'var(--bg-page)' }}>
        <Header
          style={{
            padding: '0 24px',
            background: 'var(--bg-card)',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'space-between',
            position: 'sticky',
            top: 0,
            zIndex: 99,
            height: 64,
            lineHeight: '64px',
            borderBottom: '1px solid var(--border-light)',
            boxShadow: 'none',
          }}
        >
          <Space>
            <Button
              type="text"
              icon={collapsed ? <MenuUnfoldOutlined /> : <MenuFoldOutlined />}
              onClick={() => setCollapsed(!collapsed)}
              style={{ fontSize: 16, color: 'var(--text-secondary)' }}
              aria-label={collapsed ? 'Развернуть меню' : 'Свернуть меню'}
              aria-expanded={!collapsed}
              aria-controls="admin-sider-navigation"
            />
          </Space>

          <Space size={16}>
            <Dropdown menu={{ items: userMenuItems }} placement="bottomRight">
              <Space
                style={{ cursor: 'pointer' }}
                role="button"
                tabIndex={0}
                aria-label={`Меню администратора ${user?.email || 'Администратор'}`}
                aria-haspopup="menu"
              >
                <Avatar
                  icon={<UserOutlined aria-hidden />}
                  style={{ backgroundColor: 'var(--sber-green)', width: 36, height: 36, lineHeight: '36px' }}
                />
                <div style={{ display: 'flex', flexDirection: 'column', lineHeight: 1.3 }}>
                  <Text strong style={{ fontSize: 13, color: 'var(--text-primary)' }}>
                    {user?.email || 'Администратор'}
                  </Text>
                  {user?.role && (
                    <Text style={{ fontSize: 11, color: 'var(--text-muted)' }}>
                      {user.role}
                    </Text>
                  )}
                </div>
              </Space>
            </Dropdown>
          </Space>
        </Header>

        <Content
          // Sprint 9 UX-A11Y-2 wave 2 — main landmark + id target for the
          // skip-to-content link above. tabIndex=-1 makes it focusable
          // programmatically but not via Tab cycle.
          id="main-content"
          role="main"
          tabIndex={-1}
          style={{
            margin: '24px',
            minHeight: 280,
          }}
        >
          <Outlet />
        </Content>
      </Layout>
    </Layout>
  )
}
