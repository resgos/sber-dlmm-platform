# Runbook — WAL-G Disaster Recovery

> Sprint 10+ production runbook. Companion к
> `docs/OPS-DB-BACKUP-RUNBOOK.md` (overview) и `TD-6` в
> `docs/BACKLOG-2026-05-21.md`.
>
> Этот документ — что SRE on-call делает руками, когда (а) backup
> сегодня ночью не отработал, (б) нужно проверить, что backup
> вообще работает, (в) Postgres data dir утрачен и кластер надо
> поднимать с нуля.
>
> **НЕ для дев-машин.** Дев использует `pg-snapshot.sh` + `dr-drill.sh`
> (см. OPS-DB-BACKUP-RUNBOOK.md). Этот файл предполагает, что у вас
> есть Sber Cloud Object Storage bucket с правами, sidecar
> поднят на хосте, и compose-override `docker-compose.walg.yml`
> активен.

---

## TL;DR — что вообще происходит

WAL-G делает две вещи:

1. **`backup-push`** — раз в сутки сидит cron в sidecar-контейнере
   `dlmm-walg`, в 02:30 UTC дёргает `walg-backup.sh`, который
   `docker exec`'ит `wal-g backup-push /var/lib/postgresql/data`
   против `dlmm-postgres`. Получается self-consistent snapshot
   data dir, заархивированный в S3.
2. **`wal-push`** *(будущий PR)* — Postgres archiver запускается на
   каждый закрытый WAL-сегмент и пушит его в S3. Это даёт
   point-in-time recovery с RPO ~1 минута. В MVP этого ещё нет
   (см. TODO в `walg-backup.sh`).

В S3 структура такая:
```
s3://sber-dlmm-prod-backups/cluster1/
├── basebackups_005/
│   ├── base_000000010000000000000007/   ← один backup
│   ├── base_000000010000000000000017/
│   └── ...
└── wal_005/                              ← пусто до follow-up PR
    └── 000000010000000000000007.lz4
```

---

## Regular operation — что должно происходить ежедневно

### Как понять, что cron отработал

```bash
# Sidecar логи (cron + walg-backup.sh output)
docker logs dlmm-walg --tail 200

# Только последний backup-run
docker exec dlmm-walg tail -200 /var/log/walg-cron.log

# Каталог backup'ов в S3 (показывает все base backups + время)
docker exec dlmm-walg wal-g backup-list
```

Ожидаемый успешный output `walg-backup.sh`:

```
[walg-backup] 2026-05-25T02:30:01Z loading env from /scripts/../.env.walg
[walg-backup] 2026-05-25T02:30:01Z preflight OK: container=dlmm-postgres pgdata=/var/lib/postgresql/data
[walg-backup] 2026-05-25T02:30:01Z   WALG_S3_PREFIX=s3://sber-dlmm-prod-backups/cluster1
[walg-backup] 2026-05-25T02:30:01Z starting wal-g backup-push (this can take minutes for large clusters)
[walg-backup] 2026-05-25T02:31:47Z backup-push complete in 106s
[walg-backup] 2026-05-25T02:31:48Z SUCCESS
```

Машино-парсинговые маркеры: `SUCCESS` или `FAILURE` в конце.

### Alarm threshold

| Условие | Severity | Что делать |
|---|---|---|
| Нет `SUCCESS` в логе за 26 часов | warning | См. "Backup failed" ниже |
| Нет `SUCCESS` за 48 часов | critical | Эскалация — RPO деградирует, последние сутки не покрыты. |
| `backup-list` пуст | critical | S3 креды отозваны или bucket удалён. |

В Sprint 11+ (см. TODO в OPS-DB-BACKUP-RUNBOOK.md) появится
Prometheus экспортер с метрикой `walg_last_backup_age_seconds` и
Grafana алерт. Сегодня — мониторим вручную через `docker logs`
+ ручной grep `FAILURE` в централизованном лог-агрегаторе
(Loki/Splunk).

---

## Сценарий 1 — "Backup failed" (cron отработал, но `FAILURE` в логе)

### Диагностика

```bash
# 1. Что было в последнем run'е?
docker exec dlmm-walg tail -50 /var/log/walg-cron.log

# 2. Постгрес сам в порядке?
docker exec dlmm-postgres pg_isready -U dlmm -d dlmm

# 3. wal-g видит S3?
docker exec dlmm-walg wal-g backup-list 2>&1 | head -20

# 4. Креды живые? (вернёт 200 если доступ есть)
docker exec dlmm-walg sh -c 'aws --endpoint "$AWS_ENDPOINT" s3 ls "$WALG_S3_PREFIX" 2>&1 | head -5'
```

### Типичные причины

| Симптом в логе | Причина | Фикс |
|---|---|---|
| `AccessDenied` или `403` | Истекли S3 креды | Сгенерить новый AccessKey, `$EDITOR docker/.env.walg`, `docker restart dlmm-walg`. |
| `NoSuchBucket` | Bucket удалили или префикс опечатан | Сверить `WALG_S3_PREFIX` с актуальным bucket. |
| `connection refused 127.0.0.1:5432` | Postgres перезагрузился, sidecar потерял exec-сессию | `docker restart dlmm-walg` (sidecar поднимется, следующий cron-run пройдёт). |
| `archive_command failed` | После follow-up PR с `archive_mode=on` — Postgres archiver не смог писать WAL | См. отдельный runbook про archive_command (создаётся в follow-up PR). |
| `no space left on device` в логе wal-g | S3 bucket за квотой или upload buffer на хосте переполнен | Поднять квоту / удалить старые backups через lifecycle rule. |

### Ручной retry

```bash
# Запустить backup-push вне cron'а
docker exec dlmm-walg /scripts/walg-backup.sh
```

Если ручной запуск отработал — cron восстановится автоматически на
следующий 02:30 UTC.

---

## Сценарий 2 — Restore drill (quarterly)

Цель — убедиться, что backup'ы в S3 не только существуют, но и
**воспроизводимы**. Самый частый failure mode — backup пишется без
ошибок, но restore не работает (corrupted, неполный WAL, FK
нарушения после replay).

### Подготовка — staging кластер

Drill делается ТОЛЬКО на staging (или dedicated DR-host). НЕ
ронять production контейнер для drill — это инцидент, а не
учения.

```bash
# 1. На staging-хосте: остановить и убить production postgres data
docker stop dlmm-postgres
docker volume rm docker_postgres-data    # !!! только на staging

# 2. Поднять новый пустой postgres (init scripts создадут схему)
docker-compose -f docker-compose.yml -f docker-compose.walg.yml up -d postgres
docker-compose -f docker-compose.yml -f docker-compose.walg.yml up -d dlmm-walg

# 3. Дождаться готовности
until docker exec dlmm-postgres pg_isready -U dlmm; do sleep 1; done
```

### Сам restore

```bash
# 4. Остановить postgres (restore требует stopped target)
docker stop dlmm-postgres

# 5. Запустить restore — sidecar делает backup-fetch в data volume
# (он у нас shared mount, см. docker-compose.walg.yml)
docker exec dlmm-walg /scripts/walg-restore.sh \
    --backup-name LATEST \
    --force

# 6. Стартануть postgres — увидит recovery.signal, replay WAL
docker start dlmm-postgres

# 7. Tail logs пока не увидим "database system is ready"
docker logs -f dlmm-postgres
# Ctrl-C когда увидим: "database system is ready to accept connections"
```

### Верификация

```bash
# 8. Row counts — сверить с baseline из source кластера
docker exec dlmm-postgres psql -U dlmm -d dlmm -c "
    SELECT 'users' tbl, count(*) FROM users
    UNION ALL SELECT 'tokens', count(*) FROM tokens
    UNION ALL SELECT 'liquidity_pools', count(*) FROM liquidity_pools
    UNION ALL SELECT 'lp_positions', count(*) FROM lp_positions
    UNION ALL SELECT 'transactions', count(*) FROM transactions
    ORDER BY 1;
"

# 9. FK integrity — должно вернуть 0 нарушений
docker exec dlmm-postgres psql -U dlmm -d dlmm -c "
    SELECT conname, pg_get_constraintdef(oid)
    FROM pg_constraint
    WHERE NOT convalidated;
"

# 10. Liquibase changelog — должен подняться без MIGRATION_FAILED
docker-compose up -d dlmm-pool-engine
docker logs dlmm-pool-engine | grep -i liquibase | tail -20
```

### Pass / fail критерии

| Метрика | Pass | Fail action |
|---|---|---|
| Row counts (5 критичных таблиц) | ±0% от baseline | Investigate WAL gap; backup может быть corrupt. |
| Liquibase validation | `Successfully validated N changesets` | Schema drift между source и restore — проверь, что используешь правильный backup (по времени). |
| Сервисы поднялись | `dlmm-pool-engine` health `UP` | См. логи сервиса — может быть FK violation в seed data. |

Зафиксировать результаты в `docs/runbooks/walg-dr-drill-log.md`
(создаётся при первом drill).

---

## Сценарий 3 — Full-DR (Postgres data dir утрачен, кластер заново)

Это худший случай: хост сгорел, named volume удалён, бэкап с
прошлой ночи — единственное, что есть. RTO target = 30 минут.

### Шаги (на свежем хосте)

```bash
# 1. Клонировать репо
git clone <repo> /opt/dlmm && cd /opt/dlmm/docker

# 2. Восстановить env-файлы из секрет-стораджа
# (Vault / Sber KMS — см. OPS-SECRETS-RUNBOOK.md)
cat > .env << 'EOF'
DB_USER=dlmm
DB_PASSWORD=<из Vault>
JWT_SECRET=<из Vault>
EOF

cat > .env.walg << 'EOF'
WALG_S3_PREFIX=s3://sber-dlmm-prod-backups/cluster1
AWS_ACCESS_KEY_ID=<из Vault>
AWS_SECRET_ACCESS_KEY=<из Vault>
AWS_ENDPOINT=https://hb.bizmrg.com
EOF

# 3. Поднять только postgres + sidecar (не вся стек, чтобы сервисы
#    не начали бить по пустой DB)
docker-compose -f docker-compose.yml -f docker-compose.walg.yml up -d postgres dlmm-walg

# 4. Дождаться, пока postgres init-db.sql пройдёт
until docker exec dlmm-postgres pg_isready -U dlmm; do sleep 1; done

# 5. Список доступных backup'ов
docker exec dlmm-walg wal-g backup-list

# 6. Остановить postgres перед restore
docker stop dlmm-postgres

# 7. Restore из последнего backup'а
docker exec dlmm-walg /scripts/walg-restore.sh --backup-name LATEST --force

# 8. Стартануть postgres — replay
docker start dlmm-postgres

# 9. Дождаться "ready to accept connections"
docker logs -f dlmm-postgres

# 10. Спарринг чек — пройтись по критичным таблицам
docker exec dlmm-postgres psql -U dlmm -d dlmm -c "
    SELECT now(), count(*) FROM users;
    SELECT max(created_at) FROM transactions;
"

# 11. Если данные на месте — поднять остальные сервисы
docker-compose -f docker-compose.yml -f docker-compose.walg.yml up -d
```

### Post-incident checklist

- [ ] Зафиксирован RTO (от detection до сервисов up): `___` минут.
- [ ] Зафиксирован RPO (lag между последним backup'ом и моментом
      crash'а): `___` часов. В MVP это до 24h (cron 02:30 UTC) —
      после внедрения WAL streaming упадёт до минут.
- [ ] Уведомлены: PO, IT-lead, security-officer.
- [ ] Создан incident retrospective doc в `docs/incidents/`.
- [ ] Если потеряны транзакции после последнего backup'а —
      выгрузить outbox events из Kafka topic (retention 7 дней),
      попытаться реконструировать состояние balance'ов.

---

## Monitoring + observability

### Метрики, которые должны быть в Grafana (TODO Sprint 11+)

```
walg_last_backup_age_seconds         < 26 * 3600   (warning)
walg_last_backup_age_seconds         < 48 * 3600   (critical)
walg_last_backup_size_bytes          > 0
walg_s3_upload_errors_total          rate ~ 0
postgres_archive_pending_wal_files   < 10          (после follow-up PR)
```

Сейчас (MVP) это grep по `docker logs dlmm-walg | grep SUCCESS` —
переносим в правильные метрики после первого Prometheus spike.

### Полезные команды для on-call

```bash
# Все backup'ы в S3 с метаданными (время, size, WAL begin/end)
docker exec dlmm-walg wal-g backup-list --detail

# Удалить backup'ы старше N дней (lifecycle rule в bucket делает
# то же самое, но можно дёрнуть руками для cleanup)
docker exec dlmm-walg wal-g delete retain 14 --confirm

# Проверить, что WAL archiving работает (после follow-up PR)
docker exec dlmm-postgres psql -U dlmm -d dlmm -c "
    SELECT * FROM pg_stat_archiver;
"

# Размер S3 bucket (через aws-cli внутри sidecar)
docker exec dlmm-walg sh -c '
    aws --endpoint "$AWS_ENDPOINT" s3 ls --summarize --recursive "$WALG_S3_PREFIX" \
    | tail -3
'
```

---

## Related

- `docker/scripts/walg-backup.sh` — daily cron job
- `docker/scripts/walg-restore.sh` — restore script (this runbook)
- `docker/docker-compose.walg.yml` — opt-in sidecar override
- `docker/.env.walg.example` — env template
- `docs/OPS-DB-BACKUP-RUNBOOK.md` — overview of dev + prod backup
- `docs/OPS-SECRETS-RUNBOOK.md` — где живут creds для `.env.walg`
- `docker/scripts/dr-drill.sh` — dev-grade drill (для local validation)
