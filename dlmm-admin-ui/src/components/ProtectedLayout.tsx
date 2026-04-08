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
    key: '/settings',
    icon: <SettingOutlined />,
    label: 'Настройки',
  },
]

/* Sber-style checkmark logo SVG */
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
      <Sider
        trigger={null}
        collapsible
        collapsed={collapsed}
        className="sber-sidebar"
        style={{
          background: '#FFFFFF',
          overflow: 'auto',
          height: '100vh',
          position: 'fixed',
          left: 0,
          top: 0,
          bottom: 0,
          zIndex: 100,
          borderRight: '1px solid #F0F0F0',
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
            borderBottom: '1px solid #F0F0F0',
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
                  color: '#1F2937',
                  whiteSpace: 'nowrap',
                  letterSpacing: '-0.2px',
                }}
              >
                СБЕР <span style={{ color: '#21A038' }}>DLMM</span>
              </Text>
              <Text
                style={{
                  fontSize: 11,
                  color: '#9CA3AF',
                  whiteSpace: 'nowrap',
                }}
              >
                Панель управления
              </Text>
            </div>
          )}
        </div>

        <Menu
          theme="light"
          mode="inline"
          selectedKeys={[selectedKey]}
          items={menuItems}
          onClick={handleMenuClick}
          style={{ borderRight: 0, marginTop: 8, background: '#FFFFFF' }}
        />
      </Sider>

      <Layout style={{ marginLeft: collapsed ? 64 : 240, transition: 'margin 0.2s', background: '#F3F4F6' }}>
        <Header
          style={{
            padding: '0 24px',
            background: '#FFFFFF',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'space-between',
            position: 'sticky',
            top: 0,
            zIndex: 99,
            height: 64,
            lineHeight: '64px',
            borderBottom: '1px solid #E5E7EB',
            boxShadow: 'none',
          }}
        >
          <Space>
            <Button
              type="text"
              icon={collapsed ? <MenuUnfoldOutlined /> : <MenuFoldOutlined />}
              onClick={() => setCollapsed(!collapsed)}
              style={{ fontSize: 16, color: '#6B7280' }}
            />
          </Space>

          <Space size={16}>
            <Dropdown menu={{ items: userMenuItems }} placement="bottomRight">
              <Space style={{ cursor: 'pointer' }}>
                <Avatar
                  icon={<UserOutlined />}
                  style={{ backgroundColor: '#21A038', width: 36, height: 36, lineHeight: '36px' }}
                />
                <div style={{ display: 'flex', flexDirection: 'column', lineHeight: 1.3 }}>
                  <Text strong style={{ fontSize: 13, color: '#1F2937' }}>
                    {user?.email || 'Администратор'}
                  </Text>
                  {user?.role && (
                    <Text style={{ fontSize: 11, color: '#9CA3AF' }}>
                      {user.role}
                    </Text>
                  )}
                </div>
              </Space>
            </Dropdown>
          </Space>
        </Header>

        <Content
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
