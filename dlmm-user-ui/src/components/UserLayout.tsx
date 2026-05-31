import { useSyncExternalStore, useState } from 'react'
import { Navigate, Outlet, useNavigate, useLocation } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import type { TFunction } from 'i18next'
import { Layout, Menu, Button, Avatar, Space, Typography, Dropdown, Segmented, Tooltip, Grid, Drawer } from 'antd'
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
  ThunderboltOutlined,
  AppstoreOutlined,
  AimOutlined,
} from '@ant-design/icons'
import { authStore } from '@/store/authStore'
import { auth } from '@/api/services'
import { uiPrefStore } from '@/store/uiPrefStore'
import NotificationBell from './NotificationBell'
import LanguageSwitcher from './LanguageSwitcher'
import DemoBranding from './DemoBranding'
import SberkotAssistant from './sberkot/SberkotAssistant'
import ErrorBoundary from './ErrorBoundary'

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

function buildMenuItems(simpleMode: boolean, t: TFunction) {
  return [
    {
      type: 'group' as const,
      label: t('nav.tradeGroup'),
      children: [
        { key: '/', icon: <HomeOutlined />, label: t('nav.home') },
        // SM-01 — Simple mode surfaces a single airy buy/sell+LP page as the
        // primary trading entry and hides the advanced Swap / Хедж FX.
        ...(simpleMode
          ? [{ key: '/simple', icon: <ThunderboltOutlined />, label: t('nav.buySell') }]
          : [
              { key: '/swap', icon: <SwapOutlined />, label: t('nav.swap') },
              { key: '/hedge', icon: <SafetyCertificateOutlined />, label: t('nav.hedge') },
            ]),
        { key: '/pools', icon: <FundOutlined />, label: t('nav.pools') },
      ],
    },
    { type: 'divider' as const },
    {
      type: 'group' as const,
      label: t('nav.manageGroup'),
      children: [
        { key: '/positions', icon: <PieChartOutlined />, label: t('nav.positions') },
        { key: '/orders', icon: <AimOutlined />, label: t('nav.orders') },
        ...(!simpleMode ? [{ key: '/rebalance', icon: <RetweetOutlined />, label: t('nav.rebalance') }] : []),
        { key: '/transactions', icon: <TransactionOutlined />, label: t('nav.transactions') },
      ],
    },
    { type: 'divider' as const },
    {
      type: 'group' as const,
      label: t('nav.accountGroup'),
      children: [
        ...(!simpleMode ? [{ key: '/team', icon: <TeamOutlined />, label: t('nav.team') }] : []),
        { key: '/reviews', icon: <StarOutlined />, label: t('nav.reviews') },
        { key: '/profile', icon: <UserOutlined />, label: t('nav.profile') },
      ],
    },
  ]
}

/** All possible leaf items across BOTH modes — used by selectedKey lookup
 *  so /simple, /swap, /hedge etc. all resolve regardless of current mode.
 *  Only `.key` is read here, so an identity translator is fine for the labels. */
const keyOnly = ((k: string) => k) as unknown as TFunction
const ALL_LEAF_KEYS = [...buildMenuItems(false, keyOnly), ...buildMenuItems(true, keyOnly)]

interface LeafMenuItem {
  key: string
  icon: JSX.Element
  label: string
}

/** Flat list of all leaf items (deduped by key) — selectedKey lookup. */
const flatMenuItems: LeafMenuItem[] = Array.from(
  new Map(
    ALL_LEAF_KEYS
      .flatMap((m) => ('children' in m ? m.children : []))
      .filter((m): m is LeafMenuItem => m != null && 'key' in m)
      .map((m) => [m.key, m]),
  ).values(),
)

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
  const { t } = useTranslation()
  const [collapsed, setCollapsed] = useState(false)
  const [mobileNavOpen, setMobileNavOpen] = useState(false)
  const [userMenuOpen, setUserMenuOpen] = useState(false)
  const screens = Grid.useBreakpoint()
  // mobile audit C1: below `md` the fixed 240px Sider becomes an overlay Drawer.
  const isMobile = !screens.md
  const navigate = useNavigate()
  const location = useLocation()
  const user = authStore.getUser()
  const prefs = useSyncExternalStore(uiPrefStore.subscribe, uiPrefStore.getSnapshot)
  const menuItems = buildMenuItems(prefs.simpleMode, t)

  if (!authStore.isAuthenticated()) {
    return <Navigate to="/login" replace />
  }

  const handleMenuClick = ({ key }: { key: string }) => {
    navigate(key)
  }

  // SM-01 — switching to Simple drops the user on the new simple trading
  // surface; switching to Pro returns to the dashboard. Both are coherent
  // landing spots so the mode flip is never a dead-end.
  const handleModeChange = (value: string | number) => {
    const next = value === 'simple' ? 'simple' : 'pro'
    uiPrefStore.setMode(next)
    navigate(next === 'simple' ? '/simple' : '/')
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
    { key: 'profile', icon: <UserOutlined />, label: t('nav.profile'), onClick: () => navigate('/profile') },
    { key: 'logout', icon: <LogoutOutlined />, label: t('nav.logout'), onClick: handleLogout },
  ]

  const selectedKey = (() => {
    const path = location.pathname
    if (path === '/') return '/'
    // After UI-CRITIQUE #11 grouping, top-level menuItems are groups,
    // not leaf items — selectedKey lookup must scan flatMenuItems.
    const match = flatMenuItems.find((item) => item.key !== '/' && path.startsWith(item.key))
    return match?.key || '/'
  })()

  // Sider/Drawer share this inner content. `isCollapsed` only applies to the
  // desktop Sider (the Drawer always renders full-width).
  const siderInner = (isCollapsed: boolean) => (
    <>
      <div
        style={{
          height: 64,
          display: 'flex',
          alignItems: 'center',
          justifyContent: isCollapsed ? 'center' : 'flex-start',
          padding: isCollapsed ? '0' : '0 20px',
          borderBottom: '1px solid var(--border-sidebar)',
          gap: 10,
        }}
      >
        <SberLogo size={isCollapsed ? 32 : 28} />
        {!isCollapsed && (
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
              {t('brand.tagline')}
            </Text>
          </div>
        )}
      </div>
      <nav id="sider-navigation" aria-label={t('nav.mainNav')}>
        <Menu
          theme="light"
          mode="inline"
          selectedKeys={[selectedKey]}
          items={menuItems}
          onClick={(e) => { handleMenuClick(e); setMobileNavOpen(false) }}
          style={{ borderRight: 0, marginTop: 8, background: 'var(--bg-sidebar)' }}
        />
      </nav>
    </>
  )

  return (
    <Layout style={{ minHeight: '100vh' }}>
      {/* Sprint 9 UX-A11Y-2 wave 2 — skip-to-content link. Off-screen
          until focused; lets keyboard / screen-reader users bypass the
          Sider navigation tree. */}
      <a href="#main-content" className="sber-skip-link">
        {t('nav.skipToContent')}
      </a>
      {isMobile ? (
        <Drawer
          placement="left"
          open={mobileNavOpen}
          onClose={() => setMobileNavOpen(false)}
          width={240}
          closable={false}
          /* a11y: forceRender keeps the <nav id="sider-navigation"> mounted even
             while the drawer is closed, so the header hamburger's
             aria-controls="sider-navigation" never dangles to a missing node. */
          forceRender
          className="sber-sidebar"
          styles={{ body: { padding: 0, background: 'var(--bg-sidebar)' } }}
        >
          {siderInner(false)}
        </Drawer>
      ) : (
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
          {siderInner(collapsed)}
        </Sider>
      )}

      <Layout style={{ marginLeft: isMobile ? 0 : collapsed ? 64 : 240, transition: 'margin 0.2s', background: 'var(--bg-page)' }}>
        <Header
          style={{
            padding: isMobile ? '0 12px' : '0 24px',
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
          <Space size={isMobile ? 8 : 16}>
            <Button
              type="text"
              icon={!isMobile && !collapsed ? <MenuFoldOutlined /> : <MenuUnfoldOutlined />}
              onClick={() => (isMobile ? setMobileNavOpen(true) : setCollapsed(!collapsed))}
              style={{ fontSize: 'var(--text-md)', color: 'var(--text-secondary)' }}
              aria-label={(isMobile ? mobileNavOpen : !collapsed) ? t('nav.collapseMenu') : t('nav.expandMenu')}
              aria-expanded={isMobile ? mobileNavOpen : !collapsed}
              aria-controls="sider-navigation"
            />
            {/* SM-01 — prominent Simple ⇄ Pro mode pill + a state chip so
                the user always knows which surface they're on. */}
            <Tooltip
              title={prefs.simpleMode ? t('nav.modeTooltipSimple') : t('nav.modeTooltipPro')}
            >
              <Segmented
                value={prefs.mode}
                onChange={handleModeChange}
                className="sber-mode-toggle"
                aria-label={t('nav.modeLabel')}
                options={[
                  { value: 'simple', label: <span className="sber-mode-toggle__opt"><ThunderboltOutlined /> {t('nav.modeSimple')}</span> },
                  { value: 'pro', label: <span className="sber-mode-toggle__opt"><AppstoreOutlined /> {t('nav.modePro')}</span> },
                ]}
              />
            </Tooltip>
            <span className={`sber-mode-chip sber-mode-chip--${prefs.mode}`}>
              {prefs.simpleMode ? t('nav.modeSimple') : t('nav.modePro')}
            </span>
          </Space>

          <Space size={isMobile ? 8 : 20}>
            {/* QW-3 — personalized demo URL banner; hidden on mobile to save room. */}
            {!isMobile && <DemoBranding />}
            {/* Sprint 9-DS-r4 P2-14 — language picker; compact RU/EN toggle. */}
            <LanguageSwitcher />
            <NotificationBell />
            <Dropdown
              menu={{ items: userMenuItems }}
              placement="bottomRight"
              open={userMenuOpen}
              onOpenChange={setUserMenuOpen}
            >
              <Space
                style={{ cursor: 'pointer' }}
                role="button"
                tabIndex={0}
                aria-label={t('nav.userMenu', { name: user?.email || t('nav.defaultUser') })}
                aria-haspopup="menu"
                aria-expanded={userMenuOpen}
                onKeyDown={(e) => {
                  // a11y R2: AntD Dropdown opens on click/hover only — wire
                  // Enter/Space to the controlled open state for keyboard users.
                  if (e.key === 'Enter' || e.key === ' ') {
                    e.preventDefault()
                    setUserMenuOpen((o) => !o)
                  }
                }}
              >
                <Avatar
                  icon={<UserOutlined aria-hidden />}
                  style={{ backgroundColor: 'var(--sber-green)', width: 36, height: 36, lineHeight: '36px' }}
                />
                {/* mobile audit C2 — email + KYC label hidden below md so the
                    header's two clusters don't collide on a phone. */}
                {!isMobile && (
                  <div style={{ display: 'flex', flexDirection: 'column', lineHeight: 1.3 }}>
                    <Text strong style={{ fontSize: 'var(--text-sm)', color: 'var(--text-primary)' }}>
                      {user?.email || t('nav.defaultUser')}
                    </Text>
                    <Text style={{ fontSize: 'var(--text-xs)', color: 'var(--text-muted)' }}>
                      {user?.kycStatus === 'VERIFIED' ? t('nav.verified') : t('nav.notVerified')}
                    </Text>
                  </div>
                )}
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
          <ErrorBoundary key={location.pathname}>
            <Outlet />
          </ErrorBoundary>
        </Content>
        {/* SK-01 — floating Сберкот assistant (position:fixed; mounts once
            inside the authenticated layout so it's gone on /login). */}
        <SberkotAssistant />
      </Layout>
    </Layout>
  )
}
