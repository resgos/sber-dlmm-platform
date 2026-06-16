import { describe, it, expect } from 'vitest'
import { createInstance } from 'i18next'
import ru from '../i18n/locales/ru.json'
import en from '../i18n/locales/en.json'

/**
 * Verifies the NotificationBell strings (previously hardcoded Russian) resolve
 * through i18n — so an EN user sees English in the header bell, not Cyrillic.
 *
 * Uses an ISOLATED i18next instance per language (not the shared app singleton)
 * so changing language here can't leak into other test files.
 */
function makeI18n(lng: 'ru' | 'en') {
  const inst = createInstance()
  inst.init({
    lng,
    resources: { ru: { translation: ru }, en: { translation: en } },
    interpolation: { escapeValue: false },
  })
  return inst
}

describe('NotificationBell i18n', () => {
  it('resolves the bell UI strings in English', () => {
    const { t } = makeI18n('en')
    expect(t('notifications.title')).toBe('Notifications')
    expect(t('notifications.markAll')).toBe('Mark all read')
    expect(t('notifications.empty')).toBe('No notifications')
    expect(t('notifications.goToPosition')).toBe('Go to position')
    expect(t('notifications.ariaBellUnread', { count: 5 })).toBe('Notifications (5 unread)')
  })

  it('resolves category labels in both languages', () => {
    const en$ = makeI18n('en')
    expect(en$.t('notifications.types.MARGIN_CALL')).toBe('🔴 Margin call')
    expect(en$.t('notifications.types.FEE_ACCRUED')).toBe('Fees')
    const ru$ = makeI18n('ru')
    expect(ru$.t('notifications.types.MARGIN_CALL')).toBe('🔴 Маржин-колл')
    expect(ru$.t('notifications.types.FEE_ACCRUED')).toBe('Комиссии')
  })

  it('falls back to a readable label for an unknown category', () => {
    const { t } = makeI18n('en')
    expect(t('notifications.types.MYSTERY_EVENT', { defaultValue: 'MYSTERY_EVENT'.replace(/_/g, ' ') }))
      .toBe('MYSTERY EVENT')
  })
})
