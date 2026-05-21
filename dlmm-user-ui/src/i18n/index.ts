/**
 * Sprint 8 C-4 — i18n foundation.
 * Sprint 9-DS-r4 P2-14 — EN locale + language preference store.
 *
 * <p>Wires {@code react-i18next} with Russian + English resource
 * bundles. Active language is persisted in localStorage under
 * {@code dlmm.user.language} (per-device, not per-user — same
 * rationale as themeStore).
 *
 * <p>To flip language at runtime:
 *   import i18n from '@/i18n'
 *   i18n.changeLanguage('en')
 * <p>The language toggle UI ships in Sprint 10 (header dropdown);
 * for now the bundle is loaded and ready so any en-locale demo
 * just calls {@code i18n.changeLanguage('en')} in the console.
 *
 * <p>AntD's own component strings (e.g. "Cancel" / "OK" on Modals)
 * are localised separately via {@code ConfigProvider locale={...}}
 * — wired in {@code main.tsx}. This module covers app-owned copy.
 */

import i18n from 'i18next'
import { initReactI18next } from 'react-i18next'
import ruResource from './locales/ru.json'
import enResource from './locales/en.json'

const STORAGE_KEY = 'dlmm.user.language'
const DEFAULT_LANGUAGE = 'ru'

function safeReadLanguage(): string {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    if (raw === 'ru' || raw === 'en') return raw
  } catch {
    // localStorage may be disabled — fall through to default.
  }
  return DEFAULT_LANGUAGE
}

void i18n
  .use(initReactI18next)
  .init({
    resources: {
      ru: { translation: ruResource as Record<string, unknown> },
      en: { translation: enResource as Record<string, unknown> },
    },
    lng: safeReadLanguage(),
    fallbackLng: DEFAULT_LANGUAGE,
    interpolation: {
      // React already escapes — avoid double-encode of `{{var}}` outputs.
      escapeValue: false,
    },
    // Plural rules — Russian needs one/few/many (see ru.json plural keys
    // like pools.subtitle_one / _few / _many). i18next handles this
    // automatically from the LANG code; English uses one/other.
    returnEmptyString: false,
  })

// Sprint 9-DS-r4 P2-14 — persist language flips so a reload keeps the
// user where they were. Subscribed once on init; no cleanup needed
// because i18n is a module-singleton.
i18n.on('languageChanged', (lng) => {
  try { localStorage.setItem(STORAGE_KEY, lng) } catch { /* ignore */ }
})

export default i18n
