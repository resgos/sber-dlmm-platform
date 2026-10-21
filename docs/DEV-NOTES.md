# DEV-NOTES — память между автопрогонами

Короткие устойчивые грабли сборки/окружения/деплоя. 1–3 строки на пункт, без дублей.

- `lint:all` — цепочка через `&&`: предсуществующе красный `lint:styles` (inline-styles, line-keyed baseline,
  44 нарушения на 2026-10-21) обрывает её, и `lint:i18n`/`i18n-dead`/`dates`/`api` НЕ запускаются.
  Гонять их по отдельности: `npm --prefix <W>/dlmm-user-ui run lint:i18n` и т.д.
- `lint:i18n` (check-no-cyrillic-in-tsx) считает кириллические слова по файлу: перефразировка
  захардкоженной строки длиннее исходной валит ратчет — держать число слов или выносить в t().
- antd 5.29.3 в lock обоих UI: `destroyOnClose` → `destroyOnHidden` (с 5.25), диапазон в package.json ^5.25.0.
- Docker Desktop может быть не запущен (2026-10-21: pipe dockerDesktopLinuxEngine не найден) — тогда
  статическая ветка: mvn/vitest/build без деплоя и curl; стек сам не поднимать.
