---
name: dlmm-tick
description: Один тик полировки Sber DLMM — прогнать тесты, взять одну маленькую вещь (UI-тест предыдущей фичи / пункт бэклога / найденная мелочь), доделать до конца с живой проверкой, закоммитить в рабочую ветку. Use when пользователь запускает «/loop 1h /dlmm-tick», просит «тик полировки», «продолжай улучшать платформу» в автономном цикле.
---

Ты работаешь над Sber DLMM (демо-платформа концентрированной ликвидности, эталон UX — Meteora). Один вызов = один законченный инкремент. Отвечай и пиши UI-тексты по-русски.

ПРАВИЛА GIT
- НИКОГДА не коммить в main. Работай в текущей ветке claude/* (или worktree, если он активен). Коммит и push в remote — обязательны в конце тика.
- Интерактивный режим (не /loop): коммит только по явному слову «коммить».

ОКРУЖЕНИЕ (Git Bash)
- Java-сборка: `JAVA_HOME='C:\Users\rusgr\.jdks\corretto-21.0.4' '/c/Tools/apache-maven-3.9.6/bin/mvn.cmd' -q -pl <module> package`; для тестов модуля сперва `-pl dlmm-common install -DskipTests`.
- Фронт: `cd dlmm-user-ui && npm run build && npm run test`; линт: `npm run lint:all`.
- Стек: ~19 контейнеров, user-ui http://localhost:3001 (nginx → /api на gateway 8080). Хот-деплой jar: `docker cp <module>/target/<module>-1.0.0-SNAPSHOT.jar dlmm-<svc>:/app/app.jar && docker restart dlmm-<svc>` (ждать actuator/health UP); фронт: `docker cp dlmm-user-ui/dist/. dlmm-user-ui:/usr/share/nginx/html/`.
- JWT для API-проверок: минтить HS384 из docker/.env JWT_SECRET (демо-юзер ivanov, sub a0000000-...-0002), НЕ вводить пароли. БД: `docker exec dlmm-postgres psql -U dlmm ...`.
- Помни грабли из памяти проекта: recreate стека стирает hot-deploys ([[dlmm-stack-recreate-wipes-hotdeploys]]); суммы — raw integer ×10⁴; схема БД только через Liquibase + docker/init-db.sql.

ТИК
1. Проверь стек (docker ps + health gateway/pool-engine/fee/transaction; curl localhost:3001). Docker выключен — не поднимай сам, ограничься статикой и пометь в отчёте.
2. Прогони: mvn-тесты затронутых модулей (минимум dlmm-common), npm build+test+lint в dlmm-user-ui. Красное — чини в первую очередь.
3. Выбери ОДНУ маленькую вещь: UI-тест предыдущей фичи → найденная мелочь, ИЛИ верхний незакрытый пункт docs/AUDIT-BACKLOG-2026-06-03.md. Ротация областей: своп, ликвидность/позиции, комиссии, транзакции, нотификации, EN-i18n, мобильная вёрстка, админ-UI.
4. Сделай правку, собери, задеплой (если стек жив) и ПРОВЕРЬ ВЖИВУЮ: curl API через gateway и/или UI на :3001 (для canvas/recharts-страниц скриншоты не работают — проверяй через DOM/API). Не заявляй о готовности без реальной проверки. Никаких X/Y-плейсхолдеров — только имена токенов; цифры демо должны быть реалистичными.
5. Рискованные сквозные зоны (auth, схема БД, money-path) — не трогать, только флагать в бэклоге.
6. Коммит (тесты зелёные) + push. Устойчивую граблю тика допиши в docs/DEV-NOTES.md.
7. Отчёт 3–5 строк: что сделано, как проверено, статус стека. Тик без коммита = сбой — назови причину одной строкой.
