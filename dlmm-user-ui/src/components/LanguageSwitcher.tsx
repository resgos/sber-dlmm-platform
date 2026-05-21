import { useTranslation } from 'react-i18next'
import { Button, Dropdown } from 'antd'
import { GlobalOutlined } from '@ant-design/icons'
import type { MenuProps } from 'antd'

/**
 * Sprint 9-DS-r4 P2-14 — language picker in the header.
 *
 * Switches between Russian and English via i18n.changeLanguage.
 * Choice persists to localStorage (handled inside `@/i18n` on the
 * `languageChanged` event). Compact dropdown so the header stays
 * tight; small label ("RU" / "EN") shows the current state at a
 * glance.
 */
export default function LanguageSwitcher() {
  const { i18n } = useTranslation()
  const current = i18n.language?.startsWith('en') ? 'en' : 'ru'

  const items: MenuProps['items'] = [
    { key: 'ru', label: 'Русский' },
    { key: 'en', label: 'English' },
  ]

  return (
    <Dropdown
      menu={{
        items,
        selectedKeys: [current],
        onClick: ({ key }) => {
          // Avoid no-op re-renders if the user picks the active one.
          if (key !== current) void i18n.changeLanguage(key)
        },
      }}
      trigger={['click']}
      placement="bottomRight"
    >
      <Button
        type="text"
        size="small"
        icon={<GlobalOutlined />}
        aria-label="Сменить язык / Change language"
        style={{ fontWeight: 600 }}
      >
        {current === 'ru' ? 'RU' : 'EN'}
      </Button>
    </Dropdown>
  )
}
