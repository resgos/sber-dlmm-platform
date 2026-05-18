/**
 * Sprint 8 C-4 — i18n foundation.
 *
 * <p>Wires {@code react-i18next} with a Russian-only resource bundle.
 * English translation = Sprint 9+ — this commit lays the plumbing so
 * adding {@code en.json} is one file change away.
 *
 * <p>Why we did i18n in Sprint 8 even though we're RU-only today:
 * extracting strings now (while pages still small) is much cheaper
 * than later. Audit C-4 flagged "no language toggle" as a critical
 * gap; this commit closes the infrastructural piece — actual EN
 * translation labour is Sprint 9 work for the marketing team.
 *
 * <p>AntD's own component strings (e.g. "Cancel" / "OK" on Modals)
 * are localised separately via {@code ConfigProvider locale={ruRU}}
 * — wired in {@code main.tsx}. This module covers app-owned copy.
 */

import i18n from 'i18next'
import { initReactI18next } from 'react-i18next'
import ruResource from './locales/ru.json'

// The current language is hard-coded to 'ru' today. When EN ships
// in Sprint 9, the language toggle becomes:
//   i18n.changeLanguage(authStore.getPreferences().language ?? 'ru')
// triggered by a header dropdown in UserLayout.
const DEFAULT_LANGUAGE = 'ru'

void i18n
  .use(initReactI18next)
  .init({
    resources: {
      ru: { translation: ruResource as Record<string, unknown> },
    },
    lng: DEFAULT_LANGUAGE,
    fallbackLng: DEFAULT_LANGUAGE,
    interpolation: {
      // React already escapes — avoid double-encode of `{{var}}` outputs.
      escapeValue: false,
    },
    // Plural rules — Russian needs one/few/many (see ru.json plural keys
    // like pools.subtitle_one / _few / _many). i18next handles this
    // automatically from the LANG code.
    returnEmptyString: false,
  })

export default i18n
