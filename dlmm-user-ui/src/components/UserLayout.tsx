import { useSyncExternalStore, useState } from 'react'
import { Navigate, Outlet, useNavigate, useLocation } from 'react-router-dom'
import { Layout, Menu, Button, Avatar, Space, Typography, Dropdown } from 'antd'
import {
  HomeOutlined,
  SwapOutlined,
  SafetyCertificateOutlined,
  FundOutlined,
  PieChartOutlined,
  TransactionOutlined,
  UserOutlined,
  LogoutOutlined,
  MenuFoldOutlined,
  MenuUnfoldOutlined,
  RetweetOutlined,
  TeamOutlined,
  StarOutlined,
} from '@ant-design/icons'
import { authStore } from '@/store/authStore'
import { auth } from '@/api/services'
import { uiPrefStore } from '@/store/uiPrefStore'
import NotificationBell from './NotificationBell'
import LanguageSwitcher from './LanguageSwitcher'

const { Header, Sider, Content } = Layout
const { Text } = Typography

/**
 * UI-CRITIQUE 2026-05-22 #11 — sidebar grouping by activity zone.
 *
 * Before this refactor: 9 flat items in one list → high visual
 * repetition at collapsed sidebar (64px) + low scanability. After:
 * 3 semantic groups (Trade / Manage / Account) with dividers. AntD
 * Menu supports `type: 'group'` which renders a header label + items.
 *
 * Sprint 4 #4.1 — hedge reuses swap-API but UX заточен под казначеев.
 * Sprint 10 F-07 — portfolio rebalancer wizard.
 * Sprint 11 G-21 — team / multi-user management.
 * S14-03 — Reviews page + simple-mode hides advanced items (Ребаланс, Команда).
 */

function buildMenuItems(simpleMode: boolean) {
  return [
    {
      type: 'group' as const,
      label: 'Торговля',
      children: [
        { key: '/', icon: <HomeOutlined />, label: 'Главная' },
        { key: '/swap', icon: <SwapOutlined />, label: 'Обмен' },
        { key: '/hedge', icon: <SafetyCertificateOutlined />, label: 'Хедж FX' },
        { key: '/pools', icon: <FundOutlined />, label: 'Пулы' },
      ],
    },
    { type: 'divider' as const },
    {
      type: 'group' as const,
      label: 'Управление',
      children: [
        { key: '/positions', icon: <PieChartOutlined />, label: 'Мои позиции' },
        ...(!simpleMode ? [{ key: '/rebalance', icon: <RetweetOutlined />, label: 'Ребаланс' }] : []),
        { key: '/transactions', icon: <TransactionOutlined />, label: 'Транзакции' },
      ],
    },
    { type: 'divider' as const },
    {
      type: 'group' as const,
      label: 'Аккаунт',
      children: [
        ...(!simpleMode ? [{ key: '/team', icon: <TeamOutlined />, label: 'Команда' }] : []),
        { key: '/reviews', icon: <StarOutlined />, label: 'Отзывы' },
        { key: '/profile', icon: <UserOutlined />, label: 'Профиль' },
      ],
    },
  ]
}

/** All possible leaf items (simpleMode=false) — used by selectedKey lookup. */
const ALL_LEAF_KEYS = buildMenuItems(false)

interface LeafMenuItem {
  key: string
  icon: JSX.Element
  label: string
}

/** Flat list of all leaf items — used by selectedKey lookup. */
const flatMenuItems: LeafMenuItem[] = ALL_LEAF_KEYS
  .flatMap((m) => ('children' in m ? m.children : []))
  .filter((m): m is LeafMenuItem => m != null && 'key' in m)

function SberLogo({ size = 28 }: { size?: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 28 28" fill="none" xmlns="http://www.w3.org/2000/svg">
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

export default function UserLayout() {
  const [collapsed, setCollapsed] = useState(false)
  const navigate = useNavigate()
  const location = useLocation()
  const user = authStore.getUser()
  const prefs = useSyncExternalStore(uiPrefStore.subscribe, uiPrefStore.getSnapshot)
  const menuItems = buildMenuItems(prefs.simpleMode)

  if (!authStore.isAuthenticated()) {
    return <Navigate to="/login" replace />
  }

  const handleMenuClick = ({ key }: { key: string }) => {
    navigate(key)
  }

  const handleLogout = async () => {
    // Sprint 8 AU-3 — fire server-side revocation BEFORE wiping local
    // tokens. The api/auth.logout call uses the Bearer header to extract
    // the jti and write it to the Redis denylist. Best-effort: failures
    // are swallowed in auth.logout itself so we always reach navigate().
    const refresh = authStore.getRefreshToken()
    await auth.logout(refresh ?? undefined)
    authStore.logout()
    navigate('/login')
  }

  const userMenuItems = [
    { key: 'profile', icon: <UserOutlined />, label: 'Профиль', onClick: () => navigate('/profile') },
    { key: 'logout', icon: <LogoutOutlined />, label: 'Выйти', onClick: handleLogout },
  ]

  const selectedKey = (() => {
    const path = location.pathname
    if (path === '/') return '/'
    // After UI-CRITIQUE #11 grouping, top-level menuItems are groups,
    // not leaf items — selectedKey lookup must scan flatMenuItems.
    const match = flatMenuItems.find((item) => item.key !== '/' && path.startsWith(item.key))
    return match?.key || '/'
  })()

  return (
    <Layout style={{ minHeight: '100vh' }}>
      {/* Sprint 9 UX-A11Y-2 wave 2 — skip-to-content link. Off-screen
          until focused; lets keyboard / screen-reader users bypass the
          Sider navigation tree. */}
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
              <Text style={{ fontSize: 'var(--text-xs)', color: 'var(--text-muted)', whiteSpace: 'nowrap' }}>
                Платформа ликвидности
              </Text>
            </div>
          )}
        </div>

        {/* Sprint 9 UX-A11Y-2 wave 2 — semantic <nav> landmark + id
            for aria-controls target. */}
        <nav id="sider-navigation" aria-label="Основная навигация">
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
              style={{ fontSize: 'var(--text-md)', color: 'var(--text-secondary)' }}
              aria-label={collapsed ? 'Развернуть меню' : 'Свернуть меню'}
              aria-expanded={!collapsed}
              aria-controls="sider-navigation"
            />
          </Space>

          <Space size={20}>
            {/* Sprint 9-DS-r4 P2-14 — language picker; compact RU/EN toggle. */}
            <LanguageSwitcher />
            <NotificationBell />
            <Dropdown menu={{ items: userMenuItems }} placement="bottomRight">
              <Space
                style={{ cursor: 'pointer' }}
                role="button"
                tabIndex={0}
                aria-label={`Меню пользователя ${user?.email || 'Пользователь'}`}
                aria-haspopup="menu"
              >
                <Avatar
                  icon={<UserOutlined aria-hidden />}
                  style={{ backgroundColor: 'var(--sber-green)', width: 36, height: 36, lineHeight: '36px' }}
                />
                <div style={{ display: 'flex', flexDirection: 'column', lineHeight: 1.3 }}>
                  <Text strong style={{ fontSize: 'var(--text-sm)', color: 'var(--text-primary)' }}>
                    {user?.email || 'Пользователь'}
                  </Text>
                  <Text style={{ fontSize: 'var(--text-xs)', color: 'var(--text-muted)' }}>
                    {user?.kycStatus === 'VERIFIED' ? 'Верифицирован' : 'Не верифицирован'}
                  </Text>
                </div>
              </Space>
            </Dropdown>
          </Space>
        </Header>

        <Content
          // Sprint 9 UX-A11Y-2 wave 2 — main landmark + skip-link target.
          id="main-content"
          role="main"
          tabIndex={-1}
          style={{ margin: '24px', minHeight: 280 }}
        >
          <Outlet />
        </Content>
      </Layout>
    </Layout>
  )
}
