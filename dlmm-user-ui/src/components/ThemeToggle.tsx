import { useSyncExternalStore } from 'react'
import { useTranslation } from 'react-i18next'
import { Card, Radio, Space, Typography } from 'antd'
import { BulbOutlined, BulbFilled, DesktopOutlined } from '@ant-design/icons'
import { themeStore, type ThemeMode } from '@/store/themeStore'

const { Text } = Typography

/**
 * Sprint 9-DS-r4 P2-15 — theme picker.
 *
 * Three-way toggle: Light / Dark / System (follows OS). Persists via
 * themeStore (localStorage). Shown in the Profile page right rail so
 * the user can find it without hunting through nav menus, and stays a
 * Card so the page rhythm matches the other right-rail cards
 * (account summary, recent activity). Copy lives in profile.theme.*.
 *
 * Why useSyncExternalStore (React 18) rather than useState + effect:
 * the theme can change from outside React — `storage` events from
 * another tab, OS preference flip in 'system' mode. SyncExternal keeps
 * the radio reflecting the truth without a manual re-render hook.
 */
export default function ThemeToggle() {
  const { t } = useTranslation()
  const mode = useSyncExternalStore(
    themeStore.subscribe,
    themeStore.getMode,
    // Server snapshot (SSR-safe) — never executed in our SPA, but
    // React 18 requires the third argument. Returning 'system' here
    // means an SSR pre-render would emit a neutral default.
    () => 'system' as ThemeMode,
  )

  return (
    <Card
      className="sber-card"
      title={<Text strong>{t('profile.theme.title')}</Text>}
      styles={{ body: { padding: 18 } }}
    >
      <Text type="secondary" style={{ fontSize: 'var(--text-xs)', display: 'block', marginBottom: 12 }}>
        {t('profile.theme.label')}
      </Text>
      <Radio.Group
        value={mode}
        onChange={(e) => themeStore.setMode(e.target.value as ThemeMode)}
        optionType="button"
        buttonStyle="solid"
        size="middle"
      >
        <Radio.Button value="light">
          <Space size={6}>
            <BulbOutlined />
            {t('profile.theme.light')}
          </Space>
        </Radio.Button>
        <Radio.Button value="dark">
          <Space size={6}>
            <BulbFilled />
            {t('profile.theme.dark')}
          </Space>
        </Radio.Button>
        <Radio.Button value="system">
          <Space size={6}>
            <DesktopOutlined />
            {t('profile.theme.system')}
          </Space>
        </Radio.Button>
      </Radio.Group>
      <Text type="secondary" style={{ fontSize: 'var(--text-xs)', display: 'block', marginTop: 10 }}>
        {t('profile.theme.systemHint')}
      </Text>
    </Card>
  )
}
