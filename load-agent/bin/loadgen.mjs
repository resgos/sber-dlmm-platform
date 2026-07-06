#!/usr/bin/env node
/**
 * loadgen — REST load generator без зависимостей (Node >= 18).
 *
 * Спроектирован как «исполнительное ядро» для ИИ-агента нагрузочного тестирования:
 * вся математика, валидация и интерпретация результатов живёт здесь, а не в LLM.
 *
 * Команды:
 *   node loadgen.mjs probe <baseUrl> [path ...]      — проверить доступность цели
 *   node loadgen.mjs init [--out scenario.json]      — создать шаблон сценария
 *   node loadgen.mjs validate <scenario.json>        — проверить сценарий
 *   node loadgen.mjs run <scenario.json> [опции]     — прогнать нагрузку
 *
 * Опции run:
 *   --smoke              функциональная проверка: каждый запрос по 1 разу, последовательно
 *   --vus N              переопределить число виртуальных пользователей
 *   --duration N         переопределить длительность (сек)
 *   --base-url URL       переопределить baseUrl сценария
 *   --out FILE           куда писать JSON-результат (по умолчанию ./loadgen-result.json)
 *   --allow-writes       разрешить не-GET запросы (нужен ещё allowWrites:true в сценарии)
 *   --confirm-external   разрешить нагрузку на внешний (не локальный/приватный) хост
 *   --quiet              не печатать прогресс каждые 5 секунд
 *
 * Коды выхода: 0 = PASS, 1 = ошибка конфигурации/использования, 2 = FAIL (пороги/ошибки), 3 = цель недоступна
 */

import { readFileSync, writeFileSync, existsSync, mkdirSync } from 'node:fs';
import { dirname, join, isAbsolute, basename } from 'node:path';
import { randomUUID } from 'node:crypto';
import { performance, monitorEventLoopDelay } from 'node:perf_hooks';
import { Worker, isMainThread, parentPort, workerData } from 'node:worker_threads';
import { spawn } from 'node:child_process';
import { fileURLToPath, pathToFileURL } from 'node:url';
import os from 'node:os';

const VERSION = '1.18.0';
const MAX_VUS = 200;
const MAX_DURATION_SEC = 900;
const DEFAULT_TIMEOUT_MS = 10_000;

// ─────────────────────────────────────────────── утилиты ──

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

function pct(sortedAsc, p) {
  if (!sortedAsc.length) return 0;
  const i = Math.min(sortedAsc.length - 1, Math.max(0, Math.ceil((p / 100) * sortedAsc.length) - 1));
  return sortedAsc[i];
}

// гистограмма латентности: границы в мс; складывается точно между машинами → корректная агрегация (merge)
const HIST_BOUNDS = [1, 2, 3, 5, 7, 10, 15, 20, 25, 30, 40, 50, 60, 75, 90, 100, 125, 150, 200, 250, 300, 400, 500, 750, 1000, 1500, 2000, 3000, 5000, 7500, 10000, 15000, 20000, 30000, 60000, 120000];
const HIST_BOUNDS_V = 1; // версия сетки бакетов — БУМП при любом изменении HIST_BOUNDS (merge проверяет совпадение)

/** sortedAsc → массив counts длиной HIST_BOUNDS.length+1 (последний бакет = >120000мс). */
function histogram(sortedAsc) {
  const counts = new Array(HIST_BOUNDS.length + 1).fill(0);
  let bi = 0;
  for (const v of sortedAsc) {
    while (bi < HIST_BOUNDS.length && v > HIST_BOUNDS[bi]) bi++;
    counts[bi]++;
  }
  return counts;
}

/** Перцентиль из гистограммы (верхняя граница бакета, консервативно). Для слияния прогонов. */
function percentileFromHistogram(counts, p) {
  const total = counts.reduce((a, b) => a + b, 0);
  if (!total) return 0;
  const target = Math.max(1, Math.ceil((p / 100) * total));
  let cum = 0;
  for (let i = 0; i < counts.length; i++) {
    cum += counts[i];
    if (cum >= target) return HIST_BOUNDS[Math.min(i, HIST_BOUNDS.length - 1)];
  }
  return HIST_BOUNDS[HIST_BOUNDS.length - 1];
}

function fmtMs(x) {
  return x >= 100 ? String(Math.round(x)) : x.toFixed(1);
}

function levenshtein(a, b) {
  const m = a.length, n = b.length;
  const d = Array.from({ length: m + 1 }, (_, i) => [i, ...Array(n).fill(0)]);
  for (let j = 0; j <= n; j++) d[0][j] = j;
  for (let i = 1; i <= m; i++)
    for (let j = 1; j <= n; j++)
      d[i][j] = Math.min(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + (a[i - 1] === b[j - 1] ? 0 : 1));
  return d[m][n];
}

function suggestKey(key, known) {
  let best = null, bestD = 3;
  for (const k of known) {
    const dd = levenshtein(key.toLowerCase(), k.toLowerCase());
    if (dd < bestD) { bestD = dd; best = k; }
  }
  return best;
}

/**
 * Извлечение по dot-пути с поддержкой [*] и [N]: "content[*].id", "data.accessToken", "[0].id".
 * opts.keepNulls=true — в режиме фана [*] НЕ выкидывать null/отсутствующие листья, а сохранять их слотом
 * (нужно jsonPathEquals: "все значения = X" должно ловить битый элемент со status:null, а не молча его терять).
 */
function extractPath(root, path, opts = {}) {
  const keepNulls = opts.keepNulls === true;
  let nodes = [root];
  let fan = false;
  for (const part of String(path).split('.')) {
    const m = part.match(/^([\w$-]*)(?:\[(\*|\d+)\])?$/);
    if (!m) throw new Error(`некорректный extract-путь "${path}" (сегмент "${part}")`);
    const [, key, idx] = m;
    nodes = nodes.flatMap((n) => {
      if (n == null) return fan && keepNulls ? [null] : [];
      let v = key === '' ? n : n[key];
      if (v == null) return fan && keepNulls ? [null] : [];
      if (idx === undefined) return [v];
      if (!Array.isArray(v)) return [];
      if (idx === '*') { fan = true; return v; }
      const el = v[Number(idx)];
      return el == null ? [] : [el];
    });
  }
  return fan ? nodes : nodes[0];
}

const BUILTIN_PLACEHOLDERS = ['uuid', 'ts'];

/** used (опционально) — объект, в который записываются выбранные значения переменных: { имя: значение } */
function renderTemplate(str, vars, used) {
  return String(str).replace(/\{\{([^}]+)\}\}/g, (_, raw) => {
    const expr = raw.trim();
    if (expr === 'uuid') return randomUUID();
    if (expr === 'ts') return String(Date.now());
    const ri = expr.match(/^randInt:(-?\d+)-(-?\d+)$/);
    if (ri) {
      const a = Number(ri[1]), b = Number(ri[2]);
      return String(a + Math.floor(Math.random() * (b - a + 1)));
    }
    if (vars[expr] !== undefined) {
      // одно значение на HTTP-вызов: повторное вхождение {{var}} (в path и body) получает тот же выбор
      if (used && used[expr] !== undefined) return used[expr];
      const v = vars[expr];
      const chosen = String(Array.isArray(v) ? v[Math.floor(Math.random() * v.length)] : v);
      if (used) used[expr] = chosen;
      return chosen;
    }
    throw new Error(`неизвестный placeholder {{${expr}}}`);
  });
}

/** CSV-парсер (RFC 4180): кавычки, запятые и переводы строк внутри закавыченных полей. Возвращает массив записей. */
function parseCsv(text) {
  const rows = [];
  let row = [];
  let cur = '';
  let q = false;
  let i = 0;
  while (i < text.length) {
    const ch = text[i];
    if (q) {
      if (ch === '"') {
        if (text[i + 1] === '"') { cur += '"'; i++; } else q = false;
      } else cur += ch;
    } else if (ch === '"') q = true;
    else if (ch === ',') { row.push(cur.trim()); cur = ''; }
    else if (ch === '\n' || ch === '\r') {
      if (ch === '\r' && text[i + 1] === '\n') i++;
      row.push(cur.trim()); cur = '';
      if (row.length > 1 || row[0] !== '') rows.push(row);
      row = [];
    } else cur += ch;
    i++;
  }
  if (q) throw new Error('незакрытая кавычка (файл обрывается внутри закавыченного поля)');
  row.push(cur.trim());
  if (row.length > 1 || row[0] !== '') rows.push(row);
  return rows;
}

/** Значения переменной из файла: .txt (строки), .csv (колонка), .json (extract-путь) */
function loadVarFile(scnDir, name, def) {
  if (typeof def.file !== 'string' || !def.file) throw new Error(`vars.${name}: "file" должен быть непустой строкой-путём — прогоните validate`);
  const p = isAbsolute(def.file) ? def.file : join(scnDir || '.', def.file);
  let raw;
  try { raw = readFileSync(p, 'utf8'); } catch (e) {
    throw new Error(`vars.${name}: не удалось прочитать файл ${p}: ${e.message} (путь считается от папки сценария)`);
  }
  if (raw.charCodeAt(0) === 0xFEFF) raw = raw.slice(1); // UTF-8 BOM (PowerShell Out-File и т.п.)
  let vals;
  if (/\.csv$/i.test(p)) {
    let rows;
    try { rows = parseCsv(raw); } catch (e) { throw new Error(`vars.${name}: CSV ${p}: ${e.message}`); }
    if (rows.length < 2) throw new Error(`vars.${name}: CSV ${p} пуст — нужен заголовок и хотя бы одна строка данных`);
    const header = rows[0];
    const ci = header.indexOf(def.column);
    if (ci < 0) throw new Error(`vars.${name}: в CSV нет колонки "${def.column}". Доступные: ${header.join(', ')}`);
    vals = rows.slice(1).map((r) => r[ci]).filter((v) => v !== undefined && v !== '');
  } else if (/\.json$/i.test(p)) {
    let json;
    try { json = JSON.parse(raw); } catch (e) { throw new Error(`vars.${name}: файл ${p} — не валидный JSON: ${e.message}`); }
    let v = def.extract ? extractPath(json, def.extract) : json;
    if (!Array.isArray(v)) v = v == null ? [] : [v];
    vals = v.filter((x) => x != null && typeof x !== 'object').map(String);
    if (!vals.length) throw new Error(`vars.${name}: из ${p}${def.extract ? ` по пути "${def.extract}"` : ''} не получился список скалярных значений`);
  } else {
    vals = raw.split(/\r?\n/).map((l) => l.trim()).filter((l) => l && !l.startsWith('#'));
  }
  if (!vals.length) throw new Error(`vars.${name}: файл ${p} не дал ни одного значения`);
  return vals;
}

function listPlaceholders(str) {
  const out = [];
  for (const m of String(str).matchAll(/\{\{([^}]+)\}\}/g)) out.push(m[1].trim());
  return out;
}

function isPrivateHost(hostname) {
  const h = hostname.toLowerCase();
  if (h === 'localhost' || h === '::1' || h.endsWith('.local') || h === 'host.docker.internal') return true;
  const m = h.match(/^(\d+)\.(\d+)\.(\d+)\.(\d+)$/);
  if (!m) return false;
  const [a, b] = [Number(m[1]), Number(m[2])];
  if (a === 127 || a === 10) return true;
  if (a === 192 && b === 168) return true;
  if (a === 172 && b >= 16 && b <= 31) return true;
  return false;
}

function errKind(e) {
  if (e?.name === 'TimeoutError' || e?.name === 'AbortError') return 'timeout';
  const code = e?.cause?.code || e?.code || '';
  if (/ECONNREFUSED|ECONNRESET|ENOTFOUND|EHOSTUNREACH|EAI_AGAIN|UND_ERR_SOCKET|ETIMEDOUT/.test(code)) return 'conn';
  return 'other';
}

function errText(e) {
  return String(e?.cause?.code || e?.cause?.message || e?.message || e).slice(0, 160);
}

// ─────────────────────────────────────────────── парсинг CLI ──

function parseArgs(argv) {
  const flags = {};
  const positional = [];
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (a.startsWith('--')) {
      const name = a.slice(2);
      const needsValue = ['out', 'vus', 'duration', 'base-url', 'max-rps', 'workers', 'top', 'format', 'baseline', 'max-p95-regression-pct', 'max-error-increase-pp', 'junit', 'md', 'html', 'preset'].includes(name);
      if (needsValue) {
        flags[name] = argv[++i];
        if (flags[name] === undefined) die(1, `Опции --${name} нужно значение. Пример: --${name} <значение>`);
      } else {
        flags[name] = true;
      }
    } else {
      positional.push(a);
    }
  }
  return { cmd: positional[0], positional: positional.slice(1), flags };
}

function die(code, msg) {
  console.error(msg);
  process.exit(code);
}

// ─────────────────────────────────────────────── validate ──

const KNOWN_ROOT_KEYS = ['name', 'kind', 'baseUrl', 'sql', 'produce', 'verify', 'pipeline', 'headers', 'timeoutMs', 'auth', 'vars', 'requests', 'flows', 'load', 'thresholds', 'allowWrites', 'monitor', 'setup', 'teardown'];
const KNOWN_REQ_KEYS = ['name', 'method', 'path', 'sql', 'weight', 'body', 'headers', 'expectStatus', 'checks', 'capture', 'bodyType'];
const KNOWN_FLOW_KEYS = ['name', 'weight', 'steps'];
const KNOWN_CHECK_KEYS = ['status', 'maxMs', 'notEmpty', 'bodyContains', 'jsonPath', 'jsonPathEquals', 'minRows'];
const KNOWN_SQL_KEYS = ['driver', 'command', 'flags', 'init', 'stmtTimeout', 'mark', 'timeRegex', 'rowsRegex', 'errorRegex', 'statusRegex', 'label'];
const KNOWN_LOAD_KEYS = ['vus', 'durationSec', 'rampUpSec', 'thinkTimeMs', 'maxRps', 'workers', 'stages', 'warmupSec'];
const HTTP_METHODS = ['GET', 'HEAD', 'OPTIONS', 'POST', 'PUT', 'PATCH', 'DELETE'];
const WRITE_METHODS = ['POST', 'PUT', 'PATCH', 'DELETE'];

/**
 * Подстановка переменных окружения в строки сценария: ${VAR} и ${VAR:-default}.
 * Секреты (пароли/токены) не хранятся в JSON — инжектятся из env в CI. $${VAR} — литерал ${VAR}.
 * Возвращает { missing: string[] } — имена незаданных переменных без дефолта.
 */
function resolveEnvInScenario(node, env, missing) {
  const subst = (s) => s.replace(/(\$?)\$\{([A-Za-z_]\w*)(?::-([^}]*))?\}/g, (full, dollar, name, def) => {
    if (dollar === '$') return full.slice(1); // $${VAR} → литерал ${VAR}
    const v = env[name];
    if (v !== undefined && v !== '') return v;
    if (def !== undefined) return def;
    missing.add(name);
    return full;
  });
  if (Array.isArray(node)) {
    for (let i = 0; i < node.length; i++) {
      if (typeof node[i] === 'string') node[i] = subst(node[i]);
      else if (node[i] && typeof node[i] === 'object') resolveEnvInScenario(node[i], env, missing);
    }
  } else if (node && typeof node === 'object') {
    for (const k of Object.keys(node)) {
      if (typeof node[k] === 'string') node[k] = subst(node[k]);
      else if (node[k] && typeof node[k] === 'object') resolveEnvInScenario(node[k], env, missing);
    }
  }
  return missing;
}

function loadScenario(file, overrides = {}) {
  if (!existsSync(file)) die(1, `Файл сценария не найден: ${file}\nСоздайте его: node loadgen.mjs init --out ${file}`);
  let raw;
  try { raw = readFileSync(file, 'utf8'); } catch (e) { die(1, `Не удалось прочитать ${file}: ${e.message}`); }
  let scn;
  try { scn = JSON.parse(raw); } catch (e) {
    die(1, `Файл ${file} — не валидный JSON: ${e.message}\nЧастые причины: лишняя запятая после последнего элемента, комментарии //, одинарные кавычки.`);
  }
  // сценарий обязан быть объектом — иначе CLI-override'ы (--vus/--base-url) и подстановка env
  // упали бы сырым TypeError вместо диагностируемого сообщения (критично для ИИ-агента, парсящего вывод)
  if (typeof scn !== 'object' || scn === null || Array.isArray(scn)) die(1, 'Сценарий должен быть JSON-объектом {...}');
  // CLI-override'ы env-шаблонных полей применяем ДО подстановки: напр. baseUrl:"${TARGET_URL}" + --base-url —
  // тогда TARGET_URL не считается пропущенной, и «шаблон URL + инъекция из CLI» работает без экспорта переменной
  if (overrides.baseUrl) scn.baseUrl = overrides.baseUrl;
  // подстановка ${ENV_VAR} из окружения (секреты не в файле)
  const missing = resolveEnvInScenario(scn, process.env, new Set());
  if (missing.size) die(1, `Не заданы переменные окружения, на которые ссылается сценарий: ${[...missing].join(', ')}.\n  Задайте их (например: ${[...missing][0]}=... node loadgen.mjs ...) или укажите дефолт в сценарии: "\${${[...missing][0]}:-значение}".`);
  scn._dir = dirname(file) || '.';
  return scn;
}

/** Возвращает { errors: string[], warnings: string[] }. Мутирует scn: нормализует дефолты. */
function validateScenario(scn) {
  const errors = [];
  const warnings = [];
  const push = (arr, s) => arr.push(s);

  if (typeof scn !== 'object' || scn === null || Array.isArray(scn)) {
    return { errors: ['Сценарий должен быть JSON-объектом {...}'], warnings };
  }

  for (const k of Object.keys(scn)) {
    if (k.startsWith('_')) continue; // _comment и т.п. игнорируются
    if (!KNOWN_ROOT_KEYS.includes(k)) {
      const s = suggestKey(k, KNOWN_ROOT_KEYS);
      push(warnings, `Неизвестный ключ "${k}" на верхнем уровне${s ? ` — возможно, имелось в виду "${s}"` : ''} (будет проигнорирован)`);
    }
  }

  // kind — транспорт: "http" (по умолч.) | "sql" (нагрузка на СУБД) | "pipeline" (сквозной лаг Kafka→PG/Ignite)
  scn.kind = scn.kind ?? 'http';
  if (!['http', 'sql', 'pipeline'].includes(scn.kind)) push(errors, `kind должен быть "http" | "sql" | "pipeline", сейчас: ${JSON.stringify(scn.kind)}`);

  // baseUrl — только для HTTP (у SQL/pipeline цель = команды CLI-клиентов)
  if (scn.kind === 'http') {
    if (!scn.baseUrl) push(errors, 'Нет обязательного поля "baseUrl". Пример: "baseUrl": "http://localhost:8080"');
    else {
      try {
        const u = new URL(scn.baseUrl);
        if (u.protocol !== 'http:' && u.protocol !== 'https:') {
          push(errors, `baseUrl "${scn.baseUrl}" — нет схемы http:// или https://. Пример: "http://localhost:8080"`);
        } else if (u.pathname !== '/' && u.pathname !== '') {
          push(warnings, `baseUrl содержит путь "${u.pathname}" — пути запросов задаются от корня хоста и НЕ будут дописаны к нему. Оставьте в baseUrl только схему://хост:порт.`);
        }
      } catch { push(errors, `baseUrl "${scn.baseUrl}" — не валидный URL. Нужен вид http://host:port`); }
    }
  }

  // sql — блок транспорта СУБД (только для kind:"sql")
  if (scn.kind === 'sql') {
    const sql = scn.sql;
    if (!sql || typeof sql !== 'object' || Array.isArray(sql)) {
      push(errors, 'kind:"sql" требует блок "sql": { "driver":"psql", "command":["psql","-h","host","-U","user","-d","db"] }');
    } else {
      for (const k of Object.keys(sql)) if (!k.startsWith('_') && !KNOWN_SQL_KEYS.includes(k)) push(warnings, `sql: неизвестный ключ "${k}"`);
      if (!Array.isArray(sql.command) || !sql.command.length || sql.command.some((x) => typeof x !== 'string')) {
        push(errors, 'sql.command должен быть НЕПУСТЫМ массивом строк — команда запуска клиента СУБД (напр. ["psql","-h","localhost","-U","dlmm","-d","dlmm"] или ["docker","exec","-i","pg","psql","-U","dlmm","-d","dlmm"])');
      }
      const knownDrivers = Object.keys(SQL_DRIVERS);
      if (sql.driver !== undefined && !knownDrivers.includes(sql.driver) && sql.driver !== 'custom') {
        push(errors, `sql.driver должен быть ${knownDrivers.map((d) => `"${d}"`).join(' | ')} | "custom", сейчас: ${JSON.stringify(sql.driver)}`);
      }
      if ((sql.driver === 'custom' || sql.driver === undefined) && !SQL_DRIVERS[sql.driver]) {
        // для custom без пресета нужны хотя бы mark и timeRegex, иначе не распарсим результат/латентность
        if (!sql.mark) push(errors, 'sql.driver:"custom" требует "mark" — команду-маркер конца результата (напр. "\\\\echo __LOADGEN_ROW_DONE__")');
        if (!sql.timeRegex) push(warnings, 'sql.driver:"custom" без timeRegex — латентность будет измеряться по wall-clock (включая накладные pipe), а не серверным таймером');
      }
      for (const rk of ['init', 'flags']) if (sql[rk] !== undefined && (!Array.isArray(sql[rk]) || sql[rk].some((x) => typeof x !== 'string'))) push(errors, `sql.${rk} должен быть массивом строк`);
      for (const rk of ['timeRegex', 'rowsRegex', 'errorRegex']) {
        if (sql[rk] !== undefined) { try { new RegExp(sql[rk]); } catch (e) { push(errors, `sql.${rk} — некорректное регулярное выражение: ${e.message}`); } }
      }
      if (errors.length === 0) {
        scn._sqlDriver = resolveSqlDriver(sql); // компилируем драйвер один раз
        // без in-band статуса (как у psql :ERROR) ошибка берётся из stderr — из-за гонки stdout↔stderr
        // статистика ошибок best-effort и под нагрузкой может искажаться. Надёжнее всего driver:"psql".
        if (!scn._sqlDriver.statusRe) push(warnings, `sql.driver:"${sql.driver || 'custom'}" без in-band статуса ошибки (statusRegex) — признак ошибки берётся из stderr, статистика ошибок best-effort (возможна гонка stdout↔stderr под нагрузкой). Максимально надёжно — driver:"psql".`);
      }
    }
  }

  // pipeline — сквозной лаг конвейера: produce (Kafka) → verify (SQL-поллинг до материализации)
  if (scn.kind === 'pipeline') {
    const pr = scn.produce, ve = scn.verify, pl = scn.pipeline || {};
    if (!pr || typeof pr !== 'object' || !Array.isArray(pr.command) || !pr.command.length || pr.command.some((x) => typeof x !== 'string')) {
      push(errors, 'kind:"pipeline" требует "produce": { "command":[...клиент-продьюсер...], "message":"{{corr}}:..." } — command непустой массив строк');
    }
    if (!pr || typeof pr.message !== 'string' || !pr.message.includes('{{corr}}')) {
      push(errors, 'produce.message должен быть строкой и содержать {{corr}} — корреляционный ключ (uuid на итерацию), по которому verify ищет материализацию');
    }
    if (!ve || typeof ve !== 'object' || !ve.sql || typeof ve.sql !== 'object' || !Array.isArray(ve.sql.command) || !ve.sql.command.length) {
      push(errors, 'kind:"pipeline" требует "verify": { "sql":{ "driver":"psql","command":[...] }, "query":"select 1 from t where corr=\'{{corr}}\' limit 1" }');
    } else if (typeof ve.query !== 'string' || !ve.query.includes('{{corr}}')) {
      push(errors, 'verify.query должен быть строкой и содержать {{corr}} — иначе поллинг не свяжет запись с посланным сообщением');
    }
    pl.pollIntervalMs = pl.pollIntervalMs ?? 100;
    pl.timeoutMs = pl.timeoutMs ?? 5000;
    scn.pipeline = pl;
    if (!Number.isFinite(pl.pollIntervalMs) || pl.pollIntervalMs < 1) push(errors, 'pipeline.pollIntervalMs должен быть числом >= 1');
    if (!Number.isFinite(pl.timeoutMs) || pl.timeoutMs < 1) push(errors, 'pipeline.timeoutMs (макс. ожидание материализации) должен быть числом >= 1');
    if (errors.length === 0) {
      scn._verifyDriver = resolveSqlDriver(ve.sql); // verify-драйвер (обычно psql)
      if (!scn._verifyDriver.statusRe) push(warnings, `verify.sql.driver:"${ve.sql.driver || 'custom'}" без in-band статуса — ошибки verify-запроса best-effort. Надёжнее psql.`);
    }
  }

  // timeout
  if (scn.timeoutMs === undefined) scn.timeoutMs = DEFAULT_TIMEOUT_MS;
  else if (typeof scn.timeoutMs !== 'number' || scn.timeoutMs < 1) push(errors, `"timeoutMs" должен быть положительным числом (мс), сейчас: ${JSON.stringify(scn.timeoutMs)}`);

  // auth
  if (scn.auth) {
    const t = scn.auth.type;
    if (!['none', 'bearer', 'login'].includes(t)) push(errors, `auth.type должен быть "none" | "bearer" | "login", сейчас: ${JSON.stringify(t)}`);
    if (t === 'bearer' && !scn.auth.token) push(errors, 'auth.type="bearer", но нет auth.token');
    if (t === 'login') {
      const lg = scn.auth.login;
      if (!lg || typeof lg !== 'object') push(errors, 'auth.type="login", но нет объекта auth.login { path, body, tokenField }');
      else {
        if (!lg.path) push(errors, 'auth.login.path обязателен (например "/api/v1/auth/login")');
        if (lg.body === undefined) push(errors, 'auth.login.body обязателен (JSON с учётными данными)');
        if (!lg.tokenField) push(warnings, 'auth.login.tokenField не задан — использую "accessToken". Если токен лежит глубже, укажите путь, например "data.token"');
      }
    }
  } else {
    scn.auth = { type: 'none' };
  }

  // vars
  if (scn.vars !== undefined) {
    if (typeof scn.vars !== 'object' || Array.isArray(scn.vars)) push(errors, '"vars" должен быть объектом: { "имя": [значения] | { "setupPath", "extract" } | { "file", "column"?, "extract"? } }');
    else {
      for (const [name, def] of Object.entries(scn.vars)) {
        if (name.startsWith('_')) continue;
        if (Array.isArray(def)) {
          if (!def.length) push(errors, `vars.${name}: пустой список значений`);
        } else if (typeof def === 'object' && def !== null) {
          const sources = ['setupPath', 'file'].filter((k) => def[k] !== undefined);
          if (sources.length !== 1) {
            push(errors, `vars.${name}: укажите ровно ОДИН источник — "setupPath" (значения из GET-запроса) или "file" (значения из файла .txt/.csv/.json)`);
          } else if (sources[0] === 'setupPath') {
            if (typeof def.setupPath !== 'string' || !def.setupPath) push(errors, `vars.${name}: setupPath должен быть непустой строкой-путём`);
            if (!def.extract) push(errors, `vars.${name}: нет extract (путь до значений в JSON, например "content[*].id")`);
          } else if (typeof def.file !== 'string' || !def.file) {
            push(errors, `vars.${name}: "file" должен быть непустой строкой-путём к .txt/.csv/.json`);
          } else {
            const p = isAbsolute(def.file) ? def.file : join(scn._dir || '.', def.file);
            if (!existsSync(p)) push(errors, `vars.${name}: файл не найден: ${p} (относительные пути считаются от папки сценария)`);
            if (/\.csv$/i.test(def.file) && !def.column) push(errors, `vars.${name}: для CSV-файла обязателен "column" — имя колонки из заголовка`);
            if (!/\.(csv|json)$/i.test(def.file) && def.extract) push(warnings, `vars.${name}: "extract" применяется только к .json-файлам, для .txt будет проигнорирован`);
          }
        } else {
          push(errors, `vars.${name}: должен быть массивом значений или объектом { setupPath, extract } / { file, column?, extract? }`);
        }
      }
    }
  }

  // requests / flows
  const globalVarNames = Object.keys(scn.vars || {}).filter((k) => !k.startsWith('_'));
  // setup-захваты доступны НАГРУЗКЕ (и teardown), но не самой setup-фазе (там — только предыдущие шаги)
  const setupCaptureNames = Array.isArray(scn.setup)
    ? scn.setup.flatMap((s) => (s && typeof s.capture === 'object' && s.capture ? Object.keys(s.capture) : []))
    : [];
  const varNames = [...globalVarNames, ...setupCaptureNames];
  const isBuiltinPh = (ph) => BUILTIN_PLACEHOLDERS.includes(ph) || /^randInt:-?\d+--?\d+$/.test(ph);

  // валидирует один шаг/запрос; available — Set имён доступных переменных (глоб. vars + захваты ранее);
  // возвращает Set имён, захваченных ЭТИМ шагом (для проброса дальше по цепочке).
  const validateStepObj = (r, label, available, i) => {
    if (typeof r !== 'object' || r === null) { push(errors, `${label}: должен быть объектом`); return new Set(); }
    for (const k of Object.keys(r)) {
      if (k.startsWith('_')) continue;
      if (!KNOWN_REQ_KEYS.includes(k)) {
        const s = suggestKey(k, KNOWN_REQ_KEYS);
        push(warnings, `${label}: неизвестный ключ "${k}"${s ? ` — возможно, "${s}"` : ''}`);
      }
    }
    // ── SQL-шаг (kind:"sql"): вместо method/path — текст запроса в поле "sql" ──
    if (scn.kind === 'sql') {
      if (typeof r.sql !== 'string' || !r.sql.trim()) push(errors, `${label}: нет "sql" (текст запроса) — для kind:"sql" шаг задаётся полем "sql", а не method/path`);
      if (!r.name) r.name = r.sql ? String(r.sql).replace(/\s+/g, ' ').trim().slice(0, 40) : `#${i}`;
      for (const bad of ['method', 'path', 'body', 'bodyType', 'expectStatus']) if (r[bad] !== undefined) push(warnings, `${label}: поле "${bad}" в kind:"sql" не используется (шаг задаётся полем sql)`);
      if (r.checks !== undefined) {
        if (typeof r.checks !== 'object' || r.checks === null || Array.isArray(r.checks)) push(errors, `${label}: checks должен быть объектом, например {"minRows":1,"maxMs":300}`);
        else {
          const c = r.checks;
          if (c.minRows !== undefined && (!Number.isInteger(c.minRows) || c.minRows < 0)) push(errors, `${label}: checks.minRows должен быть целым >= 0`);
          if (c.maxMs !== undefined && (typeof c.maxMs !== 'number' || c.maxMs <= 0)) push(errors, `${label}: checks.maxMs должен быть положительным числом (мс)`);
          if (c.notEmpty !== undefined && typeof c.notEmpty !== 'boolean') push(errors, `${label}: checks.notEmpty должен быть true/false`);
          for (const k of ['status', 'bodyContains', 'jsonPath', 'jsonPathEquals']) if (c[k] !== undefined) push(warnings, `${label}: checks.${k} к SQL не применяется — доступны minRows/notEmpty/maxMs`);
          // minRows/notEmpty считают ВОЗВРАЩЁННЫЕ строки: чистый INSERT/UPDATE/DELETE без RETURNING их не даёт → проверка всегда провалится
          if ((c.minRows !== undefined || c.notEmpty) && /\b(insert|update|delete|merge)\b/i.test(r.sql || '') && !/\breturning\b/i.test(r.sql || '')) {
            push(warnings, `${label}: minRows/notEmpty на изменяющем запросе без RETURNING — он не возвращает строк, проверка всегда провалится. Уберите её или добавьте RETURNING.`);
          }
        }
      }
      if (r.capture !== undefined) push(warnings, `${label}: capture в kind:"sql" пока не поддержан — игнорируется (появится в pipeline-режиме)`);
      for (const ph of listPlaceholders(r.sql || '')) { // {{var}} в тексте запроса — те же правила видимости
        if (isBuiltinPh(ph)) continue;
        if (!available.has(ph)) push(errors, `${label}: placeholder {{${ph}}} в sql не объявлен. Доступно: ${[...available].join(', ') || '(ничего)'}. Переменная должна быть в "vars" или захвачена ранее.`);
      }
      return new Set(); // SQL-шаг захватов пока не даёт
    }
    if (!r.name) { r.name = `${r.method || 'GET'} ${r.path || `#${i}`}`; }
    r.method = String(r.method || 'GET').toUpperCase();
    if (!HTTP_METHODS.includes(r.method)) push(errors, `${label}: метод "${r.method}" не поддерживается (${HTTP_METHODS.join(', ')})`);
    if (!r.path) push(errors, `${label}: нет "path"`);
    else if (!/^\//.test(r.path) && !/^https?:\/\//.test(r.path)) push(errors, `${label}: path должен начинаться с "/" (сейчас: "${r.path}")`);
    if (r.expectStatus !== undefined && (!Array.isArray(r.expectStatus) || !r.expectStatus.length || r.expectStatus.some((s) => !Number.isInteger(s)))) {
      push(errors, `${label}: expectStatus должен быть НЕПУСТЫМ массивом целых чисел, например [200, 404]`);
    }
    // GET/HEAD не могут нести тело — иначе fetch бросит "Request with GET/HEAD method cannot have body"
    // на КАЖДОМ запросе (100% ошибок). Ловим в валидации, а не в рантайме.
    if ((r.method === 'GET' || r.method === 'HEAD') && (r.body !== undefined || r.bodyType !== undefined)) {
      push(errors, `${label}: метод ${r.method} не может нести тело (body/bodyType) — уберите body/bodyType или смените метод на POST/PUT/PATCH`);
    }
    // bodyType — формат тела (json | form | multipart)
    if (r.bodyType !== undefined) {
      if (!['json', 'form', 'multipart'].includes(r.bodyType)) {
        push(errors, `${label}: bodyType должен быть "json" | "form" | "multipart", сейчас: ${JSON.stringify(r.bodyType)}`);
      } else if (r.bodyType !== 'json') {
        if (typeof r.body !== 'object' || r.body === null || Array.isArray(r.body)) {
          push(errors, `${label}: bodyType "${r.bodyType}" требует body-объект { поле: значение }`);
        } else if (r.bodyType === 'form') {
          for (const [k, v] of Object.entries(r.body)) if (v !== null && typeof v === 'object') push(errors, `${label}: form-поле "${k}" должно быть скаляром (строка/число/boolean), не объектом`);
        } else if (r.bodyType === 'multipart') {
          for (const [k, v] of Object.entries(r.body)) {
            if (v && typeof v === 'object') { // файловое поле
              if (typeof v.file !== 'string' || !v.file) push(errors, `${label}: multipart-поле "${k}" — объект, значит файл: нужен "file": "путь"`);
              else { const p = isAbsolute(v.file) ? v.file : join(scn._dir || '.', v.file); if (!existsSync(p)) push(errors, `${label}: multipart-файл поля "${k}" не найден: ${p} (путь от папки сценария)`); }
            }
          }
        }
      }
    }
    // checks — валидация ответов
    if (r.checks !== undefined) {
      if (typeof r.checks !== 'object' || r.checks === null || Array.isArray(r.checks)) {
        push(errors, `${label}: checks должен быть объектом, например {"status":[200],"jsonPath":"content[*].id","maxMs":300}`);
      } else {
        const c = r.checks;
        for (const k of Object.keys(c)) {
          if (k.startsWith('_')) continue;
          if (!KNOWN_CHECK_KEYS.includes(k)) {
            const s = suggestKey(k, KNOWN_CHECK_KEYS);
            push(warnings, `${label}: checks: неизвестный ключ "${k}"${s ? ` — возможно, "${s}"` : ''}`);
          }
        }
        if (c.status !== undefined && (!Array.isArray(c.status) || !c.status.length || c.status.some((s) => !Number.isInteger(s)))) push(errors, `${label}: checks.status должен быть НЕПУСТЫМ массивом целых, например [200]`);
        if (c.maxMs !== undefined && (typeof c.maxMs !== 'number' || c.maxMs <= 0)) push(errors, `${label}: checks.maxMs должен быть положительным числом (мс)`);
        if (c.notEmpty !== undefined && typeof c.notEmpty !== 'boolean') push(errors, `${label}: checks.notEmpty должен быть true или false`);
        if (c.bodyContains !== undefined && typeof c.bodyContains !== 'string') push(errors, `${label}: checks.bodyContains должен быть строкой-подстрокой`);
        if (c.jsonPath !== undefined && typeof c.jsonPath !== 'string') push(errors, `${label}: checks.jsonPath должен быть строкой-путём, например "content[*].id"`);
        if (c.jsonPathEquals !== undefined) {
          const jpe = c.jsonPathEquals;
          if (typeof jpe !== 'object' || jpe === null || jpe.path === undefined || jpe.value === undefined) {
            push(errors, `${label}: checks.jsonPathEquals должен быть объектом { "path": "status", "value": "ACTIVE" }`);
          } else if (jpe.value !== null && typeof jpe.value === 'object') {
            push(errors, `${label}: checks.jsonPathEquals.value должен быть скаляром (строка/число/boolean/null), а не объектом/массивом`);
          }
        }
        if (r.method === 'HEAD' && ['notEmpty', 'bodyContains', 'jsonPath', 'jsonPathEquals'].some((k) => c[k] !== undefined)) {
          push(errors, `${label}: метод HEAD не возвращает тело — проверки notEmpty/bodyContains/jsonPath/jsonPathEquals невозможны, оставьте status/maxMs`);
        }
      }
    }
    // capture — извлечение переменных из ответа (для цепочек)
    const captured = new Set();
    if (r.capture !== undefined) {
      if (typeof r.capture !== 'object' || r.capture === null || Array.isArray(r.capture)) {
        push(errors, `${label}: capture должен быть объектом { "имяПеременной": "json.путь" }`);
      } else if (r.method === 'HEAD') {
        push(errors, `${label}: capture невозможен для HEAD (нет тела ответа)`);
      } else {
        for (const [name, path] of Object.entries(r.capture)) {
          if (name.startsWith('_')) continue;
          if (isBuiltinPh(name)) push(errors, `${label}: capture "${name}" совпадает со встроенным placeholder — выберите другое имя`);
          if (typeof path !== 'string' || !path) push(errors, `${label}: capture.${name} должен быть непустым json-путём, например "content[0].id" или "id"`);
          if (globalVarNames.includes(name)) push(warnings, `${label}: capture "${name}" перекрывает глобальную vars-переменную с тем же именем`);
          captured.add(name);
        }
      }
    }
    // placeholders — доступны глобальные vars, встроенные и переменные, захваченные РАНЕЕ в цепочке
    const used = [
      ...listPlaceholders(r.path || ''),
      ...(r.body !== undefined ? listPlaceholders(typeof r.body === 'string' ? r.body : JSON.stringify(r.body)) : []),
      ...(r.headers ? listPlaceholders(JSON.stringify(r.headers)) : []),
    ];
    for (const ph of used) {
      if (isBuiltinPh(ph)) continue;
      if (!available.has(ph)) {
        const avail = [...available];
        push(errors, `${label}: placeholder {{${ph}}} не объявлен. Доступно: ${avail.length ? avail.join(', ') : '(ничего)'}. В цепочке переменная должна быть в "vars" ИЛИ захвачена (capture) на ПРЕДЫДУЩЕМ шаге. Встроенные: {{uuid}}, {{ts}}, {{randInt:A-B}}`);
      }
    }
    return captured;
  };

  const hasRequests = Array.isArray(scn.requests) && scn.requests.length;
  const hasFlows = Array.isArray(scn.flows) && scn.flows.length;
  if (!hasRequests && !hasFlows && scn.kind !== 'pipeline') { // pipeline задаётся produce/verify, а не requests/flows
    push(errors, 'Нужен непустой массив "requests" (смесь независимых запросов) ИЛИ "flows" (сценарии-цепочки). Каждый запрос: { "name","method":"GET","path":"/...","weight":1 }.');
  }
  if (scn.kind === 'pipeline' && (hasRequests || hasFlows)) push(warnings, 'в kind:"pipeline" requests/flows игнорируются — конвейер задаётся produce/verify');
  if (scn.kind === 'pipeline' && (scn.setup !== undefined || scn.teardown !== undefined)) push(warnings, 'setup/teardown в kind:"pipeline" пока не выполняются — подготовьте sink-таблицу отдельным kind:"sql"-сценарием или вручную');
  if (scn.requests !== undefined && !Array.isArray(scn.requests)) push(errors, '"requests" должен быть массивом');
  if (scn.flows !== undefined && !Array.isArray(scn.flows)) push(errors, '"flows" должен быть массивом');

  const allStepNames = new Map(); // имя шага → где встретилось (глобальная уникальность для метрик)
  const registerName = (name, where) => {
    if (!name) return;
    if (allStepNames.has(name)) push(errors, `${where}: имя "${name}" уже используется в ${allStepNames.get(name)} — имена шагов/запросов должны быть уникальны (метрики считаются по имени).`);
    else allStepNames.set(name, where);
  };

  // pipeline: плейсхолдеры produce.message/verify.query — ловим опечатки ({{crr}}) и забытые vars до запуска (иначе smoke/load падали бы в рантайме)
  if (scn.kind === 'pipeline' && scn.produce && scn.verify) {
    const availPipe = new Set([...varNames, 'corr']); // corr — встроенный для pipeline
    for (const [field, txt] of [['produce.message', scn.produce.message], ['verify.query', scn.verify.query]]) {
      for (const ph of listPlaceholders(String(txt || ''))) {
        if (isBuiltinPh(ph) || ph === 'corr' || availPipe.has(ph)) continue;
        push(errors, `${field}: placeholder {{${ph}}} не объявлен. Доступно: ${[...varNames].join(', ') || '(нет vars)'} + {{corr}} + встроенные ({{uuid}},{{ts}},{{randInt:A-B}})`);
      }
    }
  }

  // requests → каждый как самостоятельный запрос
  if (hasRequests) {
    scn.requests.forEach((r, i) => {
      const label = `requests[${i}]${r?.name ? ` ("${r.name}")` : ''}`;
      if (typeof r !== 'object' || r === null) { push(errors, `${label}: должен быть объектом`); return; }
      if (r.weight === undefined) r.weight = 1;
      if (typeof r.weight !== 'number' || r.weight <= 0) push(errors, `${label}: weight должен быть положительным числом`);
      if (r.capture !== undefined) push(warnings, `${label}: capture в независимом запросе бесполезен (некому передать значение) — capture нужен внутри "flows"`);
      validateStepObj(r, label, new Set(varNames), i);
      registerName(r.name, label);
    });
  }

  // flows → упорядоченные цепочки шагов с передачей захваченных переменных
  if (hasFlows) {
    scn.flows.forEach((f, fi) => {
      const flabel = `flows[${fi}]${f?.name ? ` ("${f.name}")` : ''}`;
      if (typeof f !== 'object' || f === null) { push(errors, `${flabel}: должен быть объектом { name, weight, steps:[...] }`); return; }
      for (const k of Object.keys(f)) {
        if (k.startsWith('_')) continue;
        if (!KNOWN_FLOW_KEYS.includes(k)) { const s = suggestKey(k, KNOWN_FLOW_KEYS); push(warnings, `${flabel}: неизвестный ключ "${k}"${s ? ` — возможно, "${s}"` : ''}`); }
      }
      if (!f.name) f.name = `flow-${fi}`;
      registerName(f.name, `${flabel} (имя цепочки)`); // имя flow — ключ flow-статистики, должно быть уникально
      if (f.weight === undefined) f.weight = 1;
      if (typeof f.weight !== 'number' || f.weight <= 0) push(errors, `${flabel}: weight должен быть положительным числом`);
      if (!Array.isArray(f.steps) || !f.steps.length) { push(errors, `${flabel}: нужен непустой массив "steps"`); return; }
      const available = new Set(varNames);
      f.steps.forEach((step, si) => {
        const slabel = `${flabel}.steps[${si}]${step?.name ? ` ("${step.name}")` : ''}`;
        const captured = validateStepObj(step, slabel, available, si);
        registerName(step.name, slabel);
        for (const c of captured) available.add(c); // захваты доступны СЛЕДУЮЩИМ шагам
      });
    });
  }

  // setup / teardown — фазы жизненного цикла (выполняются один раз ДО/ПОСЛЕ нагрузки).
  // Захваты setup доступны и нагрузке, и teardown (создать сущность → нагрузить → удалить).
  const validatePhase = (arr, key, baseAvailable) => {
    if (arr === undefined) return baseAvailable;
    if (!Array.isArray(arr)) { push(errors, `"${key}" должен быть массивом шагов [{ name, method, path, body?, capture?, checks? }]`); return baseAvailable; }
    const available = new Set(baseAvailable);
    arr.forEach((step, si) => {
      const captured = validateStepObj(step, `${key}[${si}]${step?.name ? ` ("${step.name}")` : ''}`, available, si);
      for (const c of captured) available.add(c); // захваты доступны следующим шагам фазы
    });
    return available;
  };
  const afterSetupVars = validatePhase(scn.setup, 'setup', new Set(globalVarNames)); // setup видит только vars + свои прошлые шаги
  validatePhase(scn.teardown, 'teardown', afterSetupVars); // teardown видит vars + все захваты setup

  // writes — по всем шагам (requests + flow steps + setup + teardown)
  const lifecycleSteps = [
    ...(Array.isArray(scn.setup) ? scn.setup : []),
    ...(Array.isArray(scn.teardown) ? scn.teardown : []),
  ].filter((s) => s && typeof s === 'object');
  const allSteps = [
    ...(hasRequests ? scn.requests : []),
    ...(hasFlows ? scn.flows.flatMap((f) => (Array.isArray(f?.steps) ? f.steps : [])) : []),
  ].filter((s) => s && typeof s === 'object');
  // мутирующие шаги: для SQL — по ключевым словам DML/DDL, для HTTP — по методу (двойная защита: allowWrites + --allow-writes)
  const isWriteStep = (r) => scn.kind === 'sql' ? SQL_WRITE_RE.test(r.sql || '') : WRITE_METHODS.includes(String(r.method || '').toUpperCase());
  const writeReqs = [...allSteps, ...lifecycleSteps].filter(isWriteStep);
  // pipeline produce'ит сообщения в топик — это запись, требует такой же двойной защиты
  if ((writeReqs.length || scn.kind === 'pipeline') && scn.allowWrites !== true) {
    const what = scn.kind === 'pipeline' ? 'публикует сообщения в топик (это запись)' : `содержит изменяющие запросы (${writeReqs.map((r) => `"${r.name}"`).join(', ')})`;
    push(errors,
      `Сценарий ${what}, но allowWrites не установлен в true.\n` +
      `  Это защита от случайной порчи данных. Если писать в систему ДЕЙСТВИТЕЛЬНО нужно и пользователь это явно разрешил:\n` +
      `  1) добавьте в сценарий "allowWrites": true;  2) запускайте с флагом --allow-writes.`);
  }

  // нормализация: единая модель — всё есть flows. Независимые requests = одношаговые flow.
  if (errors.length === 0) {
    if (scn.kind === 'pipeline') {
      scn._steps = [{ name: 'pipeline' }]; // единственный «шаг» — сквозной цикл; метрики по нему
      scn._flows = [];
      scn._setup = []; scn._teardown = [];
      scn._hasExplicitFlows = false;
    } else {
      const wrapped = hasRequests ? scn.requests.map((r) => ({ name: r.name, weight: r.weight, steps: [r], _track: false })) : [];
      const explicit = hasFlows ? scn.flows.map((f) => ({ name: f.name, weight: f.weight, steps: f.steps, _track: true })) : [];
      scn._flows = [...wrapped, ...explicit];
      scn._steps = allSteps;
      scn._setup = Array.isArray(scn.setup) ? scn.setup : [];
      scn._teardown = Array.isArray(scn.teardown) ? scn.teardown : [];
      scn._hasExplicitFlows = !!hasFlows;
    }
  }

  // load
  if (scn.load === undefined) scn.load = {};
  if (typeof scn.load !== 'object') push(errors, '"load" должен быть объектом');
  else {
    for (const k of Object.keys(scn.load)) {
      if (k.startsWith('_')) continue;
      if (!KNOWN_LOAD_KEYS.includes(k)) {
        const s = suggestKey(k, KNOWN_LOAD_KEYS);
        push(warnings, `load: неизвестный ключ "${k}"${s ? ` — возможно, "${s}"` : ''}`);
      }
    }
    // stages — многоступенчатый профиль (ramp/spike/soak). Если задан — выводит vus/durationSec.
    if (scn.load.stages !== undefined) {
      if (!Array.isArray(scn.load.stages) || !scn.load.stages.length) {
        push(errors, 'load.stages должен быть непустым массивом ступеней: [{ "vus": N, "durationSec": T }, ...]');
      } else {
        let ok = true, maxVus = 0, totalSec = 0;
        scn.load.stages.forEach((st, i) => {
          if (typeof st !== 'object' || st === null || !Number.isInteger(st.vus) || st.vus < 0 || typeof st.durationSec !== 'number' || st.durationSec <= 0) {
            push(errors, `load.stages[${i}] должен быть { "vus": целое>=0, "durationSec": число>0 }`); ok = false;
          } else { maxVus = Math.max(maxVus, st.vus); totalSec += st.durationSec; }
        });
        if (ok) {
          if (maxVus < 1) push(errors, 'load.stages: хотя бы одна ступень должна иметь vus >= 1');
          if (maxVus > MAX_VUS) push(errors, `load.stages: пиковые vus=${maxVus} превышают лимит ${MAX_VUS}`);
          if (totalSec > MAX_DURATION_SEC) push(errors, `load.stages: суммарная длительность ${totalSec}с превышает лимит ${MAX_DURATION_SEC}с`);
          if (scn.load.vus !== undefined || scn.load.durationSec !== undefined || scn.load.rampUpSec !== undefined) {
            push(warnings, 'load.stages задан — load.vus/durationSec/rampUpSec игнорируются (выводятся из ступеней)');
          }
          // выводим производные значения для остального кода
          scn.load.vus = maxVus;
          scn.load.durationSec = totalSec;
          scn.load.rampUpSec = 0;
        }
      }
    }
    scn.load.vus = scn.load.vus ?? 5;
    scn.load.durationSec = scn.load.durationSec ?? 30;
    scn.load.rampUpSec = scn.load.rampUpSec ?? 0;
    if (!Number.isInteger(scn.load.vus) || scn.load.vus < 1) push(errors, `load.vus должен быть целым >= 1, сейчас: ${JSON.stringify(scn.load.vus)}`);
    if (scn.load.vus > MAX_VUS) push(errors, `load.vus=${scn.load.vus} превышает жёсткий лимит ${MAX_VUS} (защита от случайного DoS)`);
    if (!Number.isFinite(scn.load.durationSec) || scn.load.durationSec < 1) push(errors, `load.durationSec должен быть числом >= 1 (например, --duration 30 без суффикса единицы)`);
    if (scn.load.durationSec > MAX_DURATION_SEC) push(errors, `load.durationSec=${scn.load.durationSec} превышает жёсткий лимит ${MAX_DURATION_SEC} сек`);
    if (!Number.isFinite(scn.load.rampUpSec) || scn.load.rampUpSec < 0) push(errors, `load.rampUpSec должен быть числом >= 0`);
    scn.load.warmupSec = scn.load.warmupSec ?? 0;
    if (!Number.isFinite(scn.load.warmupSec) || scn.load.warmupSec < 0) push(errors, 'load.warmupSec должен быть числом >= 0 (сколько секунд разогрева исключить из метрик)');
    else if (scn.load.warmupSec >= scn.load.durationSec) push(errors, `load.warmupSec=${scn.load.warmupSec} должен быть меньше длительности ${scn.load.durationSec}с`);
    if (scn.load.warmupSec > 0 && scn.load.stages) push(warnings, 'load.warmupSec со stages не применяется (нагрузка не постоянна) — будет проигнорирован');
    let tt = scn.load.thinkTimeMs ?? 0;
    if (typeof tt === 'number') tt = [tt, tt];
    if (!Array.isArray(tt) || tt.length !== 2 || tt.some((x) => !Number.isFinite(x) || x < 0) || tt[0] > tt[1]) {
      push(errors, 'load.thinkTimeMs должен быть числом или парой [minМс, maxМс], min <= max');
    } else scn.load.thinkTimeMs = tt;
    if (scn.load.maxRps !== undefined && (!Number.isFinite(scn.load.maxRps) || scn.load.maxRps < 1)) push(errors, 'load.maxRps должен быть числом >= 1 (например, --max-rps 50 без суффикса)');
    scn.load.workers = scn.load.workers ?? 1;
    const cores = os.cpus().length;
    if (!Number.isInteger(scn.load.workers) || scn.load.workers < 1) push(errors, `load.workers должен быть целым >= 1 (число потоков-генераторов), сейчас: ${JSON.stringify(scn.load.workers)}`);
    else if (scn.load.workers > 4 * cores) push(errors, `load.workers=${scn.load.workers} превышает разумный предел ${4 * cores} (4× ядер этой машины=${cores})`);
    else if (scn.load.workers > cores) push(warnings, `load.workers=${scn.load.workers} больше числа ядер (${cores}) — потоки будут конкурировать за CPU, прироста RPS не будет`);
    if (scn.load.workers > 1 && scn.load.workers > scn.load.vus) push(warnings, `load.workers=${scn.load.workers} больше load.vus=${scn.load.vus} — лишние потоки останутся без пользователей; часть будет простаивать`);
  }

  // thresholds
  if (scn.thresholds === undefined) scn.thresholds = {};
  const th = scn.thresholds;
  th.p95Ms = th.p95Ms ?? 1000;
  th.errorRatePct = th.errorRatePct ?? 1;
  if (typeof th.p95Ms !== 'number' || th.p95Ms <= 0) push(errors, 'thresholds.p95Ms должен быть положительным числом (мс)');
  if (typeof th.errorRatePct !== 'number' || th.errorRatePct < 0) push(errors, 'thresholds.errorRatePct должен быть числом >= 0 (проценты)');
  if (th.p99Ms !== undefined && (typeof th.p99Ms !== 'number' || th.p99Ms <= 0)) push(errors, 'thresholds.p99Ms должен быть положительным числом (мс) — SLO по хвосту латентности');
  if (th.rpsMin !== undefined && (typeof th.rpsMin !== 'number' || th.rpsMin < 0)) push(errors, 'thresholds.rpsMin должен быть числом >= 0 (минимальная пропускная способность, запросов/сек)');
  // per-request пороги: { "имя запроса": { p95Ms?, p99Ms?, errorRatePct? } } — вердикт enforce'ит SLO на эндпоинт
  if (th.perRequest !== undefined) {
    if (typeof th.perRequest !== 'object' || th.perRequest === null || Array.isArray(th.perRequest)) {
      push(errors, 'thresholds.perRequest должен быть объектом { "имя запроса": { "p95Ms": N, "p99Ms": N, "errorRatePct": N } }');
    } else {
      // имена шагов берём из allSteps (есть всегда, даже при других ошибках), НЕ из scn._steps
      const stepNames = new Set(allSteps.map((s) => s.name).filter(Boolean));
      for (const [name, t] of Object.entries(th.perRequest)) {
        if (name.startsWith('_')) continue;
        if (typeof t !== 'object' || t === null || Array.isArray(t)) { push(errors, `thresholds.perRequest["${name}"] должен быть объектом { p95Ms?, p99Ms?, errorRatePct? }`); continue; }
        if (t.p95Ms !== undefined && (typeof t.p95Ms !== 'number' || t.p95Ms <= 0)) push(errors, `thresholds.perRequest["${name}"].p95Ms должен быть положительным числом`);
        if (t.p99Ms !== undefined && (typeof t.p99Ms !== 'number' || t.p99Ms <= 0)) push(errors, `thresholds.perRequest["${name}"].p99Ms должен быть положительным числом`);
        if (t.errorRatePct !== undefined && (typeof t.errorRatePct !== 'number' || t.errorRatePct < 0)) push(errors, `thresholds.perRequest["${name}"].errorRatePct должен быть числом >= 0`);
        if (t.p95Ms === undefined && t.p99Ms === undefined && t.errorRatePct === undefined) push(errors, `thresholds.perRequest["${name}"]: задайте хотя бы p95Ms, p99Ms или errorRatePct`);
        // опечатка в имени = ОШИБКА (иначе SLO молча не enforce'ится → ложный PASS)
        if (!stepNames.has(name)) push(errors, `thresholds.perRequest["${name}"]: нет запроса/шага с таким именем — порог не был бы применён. Есть: ${[...stepNames].join(', ') || 'нет шагов'}`);
      }
    }
  }

  // monitor — метрики цели во время прогона (docker stats / Prometheus)
  if (scn.monitor !== undefined) {
    const mo = scn.monitor;
    if (typeof mo !== 'object' || mo === null || Array.isArray(mo)) {
      push(errors, 'monitor должен быть объектом { docker?: {...}, prometheus?: {...}, intervalSec?: N, thresholds?: {...} }');
    } else {
      if (mo.intervalSec !== undefined && (typeof mo.intervalSec !== 'number' || mo.intervalSec < 1)) push(errors, 'monitor.intervalSec должен быть числом >= 1 (секунды между опросами)');
      if (!mo.docker && !mo.prometheus && !mo.kafka) push(errors, 'monitor задан, но пуст — укажите monitor.docker (контейнеры), monitor.prometheus (url+queries) и/или monitor.kafka (consumer-lag групп)');
      if (mo.kafka !== undefined) {
        const kf = mo.kafka;
        if (typeof kf !== 'object' || kf === null || !Array.isArray(kf.command) || !kf.command.length || kf.command.some((x) => typeof x !== 'string')) {
          push(errors, 'monitor.kafka.command — непустой массив строк: база вызова kafka-consumer-groups (напр. ["kafka-consumer-groups","--bootstrap-server","localhost:9092"] или через docker exec). Инструмент сам добавит --describe --group G');
        }
        if (!Array.isArray(kf.groups) || !kf.groups.length || kf.groups.some((g) => typeof g !== 'string' || !g)) {
          push(errors, 'monitor.kafka.groups — непустой массив имён consumer-групп для замера лага');
        }
      }
      if (mo.docker !== undefined) {
        if (typeof mo.docker !== 'object' || mo.docker === null || !Array.isArray(mo.docker.containers) || !mo.docker.containers.length) {
          push(errors, 'monitor.docker должен быть { "containers": ["имя-контейнера", ...] } (метрики через `docker stats`)');
        } else if (mo.docker.containers.some((c) => typeof c !== 'string' || !c)) push(errors, 'monitor.docker.containers: имена контейнеров — непустые строки');
      }
      if (mo.prometheus !== undefined) {
        const pm = mo.prometheus;
        if (typeof pm !== 'object' || pm === null) push(errors, 'monitor.prometheus должен быть { "url": "...", "queries": { "имя": "PromQL" } }');
        else {
          if (!pm.url) push(errors, 'monitor.prometheus.url обязателен (адрес Prometheus, например http://localhost:9090)');
          else { try { new URL(pm.url); } catch { push(errors, `monitor.prometheus.url "${pm.url}" — не валидный URL`); } }
          if (typeof pm.queries !== 'object' || pm.queries === null || Array.isArray(pm.queries) || !Object.keys(pm.queries).length) {
            push(errors, 'monitor.prometheus.queries должен быть непустым объектом { "имя метрики": "PromQL-запрос" }');
          } else for (const [k, v] of Object.entries(pm.queries)) if (typeof v !== 'string' || !v) push(errors, `monitor.prometheus.queries["${k}"] должен быть непустой строкой-PromQL`);
        }
      }
      if (mo.thresholds !== undefined) {
        if (typeof mo.thresholds !== 'object' || mo.thresholds === null || Array.isArray(mo.thresholds)) push(errors, 'monitor.thresholds должен быть объектом { "имя метрики": { "max": N } }');
        else {
          // предсказуемые имена метрик: docker → "<контейнер> CPU %" / "<контейнер> MEM МБ"; prometheus → имя запроса; kafka → "<группа> lag"
          const known = new Set();
          if (mo.docker && Array.isArray(mo.docker.containers)) for (const c of mo.docker.containers) { known.add(`${c} CPU %`); known.add(`${c} MEM МБ`); }
          if (mo.prometheus && mo.prometheus.queries && typeof mo.prometheus.queries === 'object') for (const q of Object.keys(mo.prometheus.queries)) known.add(q);
          if (mo.kafka && Array.isArray(mo.kafka.groups)) for (const g of mo.kafka.groups) known.add(`${g} lag`);
          for (const [k, t] of Object.entries(mo.thresholds)) {
            if (typeof t !== 'object' || t === null || typeof t.max !== 'number') { push(errors, `monitor.thresholds["${k}"] должен быть { "max": число } (порог по метрике войдёт в вердикт)`); continue; }
            // опечатка в имени = ОШИБКА (иначе после целого прогона получили бы ложный FAIL «метрика не собрана»)
            if (!known.has(k)) push(errors, `monitor.thresholds["${k}"]: нет такой метрики. Доступны: ${[...known].join(', ') || '(нет)'} (docker: "<контейнер> CPU %"/"<контейнер> MEM МБ", prometheus: имя запроса, kafka: "<группа> lag")`);
          }
        }
      }
    }
  }

  return { errors, warnings };
}

// ─────────────────────────────────────────────── HTTP ──

function authHeaders(scn, token) {
  const h = { ...(scn.headers || {}) };
  if (token) h['Authorization'] = `Bearer ${token}`;
  return h;
}

async function doLogin(scn) {
  const lg = scn.auth.login;
  const url = new URL(lg.path, scn.baseUrl);
  let res, text;
  try {
    res = await fetch(url, {
      method: (lg.method || 'POST').toUpperCase(),
      headers: { 'Content-Type': 'application/json', ...(scn.headers || {}) },
      body: JSON.stringify(lg.body),
      signal: AbortSignal.timeout(scn.timeoutMs),
    });
    text = await res.text();
  } catch (e) {
    throw new Error(`логин недоступен (${url}): ${errText(e)}`);
  }
  if (!res.ok) throw new Error(`логин не удался: ${lg.method || 'POST'} ${lg.path} → HTTP ${res.status}. Ответ: ${text.slice(0, 200)}`);
  let json;
  try { json = JSON.parse(text); } catch { throw new Error(`логин: ответ не JSON: ${text.slice(0, 120)}`); }
  const field = lg.tokenField || 'accessToken';
  const token = extractPath(json, field);
  if (typeof token !== 'string' || !token) {
    throw new Error(`логин: не нашёл токен по пути "${field}". Верхние ключи ответа: ${Object.keys(json).join(', ')}. Укажите правильный auth.login.tokenField`);
  }
  return token;
}

async function resolveVars(scn, token) {
  const out = {};
  for (const [name, def] of Object.entries(scn.vars || {})) {
    if (name.startsWith('_')) continue;
    if (Array.isArray(def)) { out[name] = def; continue; }
    if (def.file !== undefined) { out[name] = loadVarFile(scn._dir, name, def); continue; }
    if (typeof def.setupPath !== 'string' || !def.setupPath) {
      throw new Error(`vars.${name}: нет корректного источника значений (setupPath/file) — прогоните validate`);
    }
    const url = new URL(renderTemplate(def.setupPath, out), scn.baseUrl);
    let res, text;
    try {
      res = await fetch(url, { method: (def.method || 'GET').toUpperCase(), headers: authHeaders(scn, token), signal: AbortSignal.timeout(scn.timeoutMs) });
      text = await res.text();
    } catch (e) {
      throw new Error(`vars.${name}: setup-запрос ${url} упал: ${errText(e)}`);
    }
    if (!res.ok) throw new Error(`vars.${name}: setup-запрос ${def.setupPath} → HTTP ${res.status}: ${text.slice(0, 200)}`);
    let json;
    try { json = JSON.parse(text); } catch { throw new Error(`vars.${name}: ответ setup-запроса не JSON: ${text.slice(0, 120)}`); }
    let vals = extractPath(json, def.extract);
    if (!Array.isArray(vals)) vals = vals == null ? [] : [vals];
    if (!vals.length) {
      throw new Error(`vars.${name}: путь "${def.extract}" не дал значений. Верхние ключи ответа: ${Object.keys(json).join(', ')}. ` +
        `Подсказка: для Spring Page используйте "content[*].id", для массива — "[*].id"`);
    }
    out[name] = vals;
  }
  return out;
}

/**
 * Выполняет фазу жизненного цикла (setup/teardown): шаги по порядку, один раз.
 * Захваты пишутся в общий vars (мутирует его), доступны нагрузке и следующим фазам.
 * abortOnFail=true (setup): остановиться на первом провале. false (teardown): best-effort, идём дальше.
 */
async function runLifecyclePhase(scn, steps, token, vars, { abortOnFail }) {
  const results = [];
  const conn = scn.kind === 'sql' ? openSqlSession(scn) : null; // отдельное соединение на фазу
  try {
    for (const step of steps) {
      const r = await callOnce(scn, step, vars, token, conn);
      let rec = r, capErr = null;
      if (r.ok && step.capture && scn.kind !== 'sql') { // capture в SQL пока не поддержан — не пытаемся (у SQL-записи нет r.text)
        const cap = applyCaptures(step, r.text, vars); // мутирует vars — значение доступно дальше
        if (!cap.ok) { rec = { ms: r.ms, status: r.status, ok: false, kind: 'capture' }; capErr = cap.error; }
      }
      results.push({
        name: step.name, method: step.method, ok: rec.ok, status: rec.status || 0,
        ms: Math.round(rec.ms || 0), error: rec.ok ? null : (rec.errMsg || capErr || rec.snippet || `HTTP ${rec.status}`),
        captured: rec.ok && step.capture ? Object.keys(step.capture) : [],
      });
      if (!rec.ok && abortOnFail) break;
    }
  } finally { if (conn) await conn.close(); }
  return { results, allOk: results.length === steps.length && results.every((x) => x.ok) };
}

/**
 * Оценка ответа по правилам запроса.
 * Порядок: статус → контентные проверки (notEmpty/bodyContains/jsonPath/jsonPathEquals) → бюджет maxMs.
 * Нарушение контентной проверки = ошибка вида 'check' (в латентность не попадает).
 * Превышение maxMs = ответ успешный (латентность учитывается), но помечен slow.
 */
function evaluateResponse(req, status, text, ms, used) {
  const c = req.checks || {};
  const expected = c.status || req.expectStatus;
  const statusOk = expected ? expected.includes(status) : status >= 200 && status < 400;
  if (!statusOk) return { ms, status, ok: false, snippet: text.slice(0, 250), used };
  const fails = [];
  if (c.notEmpty && !(text && text.trim().length)) fails.push('notEmpty: пустое тело ответа');
  if (c.bodyContains !== undefined && !text.includes(c.bodyContains)) fails.push(`bodyContains: в теле нет подстроки "${c.bodyContains}"`);
  if (c.jsonPath !== undefined || c.jsonPathEquals !== undefined) {
    let json, parsed = false;
    try { json = JSON.parse(text); parsed = true; } catch { fails.push('jsonPath/jsonPathEquals: тело ответа — не JSON'); }
    if (parsed) {
      if (c.jsonPath !== undefined) {
        let v;
        try { v = extractPath(json, c.jsonPath); } catch { v = undefined; }
        if (v === undefined || (Array.isArray(v) && !v.length)) fails.push(`jsonPath: путь "${c.jsonPath}" не дал значений`);
      }
      if (c.jsonPathEquals !== undefined) {
        let v;
        // keepNulls: битый элемент (status:null / без поля) в [*]-пути обязан ПРОВАЛИТЬ "все = X", а не потеряться
        try { v = extractPath(json, c.jsonPathEquals.path, { keepNulls: true }); } catch { v = undefined; }
        const want = c.jsonPathEquals.value;
        // скаляры сравниваются как строки; объект/массив в значении — всегда несовпадение;
        // для [*]-пути должны совпасть ВСЕ значения (и их должно быть > 0)
        const eq = (x) => (x !== null && typeof x === 'object' ? false : String(x) === String(want));
        const match = Array.isArray(v) ? v.length > 0 && v.every(eq) : eq(v);
        if (!match) {
          const gotShown = Array.isArray(v) ? (v.length > 4 ? [...v.slice(0, 4), '…'] : v) : v;
          fails.push(`jsonPathEquals: ${c.jsonPathEquals.path} = ${JSON.stringify(gotShown)}, ожидалось ${JSON.stringify(want)}${Array.isArray(v) ? ' (для [*]-пути должны совпадать ВСЕ значения)' : ''}`);
        }
      }
    }
  }
  if (fails.length) return { ms, status, ok: false, kind: 'check', errMsg: fails.join('; '), snippet: text.slice(0, 150), used };
  const slow = c.maxMs !== undefined && ms > c.maxMs;
  return { ms, status, ok: true, slow, used };
}

const _fileCache = new Map(); // содержимое файлов multipart читается один раз
function readCachedFile(p) { if (!_fileCache.has(p)) _fileCache.set(p, readFileSync(p)); return _fileCache.get(p); }

/** application/x-www-form-urlencoded: {a:1,b:"{{x}}"} → "a=1&b=<val>" */
function encodeForm(obj, vars, used) {
  return Object.entries(obj)
    .map(([k, v]) => `${encodeURIComponent(k)}=${encodeURIComponent(renderTemplate(String(v), vars, used))}`)
    .join('&');
}

/** multipart/form-data: строковые поля + файловые { "file": "путь", filename?, type? }. Возвращает { body: Buffer, contentType }. */
function buildMultipart(scnDir, fields, vars, used) {
  const boundary = '----loadgen' + randomUUID().replace(/-/g, '');
  const chunks = [];
  for (const [name, v] of Object.entries(fields)) {
    if (v && typeof v === 'object' && v.file !== undefined) {
      const p = isAbsolute(v.file) ? v.file : join(scnDir || '.', v.file);
      const content = readCachedFile(p);
      const filename = renderTemplate(String(v.filename || basename(p)), vars, used); // плейсхолдеры в имени файла
      const ct = renderTemplate(String(v.type || 'application/octet-stream'), vars, used);
      chunks.push(Buffer.from(`--${boundary}\r\nContent-Disposition: form-data; name="${name}"; filename="${filename}"\r\nContent-Type: ${ct}\r\n\r\n`));
      chunks.push(content);
      chunks.push(Buffer.from('\r\n'));
    } else {
      const val = renderTemplate(String(v), vars, used);
      chunks.push(Buffer.from(`--${boundary}\r\nContent-Disposition: form-data; name="${name}"\r\n\r\n${val}\r\n`));
    }
  }
  chunks.push(Buffer.from(`--${boundary}--\r\n`));
  return { body: Buffer.concat(chunks), contentType: `multipart/form-data; boundary=${boundary}` };
}

// ─────────────────────────────────────────── SQL-транспорт (kind:"sql") ──
// Движок «думает» тот же (VUs/workers/stages/warmup/перцентили/вердикт) — меняется лишь транспорт:
// на VU держим ОДИН персистентный клиент СУБД (psql/sqlline/…) = одно соединение. Латентность —
// СЕРВЕРНАЯ (парсим таймер драйвера), а не wall-clock (без шума docker-exec pipe). Zero-dep: шелимся
// в родной CLI СУБД (как уже делаем для docker stats). Рамка SQL_MARK (echo после запроса) отделяет
// результат одного запроса от следующего в общем потоке stdout (+ merge stderr → ошибки СУБД в тот же буфер).
const SQL_MARK = '__LOADGEN_ROW_DONE__';
// markCmd: команды после КАЖДОГО запроса. Для psql статус берём В КАНАЛЕ stdout через :ERROR/:SQLSTATE
// (echo ПЕРЕД маркером) — это надёжно, в отличие от парсинга stderr: stderr и stdout — разные пайпы,
// порядок их data-событий не гарантирован, поэтому ошибка могла «протечь» в соседний запрос (ложный OK/ошибка).
const SQL_DRIVERS = {
  psql: { flags: ['-q', '-A'], init: ['\\timing on'],
    // серверный лимит на запрос: даже если убьём локальный клиент (docker exec НЕ пробрасывает сигнал
    // в контейнерный psql), backend Postgres сам снимет запрос по statement_timeout и освободится —
    // иначе осиротевшие backend'ы копятся к max_connections и кладут ту самую цель, что мы измеряем.
    stmtTimeout: 'SET statement_timeout = {ms}',
    markCmd: '\\echo LGSTAT=:ERROR :SQLSTATE\n\\echo ' + SQL_MARK,
    statusRe: 'LGSTAT=(true|false)\\s+(\\S*)', // in-band статус: авторитетный признак ошибки
    timeRe: 'Time:\\s*([\\d.]+)\\s*ms', rowsRe: '\\((\\d+) rows?\\)', errRe: '^(?:ERROR|FATAL|PANIC):' },
  // sqlline (Apache Ignite thin JDBC) — рабочая заготовка; in-band статуса как :ERROR нет, поэтому ошибка
  // распознаётся по stderr (best-effort). Тонкости под версию переопределяются в sql-блоке.
  sqlline: { flags: ['--silent=true', '--outputformat=csv'], init: ['!set timing true'], markCmd: '!echo ' + SQL_MARK,
    timeRe: '\\(([\\d.]+)\\s*seconds?\\)', rowsRe: '(\\d+)\\s+rows? selected', errRe: '^(?:Error|Exception)' },
};
// Детект мутирующего SQL для write-guard. Эвристика по ключевым словам — заведомо неполна (например
// `select pg_terminate_backend(...)` не поймать), поэтому лучше пере-флагнуть, чем пропустить: включаем
// и операционные команды (VACUUM/REFRESH/…), и `SELECT ... INTO`. Для SQL read-only не гарантируется —
// в отличие от HTTP, где метод авторитетен (см. предупреждение в validateScenario).
const SQL_WRITE_RE = /\b(insert|update|delete|create|drop|truncate|alter|merge|grant|revoke|copy|call|into|vacuum|refresh|reindex|cluster|comment|lock|do)\b/i;

/** Разбор ответа драйвера из общего буфера (до рамки MARK): серверная латентность / строки / ошибка. Чистая — тестируемая. */
function parseSqlChunk(chunk, cfg, wallMs) {
  const tm = cfg.timeRe && chunk.match(cfg.timeRe);
  let ms = tm ? Number(tm[1]) : wallMs; // серверное время драйвера точнее wall-clock (без шума pipe)
  if (tm && cfg.timeUnit === 'sec') ms *= 1000; // sqlline печатает секунды
  if (!Number.isFinite(ms)) ms = wallMs;
  const errLine = () => (cfg.errRe && chunk.split('\n').find((l) => cfg.errRe.test(l)) || '').trim();
  // 1) НАДЁЖНЫЙ путь: in-band статус в stdout (psql :ERROR) — не зависит от гонки stderr↔stdout
  if (cfg.statusRe) {
    const sm = chunk.match(cfg.statusRe);
    if (sm) {
      if (sm[1] === 'true') return { ms, ok: false, kind: 'query', errMsg: (errLine() || `ошибка SQL (SQLSTATE ${sm[2] || '?'})`).slice(0, 200), rows: 0 };
      const rm = cfg.rowsRe && chunk.match(cfg.rowsRe); // статус=false авторитетен: игнорируем «протёкший» из соседа ERROR-текст
      return { ms, ok: true, rows: rm ? Number(rm[1]) : 0 };
    }
    // статус ожидался, но не найден (частичный вывод) — падаем на stderr-эвристику ниже
  }
  // 2) FALLBACK для драйверов без in-band статуса (custom/sqlline): признак ошибки — текст на stderr
  if (cfg.errRe && cfg.errRe.test(chunk)) return { ms, ok: false, kind: 'query', errMsg: (errLine() || 'ошибка SQL').slice(0, 200), rows: 0 };
  const rm = cfg.rowsRe && chunk.match(cfg.rowsRe);
  return { ms, ok: true, rows: rm ? Number(rm[1]) : 0 };
}

/** Конфиг драйвера SQL: пресет по имени + переопределения из sql-блока. Резолвится ОДИН раз в validate. */
function resolveSqlDriver(sql) {
  const preset = SQL_DRIVERS[sql.driver] || {};
  const reOrNull = (v, presetStr, flags) => v ? new RegExp(v, flags) : (presetStr ? new RegExp(presetStr, flags) : null);
  return {
    command: [...sql.command, ...(sql.flags || preset.flags || [])],
    init: sql.init || preset.init || [],
    stmtTimeout: sql.stmtTimeout || preset.stmtTimeout || null, // серверный лимит на запрос ({ms} → timeoutMs)
    markCmd: sql.mark || preset.markCmd || ('\\echo ' + SQL_MARK),
    mark: SQL_MARK,
    statusRe: reOrNull(sql.statusRegex, preset.statusRe), // надёжный in-band статус (psql :ERROR)
    timeRe: reOrNull(sql.timeRegex, preset.timeRe),
    rowsRe: reOrNull(sql.rowsRegex, preset.rowsRe),
    errRe: reOrNull(sql.errorRegex, preset.errRe, 'm'),
    timeUnit: (sql.driver === 'sqlline') ? 'sec' : 'ms', // sqlline печатает секунды
  };
}

/** Открыть персистентную SQL-сессию: один клиентский процесс = одно соединение.
 *  cfg по умолчанию — драйвер сценария (kind:"sql"); для pipeline verify передаётся scn._verifyDriver. */
function openSqlSession(scn, cfg = scn._sqlDriver) {
  const [cmd, ...args] = cfg.command;
  const sess = { dead: false, _err: null, _pending: null, _buf: '' };
  let ps;
  try { ps = spawn(cmd, args, { stdio: ['pipe', 'pipe', 'pipe'] }); }
  catch (e) { sess.dead = true; sess._err = `не удалось запустить клиент СУБД (${cmd}): ${e.message}`; return sess; }
  sess._ps = ps;
  const onData = (d) => {
    sess._buf += d.toString();
    let i;
    while ((i = sess._buf.indexOf(cfg.mark)) >= 0) {
      const chunk = sess._buf.slice(0, i);
      sess._buf = sess._buf.slice(i + cfg.mark.length);
      const p = sess._pending; sess._pending = null;
      if (p) p(chunk);
    }
  };
  ps.stdout.on('data', onData);
  ps.stderr.on('data', onData); // merge: ошибки СУБД (stderr) в тот же буфер — рамка mark ловит их до себя
  const fail = (msg) => { sess.dead = true; if (!sess._err) sess._err = msg; const p = sess._pending; sess._pending = null; if (p) p(null); };
  ps.on('error', (e) => fail(`клиент СУБД: ${e.message}`));
  ps.on('exit', (code) => fail(code ? `клиент СУБД вышел с кодом ${code}` : 'клиент СУБД закрыл соединение'));
  // серверный statement_timeout (если драйвер его поддерживает) идёт ПЕРВЫМ — бэкенд сам снимет затянувшийся запрос
  const initLines = cfg.stmtTimeout ? [cfg.stmtTimeout.replace('{ms}', String(scn.timeoutMs)), ...cfg.init] : cfg.init;
  try { for (const line of initLines) ps.stdin.write(line + '\n'); } catch (e) { fail(`init СУБД: ${e.message}`); }

  sess.query = (sql, timeoutMs) => new Promise((resolve) => {
    if (sess.dead) return resolve({ ms: 0, ok: false, kind: 'conn', errMsg: sess._err || 'соединение закрыто' });
    const t0 = performance.now();
    let done = false;
    const timer = setTimeout(() => {
      if (done) return; done = true; sess._pending = null; sess.dead = true;
      try { ps.kill(); } catch { /* уже мёртв */ }
      resolve({ ms: performance.now() - t0, ok: false, kind: 'timeout', errMsg: `запрос не завершился за ${timeoutMs}ms` });
    }, timeoutMs);
    sess._pending = (chunk) => {
      if (done) return; done = true; clearTimeout(timer);
      if (chunk == null) return resolve({ ms: performance.now() - t0, ok: false, kind: 'conn', errMsg: sess._err || 'клиент СУБД закрылся' });
      resolve(parseSqlChunk(chunk, cfg, performance.now() - t0));
    };
    try { ps.stdin.write(sql + ';\n' + cfg.markCmd + '\n'); }
    catch (e) { done = true; clearTimeout(timer); sess.dead = true; resolve({ ms: 0, ok: false, kind: 'conn', errMsg: `запись в клиент СУБД: ${e.message}` }); }
  });

  sess.close = () => new Promise((res) => {
    // закрываем, даже если sess.dead: процесс мог быть помечен мёртвым, но ещё жив (напр. init-ошибка) —
    // важно не оставить сироту. Если процесс уже реально завершён (exitCode/signalCode) — выходим сразу.
    if (!ps || ps.exitCode !== null || ps.signalCode !== null) return res();
    let settled = false;
    const fin = () => { if (settled) return; settled = true; res(); };
    ps.on('exit', fin);
    try { ps.stdin.end(); } catch { /* ignore */ }
    setTimeout(() => { try { ps.kill(); } catch { /* ignore */ } fin(); }, 500);
  });
  return sess;
}

/** Один SQL-«вызов»: рендер запроса ({{var}}), выполнение в персистентной сессии, checks (minRows/maxMs). */
async function sqlCallOnce(scn, req, vars, conn) {
  const used = {};
  let sql;
  try { sql = renderTemplate(req.sql, vars, used); }
  catch (e) { return { ms: 0, status: 0, ok: false, kind: 'config', errMsg: e.message, used }; }
  if (!conn || conn.dead) return { ms: 0, status: 0, ok: false, kind: 'conn', errMsg: (conn && conn._err) || 'нет живого SQL-соединения', used };
  const r = await conn.query(sql, scn.timeoutMs);
  r.used = used;
  if (!r.ok) { r.status = r.status ?? 0; return r; }
  const c = req.checks || {};
  const fails = [];
  if (c.minRows !== undefined && (r.rows || 0) < c.minRows) fails.push(`minRows: вернулось ${r.rows}, ожидалось >= ${c.minRows}`);
  if (c.notEmpty === true && (r.rows || 0) < 1) fails.push('notEmpty: запрос не вернул строк');
  if (fails.length) return { ms: r.ms, status: 'OK', ok: false, kind: 'check', errMsg: fails.join('; '), snippet: `rows=${r.rows}`, used, rows: r.rows };
  const slow = c.maxMs !== undefined && r.ms > c.maxMs;
  return { ms: r.ms, status: 'OK', ok: true, slow, used, rows: r.rows };
}

// ─────────────────────────────────── pipeline-режим (kind:"pipeline") ──
// Сквозная задержка конвейера: produce сообщение с корреляционным ключом {{corr}} → поллим целевой
// verify-запрос, пока запись не материализуется → лаг = produce→видно. Это SLA конвейеров Kafka→PG/Ignite:
// «за сколько событие долетает от брокера до витрины и сколько теряется». produce/verify — те же
// persistent-клиенты через CLI (zero-dep). produce fire-and-forget (нет ack), время старта ≈ момент записи в stdin.

/** Персистентный продьюсер (kafka-console-producer/kcat -P): пишем сообщения построчно в stdin. */
function openProducer(scn) {
  const [cmd, ...args] = scn.produce.command;
  const p = { dead: false, _err: null };
  let ps;
  try { ps = spawn(cmd, args, { stdio: ['pipe', 'ignore', 'pipe'] }); }
  catch (e) { p.dead = true; p._err = `не удалось запустить продьюсер (${cmd}): ${e.message}`; return p; }
  p._ps = ps;
  let errTail = '';
  ps.stderr.on('data', (d) => { errTail = (errTail + d.toString()).slice(-500); });
  ps.on('error', (e) => { p.dead = true; if (!p._err) p._err = `продьюсер: ${e.message}`; });
  ps.on('exit', (code) => { p.dead = true; if (!p._err && code) p._err = `продьюсер вышел (код ${code}): ${errTail.trim().slice(-160)}`; });
  p.send = (msg) => { try { ps.stdin.write(msg + '\n'); return true; } catch (e) { p.dead = true; p._err = e.message; return false; } };
  p.close = () => new Promise((res) => {
    if (!ps || ps.exitCode !== null || ps.signalCode !== null) return res();
    let s = false; const fin = () => { if (s) return; s = true; res(); };
    ps.on('exit', fin);
    try { ps.stdin.end(); } catch { /* ignore */ } // end флашит буфер продьюсера и завершает процесс
    setTimeout(() => { try { ps.kill(); } catch { /* ignore */ } fin(); }, 800);
  });
  return p;
}

/** Гоняет vuCount конвейерных VU: produce→poll→лаг. Совместимо с single и worker-режимом. */
async function runPipelineSlice({ scn, stats, vuCount, vuBase, vuStride, totalVus, endAt, rampMs, effectiveMaxRps, ctx, startedAt }) {
  const rateGate = makeRateGate(effectiveMaxRps);
  const [ttMin, ttMax] = scn.load.thinkTimeMs;
  const stages = scn.load.stages;
  const t0 = startedAt || Date.now();
  const stride = vuStride || 1;
  const pollMs = scn.pipeline.pollIntervalMs;
  const lagTimeoutMs = scn.pipeline.timeoutMs;
  const step = { name: 'pipeline' }; // единственный «запрос» — сквозной цикл конвейера
  const runVU = async (localIdx) => {
    const globalIdx = (vuBase || 0) + localIdx * stride;
    if (!stages && rampMs) await sleep((rampMs * globalIdx) / Math.max(1, totalVus));
    let producer = openProducer(scn);
    let verify = openSqlSession(scn, scn._verifyDriver);
    try {
      while (Date.now() < endAt && !ctx.aborted) {
        if (stages && globalIdx >= stageTargetAt(stages, Date.now() - t0)) { await sleep(200); continue; }
        if (rateGate) await rateGate();
        if (producer.dead) { await producer.close(); producer = openProducer(scn); }
        if (verify.dead) { await verify.close(); verify = openSqlSession(scn, scn._verifyDriver); }
        const corr = randomUUID();
        const vars = { ...scn._resolvedVars, corr };
        const used = {};
        let msg, query;
        // ОБЩИЙ used: list-переменная должна дать ОДНО значение и в message, и в query, иначе verify ищет не то, что послали → ложное «застряло»
        try { msg = renderTemplate(scn.produce.message, vars, used); query = renderTemplate(scn.verify.query, vars, used); }
        catch (e) { stats.record(step, { ms: 0, status: 0, ok: false, kind: 'config', errMsg: e.message }, Date.now()); break; }
        delete used.corr; // corr уникален на итерацию — не засоряем разбивку «ВЛИЯНИЕ ПАРАМЕТРОВ»
        const tProduce = performance.now();
        if (!producer.send(msg)) { stats.record(step, { ms: 0, status: 0, ok: false, kind: 'produce', errMsg: producer._err || 'produce не удался', used }, Date.now()); continue; }
        // поллим verify до появления записи или ТАЙМАУТА ЛАГА. Отличаем «застряло» (timeout истёк) от
        // «прервано концом теста» (endAt/abort раньше timeout): последнее НЕ считаем застрявшим — событие
        // уже в топике и материализуется позже, просто тест закончился (как прерванная flow-сессия).
        let materialized = false, lastErr = null, stuck = false, verifyFailed = false;
        while (!ctx.aborted && Date.now() < endAt) {
          const lagLeft = lagTimeoutMs - (performance.now() - tProduce);
          if (lagLeft <= 0) { stuck = true; break; }
          if (verify.dead) { // verify-клиент умер (таймаут/обрыв) — переоткрываем; не поднялся → сбой инфраструктуры verify, НЕ «застряло»
            await verify.close(); verify = openSqlSession(scn, scn._verifyDriver);
            if (verify.dead) { lastErr = verify._err; verifyFailed = true; break; }
          }
          // бюджет одного verify-запроса — не больше остатка окна лага (иначе зависший verify раздул бы лаг до scn.timeoutMs)
          const r = await verify.query(query, Math.max(1, Math.min(scn.timeoutMs, lagLeft)));
          if (r.ok && (r.rows || 0) >= 1) { materialized = true; break; }
          if (!r.ok) lastErr = r.errMsg;
          if ((performance.now() - tProduce) >= lagTimeoutMs) { stuck = true; break; }
          await sleep(pollMs);
        }
        const lagMs = performance.now() - tProduce;
        if (materialized) stats.record(step, { ms: lagMs, status: 'OK', ok: true, used }, Date.now());
        else if (producer.dead) stats.record(step, { ms: lagMs, status: 0, ok: false, kind: 'produce', errMsg: producer._err || 'продьюсер упал после send — сообщение потеряно на стороне отправителя', used }, Date.now());
        else if (verifyFailed) stats.record(step, { ms: lagMs, status: 0, ok: false, kind: 'verify', errMsg: `verify-клиент недоступен: ${lastErr || ''}`.trim(), used }, Date.now());
        else if (stuck) stats.record(step, { ms: lagMs, status: 0, ok: false, kind: 'stuck', errMsg: lastErr || `не материализовалось за ${lagTimeoutMs}ms`, used }, Date.now());
        // else: прервано концом теста/abort — в статистику не идёт (лаг не измерен до конца)
        if (ttMax > 0) await sleep(ttMin + Math.random() * (ttMax - ttMin));
      }
    } finally { await producer.close(); await verify.close(); }
  };
  await Promise.all(Array.from({ length: vuCount }, (_, i) => runVU(i)));
}

async function callOnce(scn, req, vars, token, conn) {
  if (scn.kind === 'sql') return sqlCallOnce(scn, req, vars, conn);
  let url, body;
  const used = {};
  const rawHeaders = { ...(scn.headers || {}), ...(req.headers || {}) };
  const headers = {};
  const hasCT = () => Object.keys(headers).some((h) => h.toLowerCase() === 'content-type');
  // form/multipart ЖЁСТКО задают свой Content-Type (глобальный/ручной JSON-CT сломал бы парсинг тела)
  const forceCT = (v) => { for (const h of Object.keys(headers)) if (h.toLowerCase() === 'content-type') delete headers[h]; headers['Content-Type'] = v; };
  try {
    // плейсхолдеры в заголовках подставляем так же, как в path/body (общий used → одно значение на вызов):
    // без этого capture→заголовок и {{uuid}}/{{var}} в headers уходили бы литералом "Bearer {{tok}}"
    for (const [k, v] of Object.entries(rawHeaders)) headers[k] = renderTemplate(String(v), vars, used);
    if (token) headers['Authorization'] = `Bearer ${token}`; // токен задаётся программно, поверх заголовков
    url = new URL(renderTemplate(req.path, vars, used), scn.baseUrl).toString();
    if (req.body !== undefined) {
      const bt = req.bodyType || 'json';
      if (bt === 'form') {
        body = encodeForm(req.body, vars, used);
        forceCT('application/x-www-form-urlencoded');
      } else if (bt === 'multipart') {
        const mp = buildMultipart(scn._dir, req.body, vars, used);
        body = mp.body;
        forceCT(mp.contentType); // Content-Type обязан нести сгенерированный boundary
      } else {
        body = renderTemplate(typeof req.body === 'string' ? req.body : JSON.stringify(req.body), vars, used);
        if (!hasCT()) headers['Content-Type'] = 'application/json'; // json — не перетираем ручной CT (напр. charset)
      }
    }
  } catch (e) {
    return { ms: 0, status: 0, ok: false, kind: 'config', errMsg: e.message, used };
  }
  const t0 = performance.now();
  try {
    const res = await fetch(url, { method: req.method, headers, body, signal: AbortSignal.timeout(scn.timeoutMs) });
    const ttfb = performance.now() - t0; // fetch резолвится по приходу ЗАГОЛОВКОВ = время до первого байта
    const text = req.method === 'HEAD' ? '' : await res.text();
    const total = performance.now() - t0;
    const result = evaluateResponse(req, res.status, text, total, used);
    result.ttfb = Math.min(ttfb, total); // TTFB (сервер+сеть до 1-го байта); остаток total-ttfb = скачивание тела
    if (req.capture) result.text = text; // тело нужно для извлечения переменных в цепочке
    return result;
  } catch (e) {
    return { ms: performance.now() - t0, status: 0, ok: false, kind: errKind(e), errMsg: errText(e), used };
  }
}

/**
 * Извлекает значения из тела ответа шага и связывает их с session-переменными (для цепочек).
 * capture: { "имяПеременной": "json.путь" }. Все объявленные захваты обязаны разрешиться.
 * Мутирует vars. Возвращает { ok, error }.
 */
function applyCaptures(step, text, vars) {
  let json;
  try { json = JSON.parse(text); } catch { return { ok: false, error: `capture: тело ответа шага "${step.name}" — не JSON` }; }
  for (const [name, path] of Object.entries(step.capture)) {
    let v;
    try { v = extractPath(json, path); } catch { v = undefined; }
    if (Array.isArray(v)) v = v[0];
    if (v === undefined || v === null || (typeof v === 'object')) {
      return { ok: false, error: `capture: путь "${path}" (→ {{${name}}}) не дал скалярного значения в ответе шага "${step.name}"` };
    }
    vars[name] = String(v);
  }
  return { ok: true };
}

// ─────────────────────────────────────────────── статистика и отчёт ──

const MAX_TRACKED_VALUES = 50; // максимум различных значений параметра в разбивке (дальше — «(прочие)»)
const OTHERS_BUCKET = '(прочие)';
const MAX_LAT_SAMPLES = 1_000_000; // защита памяти/spread: перцентили считаются по первым N выборкам на запрос
const MAX_CELL_LAT = 100_000;

// ─────────────────────────────────────────────── исполнение нагрузки (общее для 1 потока и воркеров) ──

function makePicker(requests) {
  const cum = [];
  let acc = 0;
  for (const r of requests) { acc += r.weight; cum.push([acc, r]); }
  return () => {
    const x = Math.random() * acc;
    for (const [c, r] of cum) if (x < c) return r;
    return cum[cum.length - 1][1];
  };
}

/** Целевое число активных VU в момент elapsedMs (кусочно-линейная интерполяция ступеней, старт с 0). */
function stageTargetAt(stages, elapsedMs) {
  let t = 0, prev = 0;
  for (const st of stages) {
    const durMs = st.durationSec * 1000;
    if (elapsedMs <= t + durMs) {
      const frac = durMs > 0 ? (elapsedMs - t) / durMs : 1;
      return prev + (st.vus - prev) * frac;
    }
    t += durMs; prev = st.vus;
  }
  return prev; // после последней ступени держим её уровень (обычно 0)
}

function makeRateGate(maxRps) {
  if (!maxRps || maxRps === Infinity) return null;
  // равномерное распределение: следующий грант не раньше next; корректно работает и при maxRps < 10.
  const intervalMs = 1000 / maxRps;
  let next = 0;
  return async () => {
    const now = Date.now();
    if (next < now) next = now;
    const wait = next - now;
    next += intervalMs;
    if (wait > 0) await sleep(wait);
  };
}

/**
 * Одна сессия цепочки: шаги по порядку, захват переменных из ответов, обрыв на первом провале.
 * session-переменные накладываются поверх глобальных. Возвращает { interrupted } —
 * true, если сессию оборвал конец теста/прерывание (такую сессию не считаем в flow-статистику).
 */
async function runFlowSession(scn, flow, token, stats, ttMin, ttMax, onSample, ctx, endAt, rateGate, conn) {
  const vars = { ...scn._resolvedVars };
  let completed = true, brokeAt = null, interrupted = false;
  let svcMs = 0; // сумма ВРЕМЕНИ ОТВЕТОВ шагов — без rate-gate пауз и think-time (это латентность цели, не пейсинг генератора)
  for (let i = 0; i < flow.steps.length; i++) {
    if (Date.now() >= endAt || ctx.aborted) { interrupted = true; break; }
    if (rateGate) await rateGate();
    const step = flow.steps[i];
    const r = await callOnce(scn, step, vars, token, conn);
    let rec = r;
    if (r.ok && step.capture && scn.kind !== 'sql') { // capture в SQL пока не поддержан — не пытаемся (у SQL-записи нет r.text)
      const cap = applyCaptures(step, r.text, vars);
      if (!cap.ok) rec = { ms: r.ms, status: r.status, ok: false, kind: 'capture', errMsg: cap.error, used: r.used };
    }
    svcMs += rec.ms || 0;
    stats.record(step, rec, Date.now());
    if (onSample && rec.ok) onSample(rec.ms);
    if (!rec.ok) { completed = false; brokeAt = step.name; break; }
    if (ttMax > 0 && i < flow.steps.length - 1) await sleep(ttMin + Math.random() * (ttMax - ttMin));
  }
  if (flow._track && !interrupted) stats.recordFlow(flow.name, completed, svcMs, brokeAt, Date.now());
  return { interrupted };
}

/** Гоняет vuCount виртуальных пользователей до endAt, записывая в stats. Используется и в главном потоке, и в воркере. */
async function runLoadSlice({ scn, preToken, stats, vuCount, vuBase, vuStride, totalVus, endAt, rampMs, effectiveMaxRps, ctx, loginFailures, onSample, startedAt }) {
  const pick = makePicker(scn._flows);
  const rateGate = makeRateGate(effectiveMaxRps);
  const [ttMin, ttMax] = scn.load.thinkTimeMs;
  const stages = scn.load.stages;
  const t0 = startedAt || Date.now();
  const stride = vuStride || 1;
  const isSql = scn.kind === 'sql';
  const runVU = async (localIdx) => {
    // round-robin по глобальному индексу: активный набор ступеней [0,target) равномерно
    // ложится на все потоки (иначе на ramp работал бы только поток с младшими индексами)
    const globalIdx = (vuBase || 0) + localIdx * stride;
    const token = preToken; // общий токен из pre-flight (логин один раз, без шторма на старте)
    void loginFailures;
    if (!stages && rampMs) await sleep((rampMs * globalIdx) / Math.max(1, totalVus));
    let conn = isSql ? openSqlSession(scn) : null; // персистентное соединение на VU (для SQL)
    try {
      while (Date.now() < endAt && !ctx.aborted) {
        if (stages) {
          // VU активен, только если его глобальный индекс попадает в текущий целевой уровень ступеней
          if (globalIdx >= stageTargetAt(stages, Date.now() - t0)) { await sleep(200); continue; }
        }
        if (isSql && (!conn || conn.dead)) { if (conn) await conn.close(); conn = openSqlSession(scn); } // закрыть мёртвую (не оставить сироту) и переоткрыть
        const flow = pick();
        await runFlowSession(scn, flow, token, stats, ttMin, ttMax, onSample, ctx, endAt, rateGate, conn);
        // think-time между сессиями (для одношаговых flow = пауза между итерациями, как раньше)
        if (ttMax > 0) await sleep(ttMin + Math.random() * (ttMax - ttMin));
      }
    } finally { if (conn) await conn.close(); }
  };
  await Promise.all(Array.from({ length: vuCount }, (_, i) => runVU(i)));
}

// ─── сериализация статистики для передачи между воркерами и главным потоком ──

function serializeStats(stats) {
  const per = {};
  for (const [name, s] of stats.per) {
    per[name] = {
      count: s.count, errors: s.errors, slow: s.slow, latDropped: s.latDropped || 0,
      lat: s.lat,
      statuses: [...s.statuses.entries()],
      errSamples: [...s.errSamples.entries()],
      perVar: [...s.perVar.entries()].map(([vn, m]) => [vn, [...m.entries()].map(([val, c]) => [val, { count: c.count, errors: c.errors, lat: c.lat }])]),
    };
  }
  const flowStats = [...stats.flowStats.entries()].map(([n, f]) => [n, { started: f.started, completed: f.completed, durations: f.durations, breaks: [...f.breaks.entries()] }]);
  return { total: stats.total, errors: stats.errors, startedAt: stats.startedAt, endedAt: stats.endedAt, warmupSkipped: stats.warmupSkipped || 0, per, flowStats };
}

function mergeStats(parts, steps) {
  const base = makeStats(steps);
  base.startedAt = Math.min(...parts.map((p) => p.startedAt));
  base.endedAt = Math.max(...parts.map((p) => p.endedAt));
  for (const part of parts) {
    base.total += part.total;
    base.errors += part.errors;
    base.warmupSkipped += part.warmupSkipped || 0;
    for (const [name, ps] of Object.entries(part.per)) {
      const s = base.per.get(name);
      if (!s) continue;
      s.count += ps.count; s.errors += ps.errors; s.slow += ps.slow;
      s.latDropped = (s.latDropped || 0) + (ps.latDropped || 0);
      for (const pair of ps.lat) { if (s.lat.length < MAX_LAT_SAMPLES) s.lat.push(pair); else s.latDropped = (s.latDropped || 0) + 1; }
      for (const [k, v] of ps.statuses) s.statuses.set(k, (s.statuses.get(k) || 0) + v);
      for (const [k, v] of ps.errSamples) if (!s.errSamples.has(k)) s.errSamples.set(k, v);
      for (const [vn, cells] of ps.perVar) {
        let m = s.perVar.get(vn);
        if (!m) { m = new Map(); s.perVar.set(vn, m); }
        for (const [val, c] of cells) {
          const bucket = m.has(val) || m.size < MAX_TRACKED_VALUES ? val : OTHERS_BUCKET;
          let cell = m.get(bucket);
          if (!cell) { cell = { count: 0, errors: 0, lat: [] }; m.set(bucket, cell); }
          cell.count += c.count; cell.errors += c.errors;
          for (const ms of c.lat) if (cell.lat.length < MAX_CELL_LAT) cell.lat.push(ms);
        }
      }
    }
    for (const [name, pf] of part.flowStats || []) {
      let f = base.flowStats.get(name);
      if (!f) { f = { started: 0, completed: 0, durations: [], breaks: new Map() }; base.flowStats.set(name, f); }
      f.started += pf.started; f.completed += pf.completed;
      for (const d of pf.durations) if (f.durations.length < MAX_LAT_SAMPLES) f.durations.push(d);
      for (const [step, cnt] of pf.breaks) f.breaks.set(step, (f.breaks.get(step) || 0) + cnt);
    }
  }
  return base;
}

// ─── монитор метрик ЦЕЛИ (docker stats / Prometheus) во время прогона ──

/** "616MiB / 7.606GiB" → 616 (МБ). Поддерживает B/KiB/MiB/GiB/kB/MB/GB. Возвращает null, если не распознал. */
function parseMemMB(s) {
  const m = String(s).trim().match(/^([\d.]+)\s*([KMGT]?i?B)/i);
  if (!m) return null;
  const n = parseFloat(m[1]);
  if (Number.isNaN(n)) return null;
  const unit = m[2].toLowerCase();
  const factor = {
    b: 1 / 1048576, kib: 1 / 1024, mib: 1, gib: 1024, tib: 1048576,
    kb: 1e3 / 1048576, mb: 1e6 / 1048576, gb: 1e9 / 1048576, tb: 1e12 / 1048576,
  }[unit];
  if (factor === undefined) return null;
  return Number((n * factor).toFixed(1));
}

/** Запускает захват вывода команды с таймаутом (для `docker stats`). */
function execCapture(cmd, args, timeoutMs) {
  return new Promise((resolve, reject) => {
    let p;
    try { p = spawn(cmd, args, { stdio: ['ignore', 'pipe', 'pipe'] }); }
    catch (e) { reject(e); return; }
    let out = '', err = '';
    p.stdout.on('data', (d) => { out += d; });
    p.stderr.on('data', (d) => { err += d; });
    const t = setTimeout(() => { p.kill(); reject(new Error('таймаут')); }, timeoutMs);
    p.on('close', (code) => { clearTimeout(t); code === 0 ? resolve(out) : reject(new Error(err.trim() || `exit ${code}`)); });
    p.on('error', (e) => { clearTimeout(t); reject(e); });
  });
}

/**
 * Опрашивает метрики ЦЕЛИ во время прогона (на главном потоке, независимо от нагрузки).
 * Возвращает { stop() → { intervalSec, samples:[{t,values}], errors:[] } } или null, если monitor не задан.
 */
/** Сумма LAG по партициям из вывода `kafka-consumer-groups --describe`. Чистая — тестируемая.
 *  Строка данных: колонки GROUP TOPIC PARTITION CURRENT-OFFSET LOG-END-OFFSET LAG …; PARTITION — число, LAG — cols[5].
 *  Возвращает { lag, rows } или null, если строк данных нет (заголовок/предупреждения/пустая группа). */
function parseKafkaLag(output) {
  let total = 0, rows = 0;
  for (const line of String(output).split(/\r?\n/)) {
    const cols = line.trim().split(/\s+/);
    if (cols.length < 6 || !/^\d+$/.test(cols[2])) continue;
    const lag = Number(cols[5]);
    if (Number.isFinite(lag)) { total += lag; rows++; }
  }
  return rows ? { lag: total, rows } : null;
}

function startTargetMonitor(scn) {
  const m = scn.monitor;
  if (!m || (!m.docker && !m.prometheus && !m.kafka)) return null;
  const intervalSec = m.intervalSec || 5;
  const t0 = Date.now();
  const samples = [];
  const errors = new Set();
  let stopped = false;

  const pollDocker = async () => {
    const values = {};
    // опрашиваем КАЖДЫЙ контейнер отдельно: иначе один отсутствующий/упавший (docker stats exit 1,
    // пустой stdout) обнулил бы метрики ВСЕХ контейнеров на этом опросе.
    await Promise.all(m.docker.containers.map(async (c) => {
      try {
        const out = await execCapture('docker', ['stats', '--no-stream', '--format', '{{.Name}};{{.CPUPerc}};{{.MemUsage}}', c], 8000);
        for (const line of out.trim().split(/\r?\n/)) {
          const [name, cpu, mem] = line.split(';');
          if (!name) continue;
          const cpuN = parseFloat(cpu);
          const memMB = parseMemMB(mem);
          if (!Number.isNaN(cpuN)) values[`${name} CPU %`] = cpuN;
          if (memMB != null) values[`${name} MEM МБ`] = memMB;
        }
      } catch (e) { errors.add(`docker stats "${c}": ${errText(e)} (контейнер запущен? docker в PATH?)`); }
    }));
    return values;
  };

  const pollProm = async () => {
    const values = {};
    for (const [name, q] of Object.entries(m.prometheus.queries || {})) {
      try {
        const url = new URL('/api/v1/query', m.prometheus.url);
        url.searchParams.set('query', q);
        const res = await fetch(url, { signal: AbortSignal.timeout(8000) });
        const j = await res.json();
        const rt = j?.data?.resultType;
        const r = j?.data?.result || [];
        if (rt && rt !== 'vector' && rt !== 'scalar') {
          errors.add(`prometheus "${name}": результат типа "${rt}" (не мгновенный вектор) — оберните в функцию/агрегацию (например rate(...), sum(...)), чтобы получить одно значение`);
        } else if (rt === 'scalar') {
          const v = Number(r?.[1]);
          if (!Number.isNaN(v)) values[name] = Number(v.toFixed(2));
          else errors.add(`prometheus "${name}": scalar-значение не число (${JSON.stringify(r?.[1])})`);
        } else if (r.length) {
          const v = Number(r[0].value?.[1]);
          if (!Number.isNaN(v)) values[name] = Number(v.toFixed(2));
          else errors.add(`prometheus "${name}": значение не число (${JSON.stringify(r[0].value?.[1])})`);
          if (r.length > 1) errors.add(`prometheus "${name}": запрос вернул ${r.length} рядов, взят первый — добавьте агрегацию (sum/avg by) в PromQL`);
        } else errors.add(`prometheus "${name}": запрос не вернул данных (проверьте PromQL и что цель скрейпится)`);
      } catch (e) { errors.add(`prometheus "${name}": ${errText(e)}`); }
    }
    return values;
  };

  // consumer-lag Kafka: для каждой группы `kafka-consumer-groups --describe --group G`, сумма LAG по партициям.
  // Растущий лаг = консьюмер не успевает (backpressure) — показывает, ГДЕ узкое место конвейера.
  const pollKafka = async () => {
    const values = {};
    await Promise.all(m.kafka.groups.map(async (g) => {
      try {
        const [cmd, ...base] = m.kafka.command;
        const out = await execCapture(cmd, [...base, '--describe', '--group', g], 15000);
        const parsed = parseKafkaLag(out);
        if (parsed) values[`${g} lag`] = parsed.lag;
        else errors.add(`kafka lag "${g}": нет активных партиций/консьюмеров (группа существует? есть назначенные партиции?)`);
      } catch (e) { errors.add(`kafka lag "${g}": ${errText(e)} (kafka-consumer-groups в PATH/докере? bootstrap верный?)`); }
    }));
    return values;
  };

  let inFlight = false;
  const poll = async () => {
    if (stopped || inFlight) return; // не запускаем новый опрос, пока не завершился прошлый (docker stats может быть дольше интервала)
    inFlight = true;
    try {
      const [d, p, k] = await Promise.all([m.docker ? pollDocker() : {}, m.prometheus ? pollProm() : {}, m.kafka ? pollKafka() : {}]);
      if (!stopped) samples.push({ t: Math.round((Date.now() - t0) / 1000), values: { ...d, ...p, ...k } });
    } finally { inFlight = false; }
  };
  poll(); // первый замер сразу
  const iv = setInterval(poll, intervalSec * 1000);
  if (iv.unref) iv.unref();
  return { stop() { stopped = true; clearInterval(iv); return { intervalSec, samples, errors: [...errors] }; } };
}

/** Сводка по метрикам цели: min/avg/max/last/пик для каждой метрики + флаг насыщения.
 *  warmupSec>0 — исключаем выборки первых N секунд (как и латентность генератора): cold-start пик
 *  CPU/памяти на прогреве не должен ложно ронять monitor.thresholds. */
function summarizeTargetMetrics(monitorData, warmupSec = 0) {
  if (!monitorData || !monitorData.samples.length) return null;
  let samples = monitorData.samples;
  if (warmupSec > 0) {
    const kept = samples.filter((s) => s.t >= warmupSec);
    if (kept.length) samples = kept; // если ВСЕ выборки попали в разогрев — оставляем сырые (лучше, чем пусто)
  }
  const names = new Set();
  for (const s of samples) for (const k of Object.keys(s.values)) names.add(k);
  const metrics = [];
  for (const name of names) {
    const pts = samples.filter((s) => s.values[name] !== undefined).map((s) => [s.t, s.values[name]]);
    if (!pts.length) continue;
    const vals = pts.map(([, v]) => v);
    const max = Math.max(...vals);
    const peakAtSec = pts.find(([, v]) => v === max)?.[0] ?? 0;
    const isCpu = /cpu/i.test(name);
    const isDockerCpu = / CPU %$/.test(name); // мои docker-метрики: проценты, могут быть >100 (мультиядро)
    // шкалу выводим из значений (имя её не знает): docker — проценты (>=85);
    // prometheus: <=1.5 → доля (>=0.85), 0..100 → проценты (>=85), >100 → скорее счётчик, не флагуем
    let saturatedCpu = false;
    if (isDockerCpu) saturatedCpu = max >= 85;
    else if (isCpu) {
      if (max <= 1.5) saturatedCpu = max >= 0.85;
      else if (max <= 100) saturatedCpu = max >= 85;
    }
    // consumer-lag РАСТЁТ по ходу прогона = консьюмер не успевает (backpressure). Сравниваем конец с началом:
    // устойчиво выше и заметно вырос (а не разовый всплеск, который потом рассосался).
    const first = vals[0], last = vals[vals.length - 1];
    const risingLag = / lag$/.test(name) && last > first + 100 && last > first * 1.5 && last >= max * 0.6;
    metrics.push({
      name, min: Math.min(...vals), avg: Number((vals.reduce((a, b) => a + b, 0) / vals.length).toFixed(1)),
      max, last, peakAtSec, samples: pts.length, saturatedCpu, risingLag,
    });
  }
  return { intervalSec: monitorData.intervalSec, errors: monitorData.errors || [], metrics };
}

// ─── монитор ресурсов генератора (CPU процесса, event-loop lag, память) ──

function startResourceMonitor({ watchEventLoop }) {
  const cpu0 = process.cpuUsage();
  const t0 = performance.now();
  let elMon = null;
  if (watchEventLoop) { elMon = monitorEventLoopDelay({ resolution: 20 }); elMon.enable(); }
  let rssMax = process.memoryUsage().rss;
  let sysFreeMin = os.freemem(); // отслеживаем минимум за прогон, а не снимок в конце
  const iv = setInterval(() => {
    const r = process.memoryUsage().rss; if (r > rssMax) rssMax = r;
    const f = os.freemem(); if (f < sysFreeMin) sysFreeMin = f;
  }, 1000);
  if (iv.unref) iv.unref();
  return {
    finish(extraElLagMaxMs) {
      clearInterval(iv);
      const cpu = process.cpuUsage(cpu0);
      const wallMs = performance.now() - t0;
      const cpuMs = (cpu.user + cpu.system) / 1000;
      const cores = os.cpus().length;
      // сколько ядер в среднем было занято процессом (все потоки, включая воркеры)
      const cpuBusyCores = wallMs > 0 ? cpuMs / wallMs : 0;
      let elLagMeanMs = null, elLagMaxMs = extraElLagMaxMs ?? null;
      if (elMon) {
        elMon.disable();
        elLagMeanMs = elMon.mean / 1e6;
        elLagMaxMs = Math.max(elLagMaxMs ?? 0, elMon.max / 1e6);
      }
      const fMin = Math.min(sysFreeMin, os.freemem());
      return {
        cores,
        cpuMs: Math.round(cpuMs), wallMs: Math.round(wallMs),
        cpuBusyCores: Number(cpuBusyCores.toFixed(2)),
        cpuPctAllCores: Number(((cpuBusyCores / cores) * 100).toFixed(0)),
        elLagMeanMs: elLagMeanMs == null ? null : Number(elLagMeanMs.toFixed(1)),
        elLagMaxMs: elLagMaxMs == null ? null : Number(elLagMaxMs.toFixed(1)),
        rssMaxMB: Math.round(rssMax / 1048576),
        sysFreeMB: Math.round(fMin / 1048576),
        sysTotalMB: Math.round(os.totalmem() / 1048576),
      };
    },
  };
}

function makeStats(steps) {
  const per = new Map();
  for (const r of steps) per.set(r.name, { lat: [], count: 0, errors: 0, slow: 0, statuses: new Map(), errSamples: new Map(), perVar: new Map() });
  return {
    per,
    flowStats: new Map(), // имя цепочки → { started, completed, durations:[], breaks: Map(step→count) }
    startedAt: 0,
    endedAt: 0,
    total: 0,
    errors: 0,
    warmupUntil: 0, // выборки со временем < warmupUntil не учитываются (разогрев)
    warmupSkipped: 0,
    recordFlow(name, completed, ms, brokeAt, now) {
      if (this.warmupUntil && now && now < this.warmupUntil) return; // сессия на разогреве — не учитываем (как и per-request)
      let f = this.flowStats.get(name);
      if (!f) { f = { started: 0, completed: 0, durations: [], breaks: new Map() }; this.flowStats.set(name, f); }
      f.started++;
      if (completed) { f.completed++; if (f.durations.length < MAX_LAT_SAMPLES) f.durations.push(ms); }
      else if (brokeAt) f.breaks.set(brokeAt, (f.breaks.get(brokeAt) || 0) + 1);
    },
    record(req, r, now) {
      if (this.warmupUntil && now < this.warmupUntil) { this.warmupSkipped++; return; } // разогрев — не считаем
      const s = this.per.get(req.name);
      this.total++;
      s.count++;
      const key = r.kind === 'check' ? `${r.status}✗check` : (r.status || r.kind || 'err');
      s.statuses.set(key, (s.statuses.get(key) || 0) + 1);
      if (r.ok) {
        if (s.lat.length < MAX_LAT_SAMPLES) s.lat.push([now, r.ms, r.ttfb ?? r.ms]); else s.latDropped = (s.latDropped || 0) + 1;
        if (r.slow) s.slow++;
      } else {
        this.errors++;
        s.errors++;
        const ek = r.kind === 'check' ? 'check' : `${r.status || r.kind}`;
        if (!s.errSamples.has(ek)) s.errSamples.set(ek, (r.errMsg || r.snippet || '').slice(0, 200));
      }
      // разбивка по значениям параметров ({{var}} → конкретное значение)
      if (r.used) {
        for (const [vn, val] of Object.entries(r.used)) {
          let m = s.perVar.get(vn);
          if (!m) { m = new Map(); s.perVar.set(vn, m); }
          const bucket = m.has(val) || m.size < MAX_TRACKED_VALUES ? val : OTHERS_BUCKET;
          let cell = m.get(bucket);
          if (!cell) { cell = { count: 0, errors: 0, lat: [] }; m.set(bucket, cell); }
          cell.count++;
          if (r.ok) { if (cell.lat.length < MAX_CELL_LAT) cell.lat.push(r.ms); } else cell.errors++;
        }
      }
    },
  };
}

function buildReport(scn, stats, opts = {}) {
  // окно измерения — от конца разогрева (warmupUntil), а не от старта: так RPS считается честно
  const measureStart = stats.warmupUntil && stats.warmupUntil > stats.startedAt ? stats.warmupUntil : stats.startedAt;
  const durSec = Math.max(0.001, (stats.endedAt - measureStart) / 1000);
  const allLat = [];
  const rows = [];
  const errorsDetail = [];
  let latDroppedTotal = 0;
  for (const [name, s] of stats.per) {
    const lat = s.lat.map(([, ms]) => ms).sort((a, b) => a - b);
    const ttfbArr = s.lat.map(([, , tf]) => tf ?? 0).sort((a, b) => a - b);
    const dlArr = s.lat.map(([, ms, tf]) => Math.max(0, ms - (tf ?? ms))).sort((a, b) => a - b);
    for (const pair of s.lat) allLat.push(pair); // без spread: на больших прогонах spread переполняет стек
    latDroppedTotal += s.latDropped || 0;
    const statuses = [...s.statuses.entries()].map(([k, v]) => `${k}:${v}`).join(' ');
    rows.push({
      name,
      count: s.count,
      rps: s.count / durSec,
      errPct: s.count ? (100 * s.errors) / s.count : 0,
      p50: pct(lat, 50), p90: pct(lat, 90), p95: pct(lat, 95), p99: pct(lat, 99),
      max: lat.length ? lat[lat.length - 1] : 0,
      ttfbP95: pct(ttfbArr, 95), downloadP95: pct(dlArr, 95),
      hist: histogram(lat), // для слияния прогонов (merge)
      statuses,
    });
    for (const [ek, sample] of s.errSamples) errorsDetail.push({ request: name, error: ek, sample });
  }
  const latSorted = allLat.map(([, ms]) => ms).sort((a, b) => a - b);
  const ttfbSorted = allLat.map(([, , tf]) => tf ?? 0).sort((a, b) => a - b);
  const totalErrPct = stats.total ? (100 * stats.errors) / stats.total : 0;

  // деградация: p95 первой половины vs второй (только при ПОСТОЯННОЙ нагрузке;
  // при stages рост латентности к концу — следствие роста нагрузки, а не деградации)
  let degradation = null;
  if (allLat.length > 100 && !scn.load?.stages) {
    // середину считаем от конца разогрева (measureStart), а не от старта: иначе при warmupSec>0
    // префикс окна пуст, h1 недобирает выборки и детектор деградации не срабатывает
    const mid = measureStart + (stats.endedAt - measureStart) / 2;
    const h1 = allLat.filter(([t]) => t < mid).map(([, ms]) => ms).sort((a, b) => a - b);
    const h2 = allLat.filter(([t]) => t >= mid).map(([, ms]) => ms).sort((a, b) => a - b);
    if (h1.length > 20 && h2.length > 20) {
      const p1 = pct(h1, 95), p2 = pct(h2, 95);
      if (p2 > p1 * 1.5 && p2 - p1 > 100) degradation = { firstHalfP95: p1, secondHalfP95: p2 };
    }
  }

  // сводка по проверкам ответов (checks)
  const checksSummary = [];
  for (const [name, s] of stats.per) {
    const req = (scn._steps || []).find((r) => r.name === name);
    if (!req || !req.checks) continue;
    const failed = [...s.statuses.entries()].filter(([k]) => String(k).endsWith('✗check')).reduce((a, [, v]) => a + v, 0);
    checksSummary.push({
      request: name,
      configured: Object.keys(req.checks).filter((k) => !k.startsWith('_')),
      failed,
      slowOverMaxMs: s.slow,
      maxMs: req.checks.maxMs ?? null,
      failSample: s.errSamples.get('check') || null,
    });
  }

  // влияние параметров-списков: статистика по каждому значению {{var}}
  const paramImpact = [];
  for (const [name, s] of stats.per) {
    for (const [vn, m] of s.perVar) {
      if (m.size < 2) continue;
      const values = [...m.entries()].map(([val, c]) => {
        const sl = c.lat.slice().sort((a, b) => a - b);
        return {
          value: val, count: c.count, errors: c.errors,
          errPct: c.count ? Number(((100 * c.errors) / c.count).toFixed(1)) : 0,
          p50: Math.round(pct(sl, 50)), p95: Math.round(pct(sl, 95)),
        };
      });
      // «(прочие)» — агрегат переполнения: в списке значений остаётся, но в медиану/выбросы не входит
      const named = values.filter((v) => v.value !== OTHERS_BUCKET);
      const withOk = named.filter((v) => v.count > v.errors); // есть хоть один успешный ответ
      const p95sAsc = withOk.filter((v) => v.count >= 3).map((v) => v.p95).sort((a, b) => a - b);
      const medianP95 = p95sAsc.length ? pct(p95sAsc, 50) : 0;
      const slowOutliers = withOk.filter((v) => v.count >= 5 && medianP95 > 0 && v.p95 > 2 * medianP95 && v.p95 - medianP95 > 50);
      const errOutliers = named.filter((v) => v.count >= 5 && v.errPct >= 10);
      // если падает большинство значений — это свойство запроса, а не конкретных значений
      const uniformErrors = errOutliers.length > Math.max(2, named.length / 2);
      const byP95 = withOk.slice().sort((a, b) => b.p95 - a.p95);
      paramImpact.push({
        request: name, variable: vn, distinctValues: m.size,
        p95Min: withOk.length ? Math.min(...withOk.map((v) => v.p95)) : 0,
        p95Median: Math.round(medianP95),
        p95Max: withOk.length ? Math.max(...withOk.map((v) => v.p95)) : 0,
        topSlowest: byP95.slice(0, 3),
        slowOutliers: slowOutliers.map((v) => ({ value: v.value, p95: v.p95, count: v.count })),
        errOutliers: errOutliers.map((v) => ({ value: v.value, errPct: v.errPct, count: v.count })),
        uniformErrors,
        values: values.slice().sort((a, b) => b.p95 - a.p95).slice(0, MAX_TRACKED_VALUES),
      });
    }
  }

  const th = scn.thresholds;
  const p95 = pct(latSorted, 95);
  const p99 = pct(latSorted, 99);
  const checks = opts.smoke ? [] : [
    { name: `p95 ${fmtMs(p95)}ms <= ${th.p95Ms}ms`, pass: p95 <= th.p95Ms },
    { name: `errors ${totalErrPct.toFixed(2)}% <= ${th.errorRatePct}%`, pass: totalErrPct <= th.errorRatePct },
  ];
  if (!opts.smoke && th.p99Ms !== undefined) checks.push({ name: `p99 ${fmtMs(p99)}ms <= ${th.p99Ms}ms`, pass: p99 <= th.p99Ms });
  if (!opts.smoke && th.rpsMin !== undefined) { const achievedRps = stats.total / durSec; checks.push({ name: `RPS ${achievedRps.toFixed(1)} >= ${th.rpsMin}`, pass: achievedRps >= th.rpsMin }); }
  // per-request пороги (SLO на конкретный эндпоинт) — тоже в вердикт
  if (!opts.smoke && th.perRequest) {
    for (const r of rows) {
      const t = th.perRequest[r.name];
      if (!t) continue;
      // запрос ни разу не выполнился (например, шаг цепочки после обрыва) — SLO нельзя считать пройденным
      if (r.count === 0) { checks.push({ name: `[${r.name}] не выполнялся ни разу (0 запросов) — SLO не подтверждён`, pass: false }); continue; }
      // если у запроса НЕТ успешных выборок (100% ошибок), p95/p99=0 — не даём порогу ложно пройти
      if (t.p95Ms !== undefined) checks.push({ name: `[${r.name}] p95 ${fmtMs(r.p95)}ms <= ${t.p95Ms}ms`, pass: r.errPct < 100 && r.p95 <= t.p95Ms });
      if (t.p99Ms !== undefined) checks.push({ name: `[${r.name}] p99 ${fmtMs(r.p99)}ms <= ${t.p99Ms}ms`, pass: r.errPct < 100 && r.p99 <= t.p99Ms });
      if (t.errorRatePct !== undefined) checks.push({ name: `[${r.name}] errors ${r.errPct.toFixed(2)}% <= ${t.errorRatePct}%`, pass: r.errPct <= t.errorRatePct });
    }
  }
  // пороги по метрикам ЦЕЛИ (monitor.thresholds) — тоже в вердикт
  const targetMetrics = opts.targetMetrics || null;
  if (!opts.smoke && targetMetrics && scn.monitor?.thresholds) {
    for (const [name, t] of Object.entries(scn.monitor.thresholds)) {
      const mtr = targetMetrics.metrics.find((x) => x.name === name);
      if (!mtr) { checks.push({ name: `[цель] метрика "${name}" не собрана — порог не подтверждён`, pass: false }); continue; }
      checks.push({ name: `[цель] ${name} макс ${mtr.max} <= ${t.max}`, pass: mtr.max <= t.max });
    }
  }
  const incompleteWorkers = opts.incompleteWorkers || 0;
  const pass = checks.every((c) => c.pass) && (!opts.smoke || stats.errors === 0) && incompleteWorkers === 0;
  if (incompleteWorkers > 0) checks.push({ name: `все потоки-генераторы вернули данные (не вернули: ${incompleteWorkers})`, pass: false });

  // сводка по цепочкам (flows): доля завершённых сессий, длительность journey, точка обрыва
  const flowReport = [];
  for (const [name, f] of stats.flowStats) {
    const durs = f.durations.slice().sort((a, b) => a - b);
    const breaks = [...f.breaks.entries()].sort((a, b) => b[1] - a[1]);
    flowReport.push({
      name, sessions: f.started, completed: f.completed,
      completionPct: f.started ? Number(((100 * f.completed) / f.started).toFixed(1)) : 0,
      p50Ms: Math.round(pct(durs, 50)), p95Ms: Math.round(pct(durs, 95)),
      topBreak: breaks.length ? { step: breaks[0][0], count: breaks[0][1] } : null,
    });
  }

  // подсказки
  const hints = [];
  const statusCount = new Map();
  for (const [, s] of stats.per) for (const [k, v] of s.statuses) statusCount.set(String(k), (statusCount.get(String(k)) || 0) + v);
  const cnt = (k) => statusCount.get(k) || 0;
  if (cnt('conn') > stats.total * 0.5) hints.push('Почти все запросы не смогли подключиться (conn) — цель недоступна: проверьте URL/порт и что сервис запущен (probe).');
  if (cnt('timeout') > stats.total * 0.05) hints.push(`Много таймаутов (>${DEFAULT_TIMEOUT_MS / 1000}с по умолчанию) — сервер не справляется с нагрузкой или timeoutMs слишком мал.`);
  if (cnt('401') > 0) hints.push('Есть ответы 401 — авторизация не настроена или токен истёк: проверьте блок auth в сценарии.');
  if (cnt('403') > 0) hints.push('Есть ответы 403 — токен валиден, но не хватает прав (роль пользователя).');
  if (cnt('404') > 0) {
    const bad = rows.filter((r) => r.statuses.includes('404:')).map((r) => `"${r.name}"`).join(', ');
    hints.push(`Есть ответы 404 — проверьте пути запросов${bad ? `: ${bad}` : ''}.`);
  }
  if (cnt('429') > 0) hints.push('Есть ответы 429 — на сервере включён rate limit; либо снижайте нагрузку (load.maxRps), либо это и есть найденный предел.');
  const http5xx = [...statusCount.entries()].filter(([k]) => /^5\d\d$/.test(k)).reduce((a, [, v]) => a + v, 0);
  if (http5xx > 0) hints.push(`Есть ${http5xx} ответов 5xx — серверные ошибки под нагрузкой; смотрите логи сервиса (примеры ответов в секции ОШИБКИ).`);
  if (degradation) hints.push(`Латентность растёт со временем: p95 первой половины ${fmtMs(degradation.firstHalfP95)}ms → второй ${fmtMs(degradation.secondHalfP95)}ms. Похоже на деградацию под длительной нагрузкой (пул соединений, GC, утечка).`);
  // тяжёлое тело: если скачивание занимает заметную долю p95 — узкое место в размере ответа, не в обработке
  for (const r of rows) {
    if (r.count >= 10 && r.downloadP95 >= 15 && r.downloadP95 >= 0.35 * r.p95) {
      hints.push(`"${r.name}": из p95 ${Math.round(r.p95)}ms скачивание тела ~${Math.round(r.downloadP95)}ms (TTFB ~${Math.round(r.ttfbP95)}ms) — узкое место в РАЗМЕРЕ ответа, не в обработке; рассмотрите пагинацию/выборку полей/сжатие.`);
    }
  }
  for (const cs of checksSummary) {
    if (cs.failed > 0) hints.push(`Проверки ответов у "${cs.request}" провалены ${cs.failed} раз: ${cs.failSample || ''} — сервер отвечает 2xx, но содержимое неверное (см. ПРОВЕРКИ ОТВЕТОВ).`);
    if (cs.slowOverMaxMs > 0) hints.push(`"${cs.request}": ${cs.slowOverMaxMs} ответов медленнее бюджета checks.maxMs=${cs.maxMs}ms (в латентность включены, вердикт не ломают).`);
  }
  for (const pi of paramImpact) {
    for (const o of pi.slowOutliers.slice(0, 3)) hints.push(`Параметр {{${pi.variable}}} в "${pi.request}": значение ${o.value} аномально медленное — p95 ${o.p95}ms при медиане ${pi.p95Median}ms по остальным значениям (n=${o.count}).`);
    if (pi.uniformErrors) {
      hints.push(`"${pi.request}": ошибки НЕ зависят от значения {{${pi.variable}}} — падает большинство значений (${pi.errOutliers.length} из ${pi.distinctValues}); причина в самом запросе/сервисе, а не в данных.`);
    } else {
      for (const o of pi.errOutliers.slice(0, 3)) hints.push(`Параметр {{${pi.variable}}} в "${pi.request}": значение ${o.value} даёт ${o.errPct}% ошибок (n=${o.count}) — проверьте данные этой сущности.`);
    }
  }
  if (!opts.smoke && pass && totalErrPct === 0 && p95 < th.p95Ms * 0.3 && stats.total > 50) {
    hints.push(`Система легко держит эту нагрузку (p95 ${fmtMs(p95)}ms при пороге ${th.p95Ms}ms). Чтобы найти предел — повышайте нагрузку: --vus ${Math.min(MAX_VUS, scn.load.vus * 2)}.`);
  }
  if (latDroppedTotal > 0) hints.push(`Выборок латентности больше лимита ${MAX_LAT_SAMPLES} на запрос — перцентили посчитаны по первым ${MAX_LAT_SAMPLES} (отброшено ${latDroppedTotal}).`);
  if ((stats.warmupSkipped || 0) > 0) hints.push(`Разогрев ${scn.load.warmupSec}с: ${stats.warmupSkipped} запросов на прогреве НЕ учтены в метриках (честные перцентили без JIT/прогрева пула соединений).`);
  if (opts.lifecycle && opts.lifecycle.teardown && opts.lifecycle.teardown.some((x) => !x.ok)) {
    const failed = opts.lifecycle.teardown.filter((x) => !x.ok).map((x) => `"${x.name}"`).join(', ');
    hints.push(`⚠ TEARDOWN НЕ ПОЛНОСТЬЮ УДАЛСЯ (${failed}) — созданные при setup сущности могли остаться в системе, проверьте вручную.`);
  }

  if (incompleteWorkers > 0) hints.push(`⚠ ${incompleteWorkers} поток(ов)-генератор(ов) упали и не вернули данные — их доля нагрузки НЕ выполнена. Отчёт неполный, вердикт принудительно FAIL. Проверьте память/стабильность и повторите.`);
  for (const fr of flowReport) {
    if (fr.completionPct < 95 && fr.sessions >= 5) {
      hints.push(`Цепочка "${fr.name}": доходит до конца только ${fr.completionPct}% сессий (${fr.completed}/${fr.sessions})${fr.topBreak ? `, чаще всего обрывается на шаге "${fr.topBreak.step}" (${fr.topBreak.count} раз)` : ''} — это узкое место пользовательского сценария.`);
    }
  }

  // насыщение генератора: не упёрлись ли МЫ, а не цель
  const res = opts.resource || null;
  const workers = scn.load?.workers || 1;
  if (res) {
    // event-loop lag — главный сигнал: он меряется на КАЖДОМ потоке-генераторе (в воркерах — max),
    // и напрямую показывает, что поток нагрузки не успевает. CPU% процесса как таковой ненадёжен
    // (аггрегирует все потоки + GC/DNS), поэтому используем «занято ядер на поток».
    const elLagBad = res.elLagMaxMs != null && res.elLagMaxMs > 100;
    const cpuPerThread = res.cpuBusyCores / Math.max(1, workers); // доля ядра на один поток-генератор
    // на CPU упёрлись, только если потоки-генераторы реально пекут свои ядра (>=0.85) И это видно по lag
    const cpuBad = cpuPerThread >= 0.85 && elLagBad;
    const sat = [];
    if (elLagBad) sat.push('event-loop');
    if (cpuBad && !sat.includes('CPU')) sat.push('CPU');
    if (res.sysFreeMB < 256) sat.push('память');
    res.saturated = sat;
    res.cpuBusyCores = res.cpuBusyCores;
    if (sat.length) {
      hints.push(`⚠ ГЕНЕРАТОР УПЁРСЯ В РЕСУРСЫ (${sat.join(', ')}): измеренная латентность и достигнутый RPS ограничены самой машиной-генератором, а НЕ целью — числам ниже доверять нельзя как оценке сервиса.`);
      if (elLagBad) {
        if (workers < res.cores) {
          hints.push(`Потоков-генераторов ${workers}, а ядер ${res.cores} — распределите нагрузку: load.workers ${Math.min(res.cores, Math.max(2, workers * 2))} (или флаг --workers) и повторите прогон.`);
        } else {
          hints.push(`Генератор уже занял все ${res.cores} ядра (${res.cpuBusyCores} в среднем) — для более высокой нагрузки НУЖНО БОЛЕЕ МОЩНОЕ ЖЕЛЕЗО или запуск loadgen с нескольких машин параллельно.`);
        }
      }
      if (sat.includes('память')) {
        hints.push(`Свободной ОЗУ в системе падало до ${res.sysFreeMB}МБ, процесс занял ${res.rssMaxMB}МБ — уменьшите vus/длительность или возьмите машину с большим объёмом памяти.`);
      }
      const target = scn.load?.maxRps;
      if (target && rows.length && (stats.total / durSec) < target * 0.8) {
        hints.push(`Целевой RPS (${target}) не достигнут (факт ${Math.round(stats.total / durSec)}) при упёртом генераторе — узкое место в генераторе, не в цели.`);
      }
    } else if (res.cpuBusyCores >= workers * 0.75 && workers < res.cores && res.elLagMaxMs != null && res.elLagMaxMs > 40) {
      // не упёрлись, но потоки заметно грузят свои ядра и lag подрастает — подсказать про запас по ядрам
      hints.push(`Потоки-генераторы заметно грузят CPU (занято ~${res.cpuBusyCores} ядер из ${res.cores}); при повышении нагрузки поднимите load.workers до ${Math.min(res.cores, Math.max(2, workers * 2))}.`);
    }
  }

  // корреляция: метрики цели vs генератор — кто узкое место
  if (targetMetrics) {
    for (const e of targetMetrics.errors) hints.push(`Монитор цели: ${e}`);
    const genSaturated = res && res.saturated && res.saturated.length;
    const targetCpuBound = targetMetrics.metrics.filter((mt) => mt.saturatedCpu);
    for (const mt of targetCpuBound) {
      hints.push(`⚠ ЦЕЛЬ упёрлась: ${mt.name} достигал ${mt.max}% (пик t=${mt.peakAtSec}с)${genSaturated ? '' : ' — при этом генератор НЕ был узким местом, значит предел упирается в САМ СЕРВИС (нужно оптимизировать/масштабировать цель)'}.`);
    }
    for (const mt of targetMetrics.metrics.filter((mt) => mt.risingLag)) {
      hints.push(`⚠ BACKPRESSURE: ${mt.name} рос по ходу прогона (${mt.min}→${mt.last}, макс ${mt.max}) — консьюмер не успевает за продьюсером. Узкое место — обработка на стороне консьюмера/витрины, а не Kafka.`);
    }
    if (!genSaturated && !targetCpuBound.length && p95 > th.p95Ms && targetMetrics.metrics.length) {
      hints.push(`Латентность выше порога, но ни генератор, ни CPU цели не насыщены — узкое место, вероятно, вне CPU (БД, блокировки, сеть, GC, внешний сервис). Смотрите метрики цели и её зависимостей.`);
    }
  }

  return {
    tool: 'loadgen', version: VERSION, kind: scn.kind || 'http',
    scenario: scn.name || '(без имени)',
    baseUrl: scn.baseUrl || (scn.kind === 'sql' ? `SQL[${scn.sql?.driver || 'custom'}]` : scn.kind === 'pipeline' ? 'PIPELINE (Kafka→verify)' : ''),
    mode: opts.smoke ? 'smoke' : 'load',
    startedAt: new Date(stats.startedAt).toISOString(),
    durationSec: Number(durSec.toFixed(1)),
    warmupSec: (!opts.smoke && scn.load?.warmupSec) || 0,
    warmupSkipped: stats.warmupSkipped || 0,
    vus: opts.smoke ? 1 : scn.load.vus,
    total: stats.total, rps: Number((stats.total / durSec).toFixed(1)),
    errors: stats.errors, errorRatePct: Number(totalErrPct.toFixed(2)),
    latencyMs: { p50: Math.round(pct(latSorted, 50)), p90: Math.round(pct(latSorted, 90)), p95: Math.round(p95), p99: Math.round(pct(latSorted, 99)), max: Math.round(latSorted.length ? latSorted[latSorted.length - 1] : 0), ttfbP95: Math.round(pct(ttfbSorted, 95)) },
    hist: histogram(latSorted), histBoundsV: HIST_BOUNDS_V, // гистограмма латентности + версия сетки (для merge)
    perRequest: rows.map((r) => ({ ...r, rps: Number(r.rps.toFixed(1)), errPct: Number(r.errPct.toFixed(2)), p50: Math.round(r.p50), p90: Math.round(r.p90), p95: Math.round(r.p95), p99: Math.round(r.p99), max: Math.round(r.max), ttfbP95: Math.round(r.ttfbP95), downloadP95: Math.round(r.downloadP95) })),
    thresholds: { p95Ms: th.p95Ms, errorRatePct: th.errorRatePct, ...(th.p99Ms !== undefined ? { p99Ms: th.p99Ms } : {}), ...(th.rpsMin !== undefined ? { rpsMin: th.rpsMin } : {}), ...(th.perRequest ? { perRequest: th.perRequest } : {}) }, // значения порогов — чтобы merge пересчитал вердикт
    workers, resource: res, incompleteWorkers, flows: flowReport, targetMetrics, lifecycle: opts.lifecycle || null,
    errorsDetail, degradation, checksSummary, paramImpact, checks, verdict: pass ? 'PASS' : 'FAIL', hints,
    loginFailures: opts.loginFailures || [],
  };
}

// ─── отчёты для CI: JUnit XML и Markdown ──

function xmlEsc(s) {
  // сначала выкидываем C0-управляющие символы (кроме табуляции/переводов строк) — недопустимы в XML 1.0
  return String(s)
    .replace(/[\u0000-\u0008\u000B\u000C\u000E-\u001F]/g, '')
    .replace(/[<>&"']/g, (c) => ({ '<': '&lt;', '>': '&gt;', '&': '&amp;', '"': '&quot;', "'": '&apos;' }[c]));
}

/** JUnit XML: каждый порог/запрос/цепочка = testcase (Jenkins/GitLab/GitHub рендерят нативно). */
function toJUnitXml(rep) {
  const cases = [];
  // пороги (это и есть критерии PASS/FAIL)
  for (const c of rep.checks || []) {
    cases.push({ cls: 'thresholds', name: c.name, fail: c.pass ? null : 'порог нарушен' });
  }
  // запросы: время = p95, провал если есть ошибки
  for (const r of rep.perRequest || []) {
    cases.push({ cls: 'requests', name: r.name, timeSec: (r.p95 || 0) / 1000, fail: r.errPct > 0 ? `ошибки ${r.errPct}% (${r.statuses})` : null });
  }
  // цепочки: провал если не 100% завершения
  for (const f of rep.flows || []) {
    cases.push({ cls: 'flows', name: f.name, timeSec: (f.p95Ms || 0) / 1000, fail: f.completionPct < 100 ? `завершено ${f.completionPct}%${f.topBreak ? `, рвётся на "${f.topBreak.step}"` : ''}` : null });
  }
  const failures = cases.filter((c) => c.fail).length;
  const suiteName = rep.scenario || 'loadgen';
  const lines = [];
  lines.push('<?xml version="1.0" encoding="UTF-8"?>');
  lines.push(`<testsuites name="loadgen" tests="${cases.length}" failures="${failures}">`);
  lines.push(`  <testsuite name="${xmlEsc(suiteName)}" tests="${cases.length}" failures="${failures}" time="${rep.durationSec || 0}" timestamp="${rep.startedAt || ''}">`);
  lines.push(`    <properties><property name="verdict" value="${rep.verdict}"/><property name="target" value="${xmlEsc(rep.baseUrl || '')}"/><property name="rps" value="${rep.rps}"/><property name="errorRatePct" value="${rep.errorRatePct}"/></properties>`);
  for (const c of cases) {
    const attrs = `classname="${xmlEsc(c.cls)}" name="${xmlEsc(c.name)}"${c.timeSec !== undefined ? ` time="${c.timeSec}"` : ''}`;
    if (c.fail) lines.push(`    <testcase ${attrs}><failure message="${xmlEsc(c.fail)}">${xmlEsc(c.fail)}</failure></testcase>`);
    else lines.push(`    <testcase ${attrs}/>`);
  }
  lines.push('  </testsuite>');
  lines.push('</testsuites>');
  return lines.join('\n') + '\n';
}

/** Markdown-сводка для комментария в PR. */
function toMarkdown(rep) {
  const L = [];
  const badge = rep.verdict === 'PASS' ? '✅ PASS' : '❌ FAIL';
  L.push(`## Нагрузочный тест: ${badge}`);
  L.push('');
  L.push(`**Сценарий:** ${rep.scenario} · **Цель:** ${rep.baseUrl} · **Режим:** ${rep.mode}${rep.workers > 1 ? ` · потоков: ${rep.workers}` : ''}`);
  L.push('');
  L.push(`Запросов **${rep.total}** за ${rep.durationSec}с · RPS **${rep.rps}** · ошибок **${rep.errorRatePct}%** · p95 **${rep.latencyMs.p95}ms** (p99 ${rep.latencyMs.p99}ms)`);
  L.push('');
  const mdCell = (s) => String(s).replace(/\|/g, '\\|').replace(/\n/g, ' '); // экранируем | чтобы не ломать таблицу
  if (rep.perRequest && rep.perRequest.length) {
    L.push('| запрос | кол-во | rps | err% | p95 | p99 |');
    L.push('|---|--:|--:|--:|--:|--:|');
    for (const r of rep.perRequest) L.push(`| ${mdCell(r.name)} | ${r.count} | ${r.rps} | ${r.errPct} | ${r.p95}ms | ${r.p99}ms |`);
    L.push('');
  }
  if (rep.flows && rep.flows.length) {
    L.push('**Цепочки:** ' + rep.flows.map((f) => `${f.name} — ${f.completionPct}% завершено${f.topBreak ? ` (рвётся на "${f.topBreak.step}")` : ''}`).join('; '));
    L.push('');
  }
  if (rep.checks && rep.checks.length) {
    L.push('**Пороги:**');
    for (const c of rep.checks) L.push(`- ${c.pass ? '✅' : '❌'} ${c.name}`);
    L.push('');
  }
  if (rep.resource) {
    const r = rep.resource;
    const sat = r.saturated && r.saturated.length ? `⚠ УПЁРЛИСЬ в ${r.saturated.join(', ')}` : 'OK';
    L.push(`**Ресурсы генератора:** ${sat} (CPU ~${r.cpuBusyCores} ядер/${r.cores}, lag ${r.elLagMaxMs ?? '—'}ms)`);
    L.push('');
  }
  if (rep.hints && rep.hints.length) {
    L.push('**Подсказки:**');
    for (const h of rep.hints) L.push(`- ${h}`);
  }
  return L.join('\n') + '\n';
}

// ─── консольные цвета (ANSI), уважают NO_COLOR и не-TTY (в файл/пайп красят без ESC) ──
const COLOR_ON = process.stdout && process.stdout.isTTY && !process.env.NO_COLOR;
function clr(s, code) { const E = String.fromCharCode(27); return COLOR_ON ? `${E}[${code}m${s}${E}[0m` : s; }
const green = (s) => clr(s, 32), red = (s) => clr(s, '31;1'), yellow = (s) => clr(s, 33), dim = (s) => clr(s, 90), bold = (s) => clr(s, 1);

/** Компактная ASCII-гистограмма распределения латентности (только непустые бакеты). */
function asciiHistogram(hist, maxRows = 12) {
  if (!Array.isArray(hist) || !hist.length) return [];
  const rows = [];
  for (let i = 0; i < hist.length; i++) {
    if (!hist[i]) continue;
    const lo = i === 0 ? 0 : HIST_BOUNDS[i - 1];
    const hi = i < HIST_BOUNDS.length ? HIST_BOUNDS[i] : Infinity;
    rows.push({ label: hi === Infinity ? `>${lo}ms` : `${lo}-${hi}ms`, count: hist[i] });
  }
  if (!rows.length) return [];
  // если бакетов больше maxRows — оставляем самые населённые
  const shown = rows.length > maxRows ? [...rows].sort((a, b) => b.count - a.count).slice(0, maxRows).sort((a, b) => rows.indexOf(a) - rows.indexOf(b)) : rows;
  const max = Math.max(...shown.map((r) => r.count));
  const lblW = Math.max(...shown.map((r) => r.label.length));
  const out = [];
  for (const r of shown) {
    const bars = max ? Math.round((r.count / max) * 32) : 0;
    out.push(`  ${r.label.padStart(lblW)} │${'█'.repeat(bars)}${'·'.repeat(32 - bars)} ${r.count}`);
  }
  return out;
}

/** Самодостаточный HTML-отчёт (инлайн CSS + SVG бар-чарт гистограммы). */
function toHtml(rep) {
  const esc = (s) => String(s).replace(/[<>&]/g, (c) => ({ '<': '&lt;', '>': '&gt;', '&': '&amp;' }[c]));
  const ok = rep.verdict === 'PASS';
  const lm = rep.latencyMs || {};
  // SVG-гистограмма из rep.hist
  const hist = Array.isArray(rep.hist) ? rep.hist : [];
  const bars = [];
  for (let i = 0; i < hist.length; i++) {
    if (!hist[i]) continue;
    const lo = i === 0 ? 0 : HIST_BOUNDS[i - 1];
    const hi = i < HIST_BOUNDS.length ? HIST_BOUNDS[i] : Infinity;
    bars.push({ label: hi === Infinity ? `>${lo}` : `${hi}`, count: hist[i] });
  }
  const maxC = Math.max(1, ...bars.map((b) => b.count));
  const bw = bars.length ? Math.max(6, Math.floor(760 / bars.length)) : 6;
  const svg = bars.map((b, i) => {
    const h = Math.round((b.count / maxC) * 160);
    return `<rect x="${i * bw}" y="${180 - h}" width="${bw - 1}" height="${h}" fill="${ok ? '#2ea043' : '#d1242f'}"><title>&lt;=${b.label}ms: ${b.count}</title></rect>`;
  }).join('');
  const rows = (rep.perRequest || []).map((r) => `<tr><td>${esc(r.name)}</td><td>${r.count}</td><td>${r.rps}</td><td>${r.errPct}%</td><td>${r.p95}ms</td><td>${r.p99}ms</td></tr>`).join('');
  const checks = (rep.checks || []).map((c) => `<li class="${c.pass ? 'ok' : 'bad'}">${c.pass ? '✅' : '❌'} ${esc(c.name)}</li>`).join('');
  const hints = (rep.hints || []).map((h) => `<li>${esc(h)}</li>`).join('');
  return `<!doctype html><html lang="ru"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>loadgen: ${esc(rep.scenario)} — ${rep.verdict}</title><style>
body{font:14px/1.5 -apple-system,Segoe UI,Roboto,sans-serif;margin:0;background:#f6f8fa;color:#1f2328}
.wrap{max-width:900px;margin:0 auto;padding:24px}
.badge{display:inline-block;padding:4px 14px;border-radius:16px;font-weight:700;color:#fff;background:${ok ? '#2ea043' : '#d1242f'}}
h1{font-size:20px;margin:8px 0}.sub{color:#656d76}
.cards{display:flex;flex-wrap:wrap;gap:12px;margin:16px 0}
.card{background:#fff;border:1px solid #d0d7de;border-radius:8px;padding:12px 16px;min-width:120px}
.card b{display:block;font-size:22px}.card span{color:#656d76;font-size:12px}
table{width:100%;border-collapse:collapse;background:#fff;border:1px solid #d0d7de;border-radius:8px;overflow:hidden;margin:12px 0}
th,td{text-align:left;padding:8px 12px;border-bottom:1px solid #eaeef2}th{background:#f6f8fa}
td:not(:first-child),th:not(:first-child){text-align:right}
svg{background:#fff;border:1px solid #d0d7de;border-radius:8px;max-width:100%}
ul{list-style:none;padding:0}li{padding:3px 0}.ok{color:#1a7f37}.bad{color:#cf222e;font-weight:600}
h2{font-size:15px;margin:20px 0 6px}.mono{font-family:ui-monospace,monospace}
</style></head><body><div class="wrap">
<div><span class="badge">${rep.verdict}</span></div>
<h1>${esc(rep.scenario)}</h1>
<div class="sub">${esc(rep.baseUrl)} · ${rep.mode} · ${rep.durationSec}с${rep.workers > 1 ? ` · ${rep.workers} потоков` : ''} · ${esc(rep.startedAt || '')}</div>
<div class="cards">
<div class="card"><b>${rep.total}</b><span>запросов</span></div>
<div class="card"><b>${rep.rps}</b><span>RPS</span></div>
<div class="card"><b>${rep.errorRatePct}%</b><span>ошибок</span></div>
<div class="card"><b>${lm.p95}ms</b><span>p95</span></div>
<div class="card"><b>${lm.p99}ms</b><span>p99</span></div>
</div>
<h2>Распределение латентности</h2>
<svg viewBox="0 0 ${Math.max(1, bars.length) * bw} 200" width="100%" height="200">${svg}</svg>
<h2>По запросам</h2>
<table><tr><th>запрос</th><th>кол-во</th><th>rps</th><th>err%</th><th>p95</th><th>p99</th></tr>${rows}</table>
<h2>Пороги</h2><ul>${checks || '<li>—</li>'}</ul>
${hints ? `<h2>Подсказки</h2><ul>${hints}</ul>` : ''}
<p class="sub">loadgen v${esc(rep.version || '')}</p>
</div></body></html>
`;
}

function printReport(rep) {
  const L = console.log;
  L('');
  L('──────────────── РЕЗУЛЬТАТЫ ПО ЗАПРОСАМ ────────────────');
  const head = ['запрос', 'кол-во', 'rps', 'err%', 'p50', 'p90', 'p95', 'p99', 'max', 'статусы'];
  const table = [head, ...rep.perRequest.map((r) => [r.name.slice(0, 32), r.count, r.rps, r.errPct, r.p50, r.p90, r.p95, r.p99, r.max, r.statuses])];
  const widths = head.map((_, c) => Math.max(...table.map((row) => String(row[c]).length)));
  for (const row of table) L(row.map((v, c) => String(v).padEnd(widths[c])).join('  '));
  if (rep.errorsDetail.length) {
    L('');
    L('──────────────── ОШИБКИ (по одному примеру на вид) ────');
    for (const e of rep.errorsDetail) L(`  [${e.error}] ${e.request}: ${e.sample || '(пустое тело ответа)'}`);
  }
  if (rep.flows && rep.flows.length) {
    L('');
    L('──────────────── СЦЕНАРИИ-ЦЕПОЧКИ (FLOWS) ──────────────');
    for (const f of rep.flows) {
      const dur = f.completed > 0 ? `сумма ответов journey p50 ${f.p50Ms}ms, p95 ${f.p95Ms}ms` : 'ни одна сессия не завершена';
      L(`  ${f.name}: сессий ${f.sessions}, завершено ${f.completed} (${f.completionPct}%) | ${dur}`);
      if (f.topBreak) L(`    ⚠ чаще всего обрывается на шаге "${f.topBreak.step}" (${f.topBreak.count} раз)`);
    }
  }
  if (rep.checksSummary && rep.checksSummary.length) {
    L('');
    L('──────────────── ПРОВЕРКИ ОТВЕТОВ ──────────────────────');
    for (const c of rep.checksSummary) {
      const parts = [`настроено: ${c.configured.join(', ')}`, `провалено: ${c.failed}`];
      if (c.maxMs != null) parts.push(`медленнее ${c.maxMs}ms: ${c.slowOverMaxMs}`);
      L(`  ${c.request}: ${parts.join(' | ')}`);
      if (c.failed > 0 && c.failSample) L(`    пример провала: ${c.failSample}`);
    }
  }
  if (rep.paramImpact && rep.paramImpact.length) {
    L('');
    L('──────────────── ВЛИЯНИЕ ПАРАМЕТРОВ (списки) ───────────');
    for (const p of rep.paramImpact) {
      L(`  ${p.request} / {{${p.variable}}}: значений ${p.distinctValues}, p95 по значениям ${p.p95Min}..${p.p95Max}ms (медиана ${p.p95Median}ms)`);
      const top = p.topSlowest.map((v) => `${v.value} (p95 ${v.p95}ms, n=${v.count}${v.errPct ? `, err ${v.errPct}%` : ''})`);
      if (top.length) L(`    самые медленные: ${top.join('; ')}`);
      for (const o of p.slowOutliers) L(`    ⚠ выброс по скорости: ${o.value} — p95 ${o.p95}ms (медиана ${p.p95Median}ms)`);
      if (p.uniformErrors) L(`    ⚠ ошибки на большинстве значений (${p.errOutliers.length}/${p.distinctValues}) — причина не в данных, а в запросе/сервисе`);
      else for (const o of p.errOutliers) L(`    ⚠ выброс по ошибкам: ${o.value} — ${o.errPct}% err (n=${o.count})`);
    }
  }
  if (rep.loginFailures.length) {
    L('');
    L(`  ЛОГИН НЕ УДАЛСЯ у ${rep.loginFailures.length} VU: ${rep.loginFailures[0]}`);
  }
  if (rep.lifecycle && (rep.lifecycle.setup.length || rep.lifecycle.teardown.length)) {
    L('');
    L('──────────────── ЖИЗНЕННЫЙ ЦИКЛ (setup/teardown) ───────');
    for (const x of rep.lifecycle.setup) L(`  setup ${x.ok ? 'OK  ' : 'FAIL'} ${x.name}${x.captured && x.captured.length ? ` [${x.captured.join(', ')}]` : ''}${x.ok ? '' : ` — ${x.error}`}`);
    for (const x of rep.lifecycle.teardown) L(`  teardown ${x.ok ? 'OK  ' : 'FAIL'} ${x.name}${x.ok ? '' : ` — ${x.error}`}`);
  }
  if (rep.targetMetrics && (rep.targetMetrics.metrics.length || rep.targetMetrics.errors.length)) {
    L('');
    L('──────────────── МЕТРИКИ ЦЕЛИ (во время прогона) ───────');
    for (const mt of rep.targetMetrics.metrics) {
      L(`  ${mt.name}: сред. ${mt.avg}, макс. ${mt.max} (t=${mt.peakAtSec}с), последн. ${mt.last}${mt.saturatedCpu ? '  ⚠ насыщение' : ''}${mt.risingLag ? '  ⚠ РАСТЁТ (backpressure — консьюмер не успевает)' : ''}`);
    }
    if (!rep.targetMetrics.metrics.length) L('  (не собрано ни одной метрики — см. ошибки ниже)');
    for (const e of rep.targetMetrics.errors) L(`  (!) ${e}`);
  }
  if (rep.resource) {
    const r = rep.resource;
    L('');
    L('──────────────── РЕСУРСЫ ГЕНЕРАТОРА ────────────────────');
    L(`  CPU: занято ~${r.cpuBusyCores} ядер из ${r.cores} (${r.cpuPctAllCores}% машины) | потоков-генераторов: ${rep.workers}`);
    if (r.elLagMaxMs != null) L(`  Event-loop lag: сред. ${r.elLagMeanMs ?? '—'}ms, макс. ${r.elLagMaxMs}ms (>100ms = поток нагрузки не успевает)`);
    L(`  Память: процесс ${r.rssMaxMB}МБ (пик) | свободно в системе ${r.sysFreeMB}МБ из ${r.sysTotalMB}МБ`);
    if (r.saturated && r.saturated.length) L(`  СТАТУС: ⚠ УПЁРЛИСЬ В (${r.saturated.join(', ')}) — цифрам латентности доверять нельзя`);
    else L(`  СТАТУС: OK — генератор не был узким местом`);
  }
  if (!rep.mode || rep.mode !== 'smoke') {
    const distro = asciiHistogram(rep.hist);
    if (distro.length) { L(''); L('──────────────── РАСПРЕДЕЛЕНИЕ ЛАТЕНТНОСТИ ─────────────'); for (const d of distro) L(d); }
  }
  L('');
  L('==================== ИТОГ ====================');
  L(`VERDICT: ${rep.verdict === 'PASS' ? green(bold('PASS')) : red(bold('FAIL'))}`);
  L(`Сценарий: ${rep.scenario} | Режим: ${rep.mode} | Цель: ${rep.baseUrl}`);
  const isPipe = rep.kind === 'pipeline';
  // throughput конвейера = ДОСТАВЛЕННЫЕ события / окно (не всего попыток: застрявшие не доставлены). Лейбл ошибок нейтральный — не все они «застряло» (бывают produce/verify/config).
  const delivered = isPipe ? ((rep.total - rep.errors) / Math.max(0.001, rep.durationSec)).toFixed(1) : rep.rps;
  L(`${isPipe ? 'Событий' : 'Запросов'}: ${rep.total} за ${rep.durationSec}с (${isPipe ? `доставлено ${bold(delivered)}/с` : `RPS ${bold(rep.rps)}`}, VUs ${rep.vus}) | ${isPipe ? 'Не долетело/ошибок' : 'Ошибок'}: ${rep.errors} (${rep.errorRatePct}%)`);
  const lm = rep.latencyMs;
  L(`${isPipe ? 'Лаг распространения (produce→видно) мс' : 'Латентность мс'}: p50 ${fmtMs(lm.p50)} | p90 ${fmtMs(lm.p90)} | p95 ${bold(fmtMs(lm.p95))} | p99 ${fmtMs(lm.p99)} | max ${fmtMs(lm.max)}`);
  if (rep.kind === 'http' && lm.ttfbP95 != null && lm.p95 > 0) L(dim(`  из p95: TTFB (сервер+сеть до 1-го байта) ~${fmtMs(lm.ttfbP95)}ms, скачивание тела ~${fmtMs(Math.max(0, lm.p95 - lm.ttfbP95))}ms`));
  for (const c of rep.checks) L(`Порог: ${c.name} ${c.pass ? green('OK') : red('НАРУШЕН')}`);
  L('==============================================');
  if (rep.hints.length) {
    L('ПОДСКАЗКИ:');
    for (const h of rep.hints) L(`  ${yellow('•')} ${h}`);
  }
}

// ─────────────────────────────────────────────── команды ──

/** Git Bash (MSYS) на Windows превращает аргумент "/api/x" в "C:/Program Files/Git/api/x" — снимаем это. */
function unmangleMsysPath(p) {
  let s = String(p).replace(/\\/g, '/');
  const exe = (process.env.EXEPATH || '').replace(/\\/g, '/').replace(/\/$/, '');
  if (exe && s.toLowerCase().startsWith(exe.toLowerCase())) {
    s = s.slice(exe.length);
  } else {
    const m = s.match(/^[A-Za-z]:\/(?:[^/]+\/)*?git\/(.*)$/i);
    if (m) s = '/' + m[1];
  }
  if (!s.startsWith('/')) s = '/' + s;
  return s;
}

async function cmdProbe(positional) {
  const base = positional[0];
  if (!base) die(1, 'Использование: node loadgen.mjs probe <baseUrl> [path ...]\nПример: node loadgen.mjs probe http://localhost:8080 /actuator/health /api/v1/pools');
  let u;
  try { u = new URL(base); } catch { die(1, `"${base}" — не валидный URL. Нужен вид http://host:port`); }
  const paths = (positional.length > 1 ? positional.slice(1) : ['/']).map(unmangleMsysPath);
  console.log(`PROBE ${u.origin} (${isPrivateHost(u.hostname) ? 'локальный/приватный хост' : 'ВНЕШНИЙ хост — для run понадобится --confirm-external'})`);
  let reachable = false;
  let authNeeded = false;
  for (const p of paths) {
    const url = new URL(p, u.origin).toString();
    const times = [];
    let status = null, kind = null, msg = null, server = null, size = 0;
    for (let i = 0; i < 3; i++) {
      const t0 = performance.now();
      try {
        const res = await fetch(url, { signal: AbortSignal.timeout(5000) });
        const text = await res.text();
        times.push(performance.now() - t0);
        status = res.status; size = text.length; server = res.headers.get('server') || server;
      } catch (e) {
        times.push(performance.now() - t0);
        kind = errKind(e); msg = errText(e);
        break;
      }
    }
    if (status !== null) {
      reachable = true;
      if (status === 401 || status === 403) authNeeded = true;
      console.log(`  ${p} → HTTP ${status}, ${times.map((t) => fmtMs(t) + 'ms').join('/')}, ${size} байт${server ? `, server: ${server}` : ''}`);
    } else {
      console.log(`  ${p} → ОШИБКА ${kind}: ${msg}`);
    }
  }
  if (!reachable) {
    console.log('ИТОГ PROBE: UNREACHABLE — цель недоступна. Проверьте: 1) запущен ли сервис; 2) хост/порт; 3) firewall/VPN.');
    process.exit(3);
  }
  console.log(`ИТОГ PROBE: REACHABLE${authNeeded ? ' (часть путей требует авторизацию — настройте блок auth в сценарии)' : ''}`);
}

const PRESET_NAMES = ['smoke', 'browse', 'journey', 'stress', 'ci', 'write', 'sql-postgres', 'pipeline-kafka-pg'];

const PRESET_DESC = {
  smoke: 'быстрая проверка конфига (пара GET, run --smoke)',
  browse: 'read-heavy смесь запросов по весам',
  journey: 'сценарий-цепочка (user journey) с capture',
  stress: 'многоступенчатый профиль load.stages (ramp/spike/soak)',
  ci: 'CI-гейт: жёсткие пороги p95/p99/rpsMin + perRequest SLO',
  write: 'write-тест с setup/teardown (allowWrites)',
  'sql-postgres': 'kind:"sql" — нагрузка прямо на Postgres через persistent psql',
  'pipeline-kafka-pg': 'kind:"pipeline" — сквозной лаг Kafka→Postgres (produce→видно в витрине)',
};

function cmdInit(flags) {
  if (flags['list-presets']) {
    console.log('Доступные пресеты (init --preset <name>):');
    for (const n of PRESET_NAMES) console.log(`  ${n.padEnd(8)} — ${PRESET_DESC[n]}`);
    return;
  }
  const out = flags.out || (flags.preset ? `${flags.preset}.json` : 'scenario.json');
  if (existsSync(out) && !flags.force) die(1, `Файл ${out} уже существует. Используйте --force для перезаписи или другое имя через --out.`);
  // --preset <name>: копируем готовый сценарий из presets/<name>.json (валидный, с _comment)
  if (flags.preset) {
    if (!PRESET_NAMES.includes(flags.preset)) die(1, `Неизвестный пресет "${flags.preset}". Доступны: ${PRESET_NAMES.join(', ')}.`);
    const src = join(dirname(fileURLToPath(import.meta.url)), '..', 'presets', `${flags.preset}.json`);
    if (!existsSync(src)) die(1, `Файл пресета не найден: ${src}`);
    try {
      writeFileSync(out, readFileSync(src, 'utf8'), 'utf8');
      console.log(`Создан сценарий из пресета "${flags.preset}": ${out}`);
      console.log(`Дальше: 1) отредактируйте под свой API; 2) node loadgen.mjs validate ${out}; 3) run --smoke; 4) run.`);
    } catch (e) { die(1, `Не удалось создать ${out} из пресета: ${e.message}`); }
    return;
  }
  const template = {
    _comment: 'Шаблон сценария loadgen. Ключи с _ игнорируются. Удалите ненужные блоки.',
    name: 'my-load-test',
    baseUrl: 'http://localhost:8080',
    _baseUrl_hint: 'Только схема://хост:порт, без пути. Секреты — из окружения: "${LOADGEN_TOKEN}" или "${LOADGEN_PW:-дефолт}" (подставляются при запуске, в файл не коммитятся).',
    timeoutMs: 10000,
    headers: {},
    auth: {
      type: 'none',
      _type_hint: 'none | bearer (нужен token) | login (нужен блок login)',
      token: '',
      login: {
        path: '/api/v1/auth/login',
        method: 'POST',
        body: { email: 'user@example.com', password: 'password' },
        tokenField: 'accessToken',
        _tokenField_hint: 'Путь до JWT в JSON-ответе логина, например "data.token"',
      },
    },
    vars: {
      _hint: 'Переменные для {{placeholder}}. Источники: статический список | {setupPath, extract} — из GET-запроса перед стартом | {file} — из файла (.txt построчно, .csv по column, .json по extract). Пути файлов — от папки сценария.',
      exampleId: { setupPath: '/api/v1/items?page=0&size=20', extract: 'content[*].id' },
      _exampleFromTxt: { file: 'data/ids.txt' },
      _exampleFromCsv: { file: 'data/users.csv', column: 'email' },
      _exampleFromJson: { file: 'data/items.json', extract: '[*].id' },
    },
    requests: [
      {
        name: 'list items', method: 'GET', path: '/api/v1/items?page=0&size=20', weight: 5,
        checks: { status: [200], jsonPath: 'content[*].id', maxMs: 500 },
        _checks_hint: 'Валидация ответа: status [коды] | notEmpty | bodyContains "строка" | jsonPath "путь" (должен дать значения) | jsonPathEquals {path, value} | maxMs N (бюджет латентности; не ломает вердикт, но попадает в отчёт)',
      },
      { name: 'item detail', method: 'GET', path: '/api/v1/items/{{exampleId}}', weight: 3 },
    ],
    _requests_hint: 'weight — относительная частота. Встроенные placeholders: {{uuid}}, {{ts}}, {{randInt:1-100}}. Для POST добавьте body и allowWrites:true. Если path/body содержит {{переменную}}-список — в отчёте будет разбивка «ВЛИЯНИЕ ПАРАМЕТРОВ» по каждому значению.',
    _flows_hint: 'АЛЬТЕРНАТИВА requests для СЦЕНАРИЕВ-ЦЕПОЧЕК (user journeys): каждый VU проходит шаги ПО ПОРЯДКУ как одну сессию. "capture" извлекает значения из ответа шага в {{переменные}} для СЛЕДУЮЩИХ шагов. Пример ниже — удалите, если не нужен. Можно задавать и requests, и flows одновременно.',
    _flows_example: [
      {
        name: 'user-journey',
        weight: 1,
        steps: [
          { name: 'j: list', method: 'GET', path: '/api/v1/items?page=0&size=20', checks: { status: [200], jsonPath: 'content[*].id' }, capture: { itemId: 'content[0].id' } },
          { name: 'j: open', method: 'GET', path: '/api/v1/items/{{itemId}}', checks: { status: [200] } },
        ],
      },
    ],
    load: { vus: 5, durationSec: 30, rampUpSec: 5, thinkTimeMs: [100, 300], workers: 1 },
    _load_hint: `vus — параллельные пользователи (max ${MAX_VUS}), durationSec — длительность (max ${MAX_DURATION_SEC}), maxRps — глобальный потолок запросов/сек (опц.), workers — число потоков-генераторов на разные ядра, warmupSec — сколько секунд разогрева исключить из метрик (опц.)`,
    _stages_hint: 'АЛЬТЕРНАТИВА vus/durationSec: многоступенчатый профиль (ramp→плато→спад). Линейная интерполяция между уровнями, старт с 0. Пример ниже (разгон до 20, плато, спад) — вставьте внутрь "load" вместо vus/durationSec.',
    _stages_example: [{ vus: 20, durationSec: 20 }, { vus: 20, durationSec: 30 }, { vus: 0, durationSec: 10 }],
    thresholds: { p95Ms: 1000, errorRatePct: 1 },
    _thresholds_hint: 'Пороги для вердикта PASS/FAIL: p95Ms, errorRatePct, а также опц. p99Ms (хвост латентности) и rpsMin (минимальная пропускная). SLO на конкретный запрос: "perRequest": { "имя запроса": { "p95Ms": 200, "p99Ms": 400, "errorRatePct": 0 } } — тоже в вердикт.',
    _monitor_hint: 'Опц. метрики ЦЕЛИ во время прогона (генератор vs сервис). docker — через `docker stats`; prometheus — произвольный PromQL. thresholds по метрике цели входят в вердикт. Пример ниже — вставьте на верхний уровень как "monitor".',
    _monitor_example: {
      intervalSec: 5,
      docker: { containers: ['my-service-container'] },
      prometheus: { url: 'http://localhost:9090', queries: { 'service CPU %': 'rate(process_cpu_seconds_total[1m])*100' } },
      thresholds: { 'my-service-container CPU %': { max: 85 } },
    },
    allowWrites: false,
  };
  writeFileSync(out, JSON.stringify(template, null, 2), 'utf8');
  console.log(`Создан шаблон сценария: ${out}`);
  console.log(`Дальше: 1) отредактируйте его; 2) node loadgen.mjs validate ${out}; 3) node loadgen.mjs run ${out} --smoke; 4) node loadgen.mjs run ${out}`);
}

function cmdValidate(positional) {
  const scn = loadScenario(positional[0] || die(1, 'Использование: node loadgen.mjs validate <scenario.json>'));
  const { errors, warnings } = validateScenario(scn);
  for (const w of warnings) console.log(`ПРЕДУПРЕЖДЕНИЕ: ${w}`);
  if (errors.length) {
    console.log(`ОШИБКИ (${errors.length}):`);
    for (const e of errors) console.log(`  ✗ ${e}`);
    process.exit(1);
  }
  const writes = scn._steps.filter((r) => scn.kind === 'sql' ? SQL_WRITE_RE.test(r.sql || '') : WRITE_METHODS.includes(r.method)).length;
  const flowNote = scn._hasExplicitFlows ? `, цепочек: ${scn.flows.length}` : '';
  const tgt = scn.kind === 'sql' ? `SQL[${scn.sql.driver || 'custom'}]` : scn.kind === 'pipeline' ? 'PIPELINE (Kafka→verify)' : scn.baseUrl;
  if (scn.kind === 'pipeline') { console.log(`OK: сценарий валиден. Конвейер produce→verify, VUs: ${scn.load.vus}, длительность: ${scn.load.durationSec}с, таймаут лага: ${scn.pipeline.timeoutMs}ms, цель: ${tgt}`); return; }
  console.log(`OK: сценарий валиден. Шагов: ${scn._steps.length} (изменяющих: ${writes})${flowNote}, VUs: ${scn.load.vus}, потоков: ${scn.load.workers}, длительность: ${scn.load.durationSec}с, цель: ${tgt}`);
}

// ─────────────────────────────────────────────── profile: статистика N запросов → черновик сценария ──

const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const ACCESS_LINE_RE = /"(GET|POST|PUT|PATCH|DELETE|HEAD|OPTIONS)\s+(\S+)\s+HTTP\/[\d.]+"\s+(\d{3})/;
const ACCESS_TS_RE = /\[(\d{2})\/(\w{3})\/(\d{4}):(\d{2}):(\d{2}):(\d{2})/;
const MONTHS = { Jan: 0, Feb: 1, Mar: 2, Apr: 3, May: 4, Jun: 5, Jul: 6, Aug: 7, Sep: 8, Oct: 9, Nov: 10, Dec: 11 };

/** Один сегмент пути — «переменная» (ID)? */
function isIdSegment(seg) {
  if (UUID_RE.test(seg)) return true;
  if (/^\d+$/.test(seg)) return true;
  if (/^[0-9a-f]{16,}$/i.test(seg)) return true; // длинный hex-хэш
  return false;
}

/** Путь → { template: '/api/v1/pools/{{p1}}', values: [[val,...]] по позициям } */
function normalizePath(rawPath) {
  const qIdx = rawPath.indexOf('?');
  const pathOnly = qIdx >= 0 ? rawPath.slice(0, qIdx) : rawPath;
  const query = qIdx >= 0 ? rawPath.slice(qIdx) : '';
  const segs = pathOnly.split('/');
  const values = [];
  let pi = 0;
  const outSegs = segs.map((s) => {
    if (s !== '' && isIdSegment(s)) { pi++; values.push([s]); return `{{p${pi}}}`; }
    return s;
  });
  // нормализуем и query: value каждого параметра заменяем на {{q_ключ}} только если это ID
  let queryTemplate = '';
  if (query) {
    const pairs = query.slice(1).split('&').map((kv) => {
      const eq = kv.indexOf('=');
      if (eq < 0) return kv;
      const k = kv.slice(0, eq), v = kv.slice(eq + 1);
      return `${k}=${v}`; // query оставляем как есть (обычно page/size — часть профиля)
    });
    queryTemplate = '?' + pairs.join('&');
  }
  return { template: outSegs.join('/') + queryTemplate, values };
}

function parseProfileInput(raw, format) {
  // автоопределение формата
  const trimmed = raw.replace(/^﻿/, '').trimStart();
  if (!format) {
    if (trimmed[0] === '[' || trimmed[0] === '{') format = 'json';
    else if (/^[^\n]*\bpath\b/i.test(trimmed) && trimmed.includes(',')) format = 'csv';
    else format = 'access';
  }
  const hits = []; // { method, path, count, ts? }
  if (format === 'json') {
    let json;
    try { json = JSON.parse(trimmed); } catch (e) { throw new Error(`вход не парсится как JSON: ${e.message}`); }
    const arr = Array.isArray(json) ? json : Object.entries(json).map(([k, v]) => {
      // ключ вида "METHOD /path [HTTP/x]" или просто "/path"; вытаскиваем метод и первый путь-токен
      const m = k.match(/^\s*(GET|POST|PUT|PATCH|DELETE|HEAD|OPTIONS)?\s*(\S+)/i);
      return { method: (m && m[1] ? m[1] : 'GET').toUpperCase(), path: m ? m[2] : k, count: v };
    });
    for (const it of arr) {
      if (!it || !it.path) continue;
      hits.push({ method: (it.method || 'GET').toUpperCase(), path: it.path, count: Number(it.count ?? it.hits ?? 1) || 1 });
    }
  } else if (format === 'csv') {
    const rows = parseCsv(trimmed);
    if (rows.length < 2) throw new Error('CSV должен содержать заголовок и хотя бы одну строку');
    const header = rows[0].map((h) => h.toLowerCase());
    const pi = header.indexOf('path');
    const mi = header.indexOf('method');
    const ci = header.findIndex((h) => h === 'count' || h === 'hits');
    if (pi < 0) throw new Error(`в CSV нет колонки "path". Есть: ${rows[0].join(', ')}`);
    for (const r of rows.slice(1)) {
      if (!r[pi]) continue;
      hits.push({ method: (mi >= 0 ? r[mi] : 'GET').toUpperCase(), path: r[pi], count: ci >= 0 ? Number(r[ci]) || 1 : 1 });
    }
  } else {
    // access log
    for (const line of trimmed.split(/\r?\n/)) {
      const m = line.match(ACCESS_LINE_RE);
      if (!m) continue;
      const hit = { method: m[1], path: m[2], count: 1 };
      const tm = line.match(ACCESS_TS_RE);
      if (tm && MONTHS[tm[2]] !== undefined) {
        hit.ts = Date.UTC(Number(tm[3]), MONTHS[tm[2]], Number(tm[1]), Number(tm[4]), Number(tm[5]), Number(tm[6]));
      }
      hits.push(hit);
    }
    if (!hits.length) throw new Error('в access-логе не найдено ни одной строки вида "GET /path HTTP/1.1" 200 — проверьте формат или задайте --format');
  }
  return { format, hits };
}

function cmdProfile(positional, flags) {
  const input = positional[0];
  if (!input) die(1, 'Использование: node loadgen.mjs profile <access.log|stats.csv|stats.json> [--format access|csv|json] [--base-url URL] [--top N] [--out scenario.json]');
  if (!existsSync(input)) die(1, `Файл не найден: ${input}`);
  let raw;
  try { raw = readFileSync(input, 'utf8'); } catch (e) { die(1, `Не удалось прочитать ${input}: ${e.message}`); }

  let parsed;
  try { parsed = parseProfileInput(raw, flags.format); } catch (e) { die(1, `Разбор входа не удался: ${e.message}`); }
  const { format, hits } = parsed;

  // группировка по (method, шаблон)
  const groups = new Map(); // key → { method, template, count, valueSets: [Set,...] }
  let minTs = Infinity, maxTs = -Infinity, tsCount = 0;
  for (const h of hits) {
    const { template, values } = normalizePath(h.path);
    const key = `${h.method} ${template}`;
    let g = groups.get(key);
    if (!g) { g = { method: h.method, template, count: 0, valueSets: values.map(() => new Set()) }; groups.set(key, g); }
    g.count += h.count;
    values.forEach((vals, i) => { if (g.valueSets[i]) for (const v of vals) if (g.valueSets[i].size < 200) g.valueSets[i].add(v); });
    if (h.ts) { minTs = Math.min(minTs, h.ts); maxTs = Math.max(maxTs, h.ts); tsCount++; }
  }

  let list = [...groups.values()].sort((a, b) => b.count - a.count);
  // отбрасываем эндпоинты с невалидным путём (не начинается с /) — иначе черновик не пройдёт validate
  const badPaths = list.filter((g) => !/^\//.test(g.template));
  list = list.filter((g) => /^\//.test(g.template));
  const writeMethods = list.filter((g) => WRITE_METHODS.includes(g.method));
  const includeWrites = !!flags['include-writes'];
  if (!includeWrites) list = list.filter((g) => !WRITE_METHODS.includes(g.method));

  const topN = flags.top !== undefined ? Number(flags.top) : 20;
  if (!Number.isInteger(topN) || topN < 1) die(1, `--top должно быть целым числом >= 1, получено: ${flags.top}`);
  if (!list.length) die(1, `После разбора не осталось пригодных ${includeWrites ? '' : 'читающих '}эндпоинтов (разобрано записей: ${hits.length}). Проверьте формат входа или снимите фильтр (--include-writes).`);
  const total = list.reduce((a, g) => a + g.count, 0);
  const top = list.slice(0, topN);

  // оценка RPS из таймстемпов access-лога
  let rpsEstimate = null, spanSec = null;
  if (tsCount > 1 && maxTs > minTs) {
    spanSec = (maxTs - minTs) / 1000;
    rpsEstimate = hits.filter((h) => h.ts).reduce((a, h) => a + h.count, 0) / spanSec;
  }

  // строим сценарий
  const vars = {};
  const usedNames = new Set();
  const requests = top.map((g, idx) => {
    let path = g.template;
    g.valueSets.forEach((set, i) => {
      const pos = i + 1;
      if (!path.includes(`{{p${pos}}}`)) return;
      const varName = `v${idx}_${pos}`;
      const values = [...set];
      if (values.length) { vars[varName] = values; path = path.replace(`{{p${pos}}}`, `{{${varName}}}`); }
      else path = path.replace(`{{p${pos}}}`, '1'); // не было примеров — заглушка
    });
    const weight = Math.max(1, Math.round((g.count / total) * 100));
    // имя должно быть уникальным (метрики агрегируются по имени; validate это требует)
    let name = `${g.method} ${g.template}`;
    if (usedNames.has(name)) name = `${name} #${idx}`;
    usedNames.add(name);
    const req = { name, method: g.method, path, weight };
    if (g.method === 'GET') req.checks = { status: [200] };
    return req;
  });

  const scenario = {
    _comment: `ЧЕРНОВИК, сгенерирован из ${format}-статистики (${input}). ПРОВЕРЬТЕ перед запуском: baseUrl, auth, значения vars, веса.`,
    name: `profiled-${format}`,
    baseUrl: flags['base-url'] || 'http://localhost:8080',
    _baseUrl_note: flags['base-url'] ? undefined : 'ЗАМЕНИТЕ на адрес вашего сервиса',
    timeoutMs: 10000,
    auth: { _note: 'Если API требует авторизацию — заполните: {"type":"login","login":{...}} или {"type":"bearer","token":"..."}', type: 'none' },
    vars: Object.keys(vars).length ? vars : undefined,
    requests,
    load: {
      vus: 10,
      durationSec: 60,
      rampUpSec: 5,
      thinkTimeMs: [50, 200],
      ...(rpsEstimate ? { maxRps: Math.max(1, Math.round(rpsEstimate)) } : {}),
    },
    _load_note: rpsEstimate
      ? `maxRps=${Math.round(rpsEstimate)} — средний RPS из лога за ${Math.round(spanSec)}с. Для стресс-теста уберите maxRps или повышайте.`
      : 'RPS из входных данных вычислить не удалось (нет таймстемпов) — задайте maxRps вручную под нужную интенсивность.',
    thresholds: { p95Ms: 1000, errorRatePct: 1 },
    allowWrites: false,
  };

  const out = flags.out || 'profile-scenario.json';
  writeFileSync(out, JSON.stringify(scenario, (k, v) => (v === undefined ? undefined : v), 2), 'utf8');

  // сводка
  console.log(`profile: разобрано ${hits.length} записей (формат ${format}), уникальных эндпоинтов ${groups.size}`);
  if (badPaths.length) console.log(`  Пропущено ${badPaths.length} записей с некорректным путём (не начинается с /) — проверьте формат входа.`);
  if (writeMethods.length && !includeWrites) console.log(`  Пропущено ${writeMethods.length} изменяющих эндпоинтов (POST/PUT/DELETE) — добавьте --include-writes, если нужны (осторожно: запись).`);
  if (rpsEstimate) console.log(`  Средний RPS из лога: ${rpsEstimate.toFixed(1)} (за ${Math.round(spanSec)}с)`); else console.log('  RPS: нет таймстемпов во входе — maxRps не задан.');
  console.log('');
  console.log('  ТОП эндпоинтов по частоте:');
  console.log(`  ${'доля%'.padEnd(7)}${'запросов'.padEnd(10)}${'vars'.padEnd(6)}метод + шаблон`);
  for (const g of top) {
    const share = ((g.count / total) * 100).toFixed(1);
    const nvars = g.valueSets.filter((s) => s.size).length;
    console.log(`  ${share.padEnd(7)}${String(g.count).padEnd(10)}${String(nvars).padEnd(6)}${g.method} ${g.template}`);
  }
  console.log('');
  console.log(`Черновик сценария записан: ${out}`);
  console.log(`Дальше: 1) впишите baseUrl и auth; 2) node loadgen.mjs validate ${out}; 3) node loadgen.mjs run ${out} --smoke; 4) run.`);
}

// ─────────────────────────────────────────────── compare: регрессия против базы ──

/**
 * Сравнивает два результата прогона (JSON из `run --out`). Чистая функция для CI-гейта и тестов.
 * opts: { maxP95RegressionPct=20, maxErrorIncreasePP=1, minMs=5 }
 * Возвращает { verdict: 'IMPROVED'|'STABLE'|'REGRESSED', overall, perRequest[], flows[], regressions[], added[], removed[], warnings[] }
 */
function compareResults(baseline, current, opts = {}) {
  // Number.isFinite вместо ?? — иначе кривой (NaN) порог молча ОТКЛЮЧИЛ бы гейт регрессий
  const maxP95Pct = Number.isFinite(opts.maxP95RegressionPct) ? opts.maxP95RegressionPct : 20;
  const maxErrPP = Number.isFinite(opts.maxErrorIncreasePP) ? opts.maxErrorIncreasePP : 1;
  const minMs = Number.isFinite(opts.minMs) ? opts.minMs : 5;
  const warnings = [];
  if (baseline.scenario && current.scenario && baseline.scenario !== current.scenario) {
    warnings.push(`сравниваются РАЗНЫЕ сценарии: базовый "${baseline.scenario}" vs текущий "${current.scenario}" — сравнение может быть некорректным`);
  }
  if (baseline.baseUrl && current.baseUrl && baseline.baseUrl !== current.baseUrl) {
    warnings.push(`разные цели: базовая ${baseline.baseUrl} vs текущая ${current.baseUrl}`);
  }

  // % изменения; null (а не Infinity) когда базы нет (base=0) — чтобы не текло "+Infinity%"/null в JSON
  const finiteDelta = (base, cur) => {
    if (base > 0) return Number((((cur - base) / base) * 100).toFixed(1));
    return null; // нет базы для процента
  };
  const showDelta = (d) => (d === null ? 'нов.' : `${d > 0 ? '+' : ''}${d}%`);
  const p95Regressed = (base, cur) => cur > base && cur - base >= minMs && cur > base * (1 + maxP95Pct / 100);
  const errRegressed = (base, cur) => cur > base + maxErrPP;
  const p95Line = (label, base, cur) => (base > 0 ? `${label} ${base}→${cur}ms (${showDelta(finiteDelta(base, cur))}, порог +${maxP95Pct}%)` : `${label} baseline=0, текущий ${cur}ms (нет базы для %)`);

  const regressions = [];

  // общая латентность и ошибки
  const bl = baseline.latencyMs || {}, cl = current.latencyMs || {};
  const b95 = bl.p95 ?? 0, c95 = cl.p95 ?? 0, b99 = bl.p99 ?? 0, c99 = cl.p99 ?? 0;
  const overall = {
    p95: { base: b95, cur: c95, deltaPct: finiteDelta(b95, c95), regressed: p95Regressed(b95, c95) },
    p99: { base: b99, cur: c99, deltaPct: finiteDelta(b99, c99), regressed: p95Regressed(b99, c99) },
    errorRatePct: { base: baseline.errorRatePct ?? 0, cur: current.errorRatePct ?? 0, deltaPP: Number(((current.errorRatePct ?? 0) - (baseline.errorRatePct ?? 0)).toFixed(2)), regressed: errRegressed(baseline.errorRatePct ?? 0, current.errorRatePct ?? 0) },
    rps: { base: baseline.rps ?? 0, cur: current.rps ?? 0, deltaPct: finiteDelta(baseline.rps ?? 0, current.rps ?? 0) },
  };
  if (overall.p95.regressed) regressions.push(p95Line('Общий p95:', b95, c95));
  if (overall.errorRatePct.regressed) regressions.push(`Ошибки выросли ${overall.errorRatePct.base}%→${overall.errorRatePct.cur}% (+${overall.errorRatePct.deltaPP}пп, порог +${maxErrPP}пп)`);

  // по запросам: сначала точное имя, затем НОРМАЛИЗОВАННЫЙ ключ (без query, id-сегменты → {id}),
  // чтобы дрейф имени (query-параметр/переименование) не прятал регресс в added/removed
  const normKey = (name) => {
    const sp = String(name).split(/\s+/);
    const method = sp.length > 1 ? sp[0] : 'GET';
    let path = sp.length > 1 ? sp.slice(1).join(' ') : String(name);
    const qi = path.indexOf('?'); if (qi >= 0) path = path.slice(0, qi);
    const norm = path.split('/').map((s) => (s !== '' && isIdSegment(s) ? '{id}' : s)).join('/');
    return `${method} ${norm}`;
  };
  const baseByName = new Map((baseline.perRequest || []).map((r) => [r.name, r]));
  const curByName = new Map((current.perRequest || []).map((r) => [r.name, r]));
  const baseByNorm = new Map();
  for (const r of baseline.perRequest || []) if (!baseByNorm.has(normKey(r.name))) baseByNorm.set(normKey(r.name), r);
  const matchedBase = new Set();
  const perRequest = [];
  for (const [name, c] of curByName) {
    let b = baseByName.get(name);
    if (b) matchedBase.add(name);
    else { b = baseByNorm.get(normKey(name)); if (b) matchedBase.add(b.name); } // fallback по нормализованному ключу
    if (!b) continue;
    const reg95 = p95Regressed(b.p95, c.p95);
    const regErr = errRegressed(b.errPct, c.errPct);
    perRequest.push({
      name, baseP95: b.p95, curP95: c.p95, deltaPct: finiteDelta(b.p95, c.p95),
      baseErrPct: b.errPct, curErrPct: c.errPct, regressed: reg95 || regErr,
    });
    if (reg95) regressions.push(p95Line(`Запрос "${name}":`, b.p95, c.p95));
    if (regErr) regressions.push(`Запрос "${name}": ошибки ${b.errPct}%→${c.errPct}%`);
  }
  const matchedCurNorm = new Set(perRequest.map((r) => normKey(r.name)));
  const added = [...curByName.keys()].filter((n) => !baseByName.has(n) && !baseByNorm.has(normKey(n)));
  const removed = [...baseByName.keys()].filter((n) => !curByName.has(n) && !matchedBase.has(n) && !matchedCurNorm.has(normKey(n)));
  if (added.length || removed.length) {
    warnings.push(`набор запросов изменился: ${added.length ? `новые [${added.join(', ')}]` : ''}${added.length && removed.length ? '; ' : ''}${removed.length ? `пропали [${removed.join(', ')}]` : ''} — их метрики не сравнивались, проверьте, не спрятался ли за переименованием регресс`);
  }

  // цепочки
  const baseFlows = new Map((baseline.flows || []).map((f) => [f.name, f]));
  const flows = [];
  for (const cf of current.flows || []) {
    const bf = baseFlows.get(cf.name);
    if (!bf) continue;
    const deltaPP = Number((cf.completionPct - bf.completionPct).toFixed(1));
    const regressed = deltaPP < -5; // падение доли завершённых сессий > 5пп
    flows.push({ name: cf.name, baseCompletionPct: bf.completionPct, curCompletionPct: cf.completionPct, deltaPP, regressed });
    if (regressed) regressions.push(`Цепочка "${cf.name}": завершаемость ${bf.completionPct}%→${cf.completionPct}% (${deltaPP}пп)`);
  }

  let verdict = 'STABLE';
  if (regressions.length) verdict = 'REGRESSED';
  else if ((overall.p95.base > 0 && overall.p95.deltaPct <= -10) || overall.errorRatePct.deltaPP < 0) verdict = 'IMPROVED';

  return { verdict, overall, perRequest, flows, regressions, added, removed, warnings };
}

function printCompare(cmp, baseName, curName) {
  const L = console.log;
  L(`Сравнение: базовый "${baseName}" → текущий "${curName}"`);
  for (const w of cmp.warnings) L(`ПРЕДУПРЕЖДЕНИЕ: ${w}`);
  const o = cmp.overall;
  L('');
  L('──────────────── ОБЩИЕ МЕТРИКИ ────────────────────────');
  const arrow = (d) => (d === null ? '' : d > 0 ? '▲' : d < 0 ? '▼' : '=');
  const dp = (d) => (d === null ? 'нов.' : `${arrow(d)}${d}%`); // null = не с чем сравнивать (base=0)
  L(`  p95:     ${o.p95.base}ms → ${o.p95.cur}ms (${dp(o.p95.deltaPct)})${o.p95.regressed ? '  ⚠ РЕГРЕСС' : ''}`);
  L(`  p99:     ${o.p99.base}ms → ${o.p99.cur}ms (${dp(o.p99.deltaPct)})${o.p99.regressed ? '  ⚠ РЕГРЕСС' : ''}`);
  L(`  ошибки:  ${o.errorRatePct.base}% → ${o.errorRatePct.cur}% (${arrow(o.errorRatePct.deltaPP)}${o.errorRatePct.deltaPP}пп)${o.errorRatePct.regressed ? '  ⚠ РЕГРЕСС' : ''}`);
  L(`  RPS:     ${o.rps.base} → ${o.rps.cur} (${dp(o.rps.deltaPct)})`);
  if (cmp.perRequest.length) {
    L('');
    L('──────────────── ПО ЗАПРОСАМ (p95) ────────────────────');
    const head = ['запрос', 'база', 'текущ', 'Δ%', ''];
    const rows = cmp.perRequest.map((r) => [r.name.slice(0, 32), `${r.baseP95}ms`, `${r.curP95}ms`, dp(r.deltaPct), r.regressed ? '⚠' : '']);
    const table = [head, ...rows];
    const w = head.map((_, c) => Math.max(...table.map((row) => String(row[c]).length)));
    for (const row of table) L('  ' + row.map((v, c) => String(v).padEnd(w[c])).join('  '));
  }
  if (cmp.flows.length) {
    L('');
    L('──────────────── ЦЕПОЧКИ (завершаемость) ──────────────');
    for (const f of cmp.flows) L(`  ${f.name}: ${f.baseCompletionPct}% → ${f.curCompletionPct}% (${arrow(f.deltaPP)}${f.deltaPP}пп)${f.regressed ? '  ⚠ РЕГРЕСС' : ''}`);
  }
  if (cmp.added.length) L(`\n  + новые запросы (нет в базе): ${cmp.added.join(', ')}`);
  if (cmp.removed.length) L(`  - пропали из текущего: ${cmp.removed.join(', ')}`);
  L('');
  L('==================== ИТОГ СРАВНЕНИЯ ====================');
  L(`VERDICT: ${cmp.verdict}`);
  if (cmp.regressions.length) {
    L('РЕГРЕССИИ:');
    for (const r of cmp.regressions) L(`  ✗ ${r}`);
  } else {
    L(cmp.verdict === 'IMPROVED' ? 'Метрики улучшились, регрессий нет.' : 'Регрессий нет, метрики стабильны.');
  }
  L('=======================================================');
}

/** Разбирает пороги сравнения из флагов; кривое число — громкая ошибка, а не тихое отключение гейта. */
function parseCompareOpts(flags) {
  const opts = {};
  const num = (flag, key) => {
    if (flags[flag] === undefined) return;
    const n = Number(flags[flag]);
    if (!Number.isFinite(n) || n < 0) die(1, `--${flag}: ожидается неотрицательное число, получено "${flags[flag]}"`);
    opts[key] = n;
  };
  num('max-p95-regression-pct', 'maxP95RegressionPct');
  num('max-error-increase-pp', 'maxErrorIncreasePP');
  return opts;
}

function cmdCompare(positional, flags) {
  const [baseFile, curFile] = positional;
  if (!baseFile || !curFile) die(1, 'Использование: node loadgen.mjs compare <baseline.json> <current.json> [--max-p95-regression-pct N] [--max-error-increase-pp N] [--out diff.json]\nОба файла — это JSON-результаты из `run --out`.');
  const read = (f) => {
    if (!existsSync(f)) die(1, `Файл результата не найден: ${f} (это JSON из "run --out")`);
    try { return JSON.parse(readFileSync(f, 'utf8')); } catch (e) { die(1, `${f} — не валидный JSON результата: ${e.message}`); }
  };
  const baseline = read(baseFile);
  const current = read(curFile);
  if (!baseline.latencyMs || !current.latencyMs) die(1, 'Один из файлов не похож на результат loadgen (нет поля latencyMs). Сравнивать нужно JSON из "run --out".');
  const opts = parseCompareOpts(flags);
  const cmp = compareResults(baseline, current, opts);
  printCompare(cmp, baseline.scenario || baseFile, current.scenario || curFile);
  if (flags.out) {
    try { const dir = dirname(flags.out); if (dir && dir !== '.') mkdirSync(dir, { recursive: true }); writeFileSync(flags.out, JSON.stringify(cmp, null, 2), 'utf8'); console.log(`\nJSON-сравнение: ${flags.out}`); } catch (e) { console.log(`\nНе удалось записать ${flags.out}: ${e.message}`); }
  }
  process.exit(cmp.verdict === 'REGRESSED' ? 2 : 0);
}

// ─────────────────────────────────────────────── merge: агрегация прогонов с N машин ──

/** Складывает N результатов (run --out) в один агрегат: гистограммы суммируются → корректные перцентили. */
function mergeResults(reps) {
  if (!Array.isArray(reps) || reps.length < 2) throw new Error('нужно минимум 2 результата');
  const warnings = [];
  const scenario = reps[0].scenario, baseUrl = reps[0].baseUrl;
  const N = HIST_BOUNDS.length + 1;
  const th0json = JSON.stringify(reps[0].thresholds || {});
  for (const r of reps) {
    if (!Array.isArray(r.hist) || !r.latencyMs) throw new Error('в одном из файлов нет гистограммы (hist) — это результат старой версии (<1.11); пересоберите прогоны');
    // сетка бакетов должна совпадать — иначе поэлементная сумма молча даст мусор
    if (r.histBoundsV !== HIST_BOUNDS_V || r.hist.length !== N) throw new Error(`несовместимая сетка гистограмм (histBoundsV=${r.histBoundsV}, длина=${r.hist.length}; ожидается ${HIST_BOUNDS_V}/${N}) — сливайте результаты ОДНОЙ версии loadgen`);
    // per-request гистограммы обязаны быть валидны — иначе p95=0 ложно прошёл бы SLO
    for (const pr of r.perRequest || []) {
      if (!Array.isArray(pr.hist) || pr.hist.length !== N) throw new Error(`результат неполный: у запроса "${pr.name}" нет корректной гистограммы — пересоберите прогон на текущей версии`);
    }
    if (r.scenario !== scenario) warnings.push(`разные сценарии: "${scenario}" vs "${r.scenario}" — агрегат может быть некорректным`);
    if (r.baseUrl !== baseUrl) warnings.push(`разные цели: ${baseUrl} vs ${r.baseUrl}`);
    if (JSON.stringify(r.thresholds || {}) !== th0json) warnings.push('у результатов РАЗНЫЕ пороги — вердикт считается по первому файлу');
  }
  const sumHist = (arrs) => { const out = new Array(N).fill(0); for (const a of arrs) for (let i = 0; i < N; i++) out[i] += (a && a[i]) || 0; return out; };
  const total = reps.reduce((a, r) => a + (r.total || 0), 0);
  const errors = reps.reduce((a, r) => a + (r.errors || 0), 0);
  const durationSec = Math.max(...reps.map((r) => r.durationSec || 0));
  // суммарный RPS = все запросы / объединённое окно, а НЕ сумма per-window rps: окна машин могут
  // различаться (warmup / ранний финиш воркеров), и сумма rps завысила бы пропускную → ложный PASS
  // порога rpsMin. Math.max(длительностей) слегка занижает → ошибка консервативна (только ложный FAIL).
  const rps = durationSec > 0 ? Number((total / durationSec).toFixed(1)) : 0;
  const oh = sumHist(reps.map((r) => r.hist));
  const latencyMs = { p50: percentileFromHistogram(oh, 50), p90: percentileFromHistogram(oh, 90), p95: percentileFromHistogram(oh, 95), p99: percentileFromHistogram(oh, 99) };
  const errorRatePct = total ? Number(((100 * errors) / total).toFixed(2)) : 0;

  const names = new Set();
  reps.forEach((r) => (r.perRequest || []).forEach((x) => names.add(x.name)));
  const perRequest = [...names].map((name) => {
    const parts = reps.flatMap((r) => (r.perRequest || []).filter((x) => x.name === name));
    const h = sumHist(parts.map((p) => p.hist || []));
    const count = parts.reduce((a, p) => a + (p.count || 0), 0);
    const errCount = parts.reduce((a, p) => a + ((p.errPct || 0) / 100) * (p.count || 0), 0);
    return {
      name, count, rps: durationSec > 0 ? Number((count / durationSec).toFixed(1)) : 0, // из объединённого окна, не сумма per-run rps
      errPct: count ? Number(((100 * errCount) / count).toFixed(2)) : 0,
      p95: percentileFromHistogram(h, 95), p99: percentileFromHistogram(h, 99),
    };
  });

  // вердикт по порогам первого результата (значения одинаковы, если сценарий один)
  const th = reps[0].thresholds || {};
  const checks = [];
  if (th.p95Ms != null) checks.push({ name: `p95 ${latencyMs.p95}ms <= ${th.p95Ms}ms`, pass: latencyMs.p95 <= th.p95Ms });
  if (th.p99Ms != null) checks.push({ name: `p99 ${latencyMs.p99}ms <= ${th.p99Ms}ms`, pass: latencyMs.p99 <= th.p99Ms });
  if (th.errorRatePct != null) checks.push({ name: `errors ${errorRatePct}% <= ${th.errorRatePct}%`, pass: errorRatePct <= th.errorRatePct });
  if (th.rpsMin != null) checks.push({ name: `RPS ${rps} >= ${th.rpsMin}`, pass: rps >= th.rpsMin }); // суммарный RPS всех машин
  if (th.perRequest) for (const [n, t] of Object.entries(th.perRequest)) {
    const r = perRequest.find((x) => x.name === n);
    if (!r) { checks.push({ name: `[${n}] нет в результатах`, pass: false }); continue; }
    if (r.count === 0) { checks.push({ name: `[${n}] не выполнялся ни разу — SLO не подтверждён`, pass: false }); continue; }
    if (t.p95Ms !== undefined) checks.push({ name: `[${n}] p95 ${r.p95}ms <= ${t.p95Ms}ms`, pass: r.errPct < 100 && r.p95 <= t.p95Ms });
    if (t.p99Ms !== undefined) checks.push({ name: `[${n}] p99 ${r.p99}ms <= ${t.p99Ms}ms`, pass: r.errPct < 100 && r.p99 <= t.p99Ms });
    if (t.errorRatePct !== undefined) checks.push({ name: `[${n}] errors ${r.errPct}% <= ${t.errorRatePct}%`, pass: r.errPct <= t.errorRatePct });
  }
  const verdict = checks.length && checks.every((c) => c.pass) ? 'PASS' : (checks.length ? 'FAIL' : 'PASS');
  return { machines: reps.length, scenario, baseUrl, total, errors, errorRatePct, rps, durationSec, latencyMs, perRequest, thresholds: th, checks, verdict, warnings };
}

function printMerge(m) {
  const L = console.log;
  L(`Слияние ${m.machines} прогонов | сценарий "${m.scenario}" | цель ${m.baseUrl}`);
  for (const w of m.warnings) L(`ПРЕДУПРЕЖДЕНИЕ: ${w}`);
  L('');
  L('──────────────── СВОДНО ПО ЗАПРОСАМ ───────────────────');
  const head = ['запрос', 'кол-во', 'rps', 'err%', 'p95', 'p99'];
  const table = [head, ...m.perRequest.map((r) => [r.name.slice(0, 32), r.count, r.rps, r.errPct, r.p95, r.p99])];
  const w = head.map((_, c) => Math.max(...table.map((row) => String(row[c]).length)));
  for (const row of table) L('  ' + row.map((v, c) => String(v).padEnd(w[c])).join('  '));
  L('');
  L('==================== ИТОГ (агрегат) ====================');
  L(`VERDICT: ${m.verdict}`);
  L(`Машин: ${m.machines} | суммарно запросов: ${m.total} за ~${m.durationSec}с | СУММАРНЫЙ RPS: ${m.rps} | ошибок: ${m.errorRatePct}%`);
  L(`Латентность мс (из гистограмм): p50 ${m.latencyMs.p50} | p90 ${m.latencyMs.p90} | p95 ${m.latencyMs.p95} | p99 ${m.latencyMs.p99}`);
  for (const c of m.checks) L(`Порог: ${c.name} ${c.pass ? 'OK' : 'НАРУШЕН'}`);
  L('========================================================');
}

function cmdMerge(positional, flags) {
  if (positional.length < 2) die(1, 'Использование: node loadgen.mjs merge <r1.json> <r2.json> [...] [--out merged.json]\nОбъединяет результаты N машин/прогонов (run --out) в один агрегат: суммарный RPS + корректные перцентили из гистограмм.');
  const reps = positional.map((f) => {
    if (!existsSync(f)) die(1, `Файл результата не найден: ${f}`);
    try { return JSON.parse(readFileSync(f, 'utf8')); } catch (e) { die(1, `${f} — не валидный JSON результата: ${e.message}`); }
  });
  let m;
  try { m = mergeResults(reps); } catch (e) { die(1, `Слияние не удалось: ${e.message}`); }
  printMerge(m);
  if (flags.out) {
    try { const dir = dirname(flags.out); if (dir && dir !== '.') mkdirSync(dir, { recursive: true }); writeFileSync(flags.out, JSON.stringify(m, null, 2), 'utf8'); console.log(`\nJSON-агрегат: ${flags.out}`); } catch (e) { console.log(`\nНе удалось записать ${flags.out}: ${e.message}`); }
  }
  process.exit(m.verdict === 'FAIL' ? 2 : 0);
}

async function cmdRun(positional, flags) {
  const file = positional[0];
  if (!file) die(1, 'Использование: node loadgen.mjs run <scenario.json> [--smoke] [--vus N] [--duration N] [--out FILE]');
  // --base-url прокидываем в loadScenario (применяется ДО подстановки env, чтобы baseUrl:"${VAR}" + --base-url работал);
  // loadScenario уже гарантирует, что scn — объект (иначе die), поэтому override'ы ниже безопасны
  const scn = loadScenario(file, { baseUrl: flags['base-url'] });

  // переопределения CLI (числовые; нечисловой --duration/--max-rps → NaN поймает validateScenario)
  if (flags.vus) { scn.load = scn.load || {}; scn.load.vus = Number(flags.vus); }
  if (flags.duration) { scn.load = scn.load || {}; scn.load.durationSec = Number(flags.duration); }
  if (flags['max-rps']) { scn.load = scn.load || {}; scn.load.maxRps = Number(flags['max-rps']); }
  if (flags.workers) { scn.load = scn.load || {}; scn.load.workers = Number(flags.workers); }

  const { errors, warnings } = validateScenario(scn);
  for (const w of warnings) console.log(`ПРЕДУПРЕЖДЕНИЕ: ${w}`);
  if (errors.length) {
    console.log(`Сценарий невалиден, запуск отменён. ОШИБКИ (${errors.length}):`);
    for (const e of errors) console.log(`  ✗ ${e}`);
    process.exit(1);
  }

  // защита: запись (нагрузка + setup + teardown). Для SQL — по ключевым словам DML/DDL, для HTTP — по методу; pipeline всегда пишет (produce).
  const stepWrites = (r) => scn.kind === 'sql' ? SQL_WRITE_RE.test(r.sql || '') : WRITE_METHODS.includes(String(r.method || '').toUpperCase());
  const hasWrites = scn.kind === 'pipeline' || [...scn._steps, ...(scn._setup || []), ...(scn._teardown || [])].some(stepWrites);
  if (hasWrites && !flags['allow-writes']) {
    die(1, 'Сценарий содержит изменяющие операции (allowWrites:true задан), но для запуска нужен ещё явный флаг --allow-writes.\nЭто двойная защита: убедитесь, что пользователь явно разрешил запись в целевую систему.');
  }
  // защита: внешний хост — только для HTTP (у SQL/pipeline цель = локальные CLI-клиенты, не URL)
  if (scn.kind === 'http') {
    const host = new URL(scn.baseUrl).hostname;
    if (!isPrivateHost(host) && !flags['confirm-external']) {
      die(1, `Цель ${scn.baseUrl} — внешний хост (не localhost/приватная сеть).\n` +
        'Нагрузка на чужие системы без разрешения недопустима. Если это ВАШ сервер и тест согласован — добавьте флаг --confirm-external.');
    }
  }

  const smoke = !!flags.smoke;
  const targetLabel = scn.kind === 'sql' ? `SQL[${scn.sql.driver || 'custom'}] ${scn.sql.command.slice(-3).join(' ')}`
    : scn.kind === 'pipeline' ? `PIPELINE produce[${scn.produce.command.slice(-1)}] → verify[${scn.verify.sql.driver || 'custom'}]` : scn.baseUrl;
  const loadDesc = scn.load.stages
    ? `STAGES (${scn.load.stages.length} ступ., пик ${scn.load.vus} VUs, ${scn.load.durationSec}с${scn.load.workers > 1 ? `, ×${scn.load.workers} потоков` : ''})`
    : `LOAD (${scn.load.vus} VUs${scn.load.workers > 1 ? `×${scn.load.workers} потоков` : ''}, ${scn.load.durationSec}с)`;
  console.log(`loadgen v${VERSION} | сценарий "${scn.name || '(без имени)'}" | цель ${targetLabel} | режим ${smoke ? `SMOKE (${scn._hasExplicitFlows ? 'каждая цепочка' : 'каждый запрос'} по 1 разу)` : loadDesc}`);

  // pre-flight: логин + переменные
  let preToken = null;
  try {
    if (scn.auth.type === 'login') { preToken = await doLogin(scn); console.log('Логин: OK'); }
    else if (scn.auth.type === 'bearer') preToken = scn.auth.token;
    if (scn.vars && Object.keys(scn.vars).length) {
      const vars = await resolveVars(scn, preToken);
      scn._resolvedVars = vars;
      console.log(`Переменные: ${Object.entries(vars).map(([k, v]) => `${k}(${Array.isArray(v) ? v.length : 1} знач.)`).join(', ')}`);
    } else scn._resolvedVars = {};
  } catch (e) {
    console.log(`PRE-FLIGHT ОШИБКА: ${e.message}`);
    console.log('Запуск нагрузки отменён — сначала почините конфигурацию (см. сообщение выше).');
    process.exit(3);
  }

  // teardown — идемпотентный запуск очистки; зовём и на нормальном пути, и перед аварийным exit,
  // чтобы созданные в setup сущности не утекли (best-effort, одна упавшая уборка не рушит остальные).
  const lifecycle = { setup: [], teardown: [], _teardownRan: false };
  const runTeardownPhase = async () => {
    if (!scn._teardown || !scn._teardown.length || lifecycle._teardownRan) return;
    lifecycle._teardownRan = true;
    console.log(`\nTeardown (${scn._teardown.length} шаг.):`);
    const tr = await runLifecyclePhase(scn, scn._teardown, preToken, scn._resolvedVars, { abortOnFail: false });
    lifecycle.teardown = tr.results;
    for (const x of tr.results) console.log(`  ${x.ok ? '[OK]  ' : '[FAIL]'} ${x.name}${x.ok ? '' : ` — ${x.error}`}`);
  };

  // Ctrl+C во время setup/подготовки: без раннего обработчика Node выходит НЕМЕДЛЕННО и teardown
  // не успевает откатить созданное в setup → сущности утекают. Перед стартом нагрузки его снимаем
  // (там свои обработчики прерывания). Идемпотентность гарантирует _teardownRan внутри runTeardownPhase.
  let _tearingDown = false;
  const earlySigint = () => {
    if (_tearingDown) return;
    _tearingDown = true;
    console.log('\nПрерывание на этапе подготовки — откат созданного (teardown)…');
    runTeardownPhase().catch(() => {}).finally(() => process.exit(130));
  };
  process.on('SIGINT', earlySigint);

  // setup — фаза подготовки (создать сущности, захватить id) до нагрузки; захваты идут в _resolvedVars
  if (scn._setup && scn._setup.length) {
    console.log(`Setup (${scn._setup.length} шаг.):`);
    const sr = await runLifecyclePhase(scn, scn._setup, preToken, scn._resolvedVars, { abortOnFail: true });
    lifecycle.setup = sr.results;
    for (const x of sr.results) console.log(`  ${x.ok ? '[OK]  ' : '[FAIL]'} ${x.name}${x.captured.length ? ` [captured: ${x.captured.join(', ')}]` : ''}${x.ok ? '' : ` — ${x.error}`}`);
    if (!sr.allOk) {
      console.log('SETUP ПРОВАЛЕН — нагрузка не запускается.');
      await runTeardownPhase(); // откат того, что успело создаться
      process.exit(3);
    }
  }

  let stats = makeStats(scn._steps);
  const loginFailures = [];
  let resource = null;
  let incompleteWorkers = 0;
  // монитор метрик ЦЕЛИ (docker/prometheus) — только для полноценного прогона, не smoke
  const targetMon = !smoke ? startTargetMonitor(scn) : null;
  if (targetMon) { const parts = []; if (scn.monitor.docker) parts.push(`docker[${scn.monitor.docker.containers.join(',')}]`); if (scn.monitor.prometheus) parts.push(`prometheus[${Object.keys(scn.monitor.prometheus.queries).length} запр.]`); if (scn.monitor.kafka) parts.push(`kafka-lag[${scn.monitor.kafka.groups.join(',')}]`); console.log(`Монитор цели: ${parts.join(' + ')}`); }
  let targetMetrics = null;

  if (scn.kind === 'pipeline') {
    // ─── конвейерный режим: produce→poll→лаг (всегда однопоточно — поллинг I/O-bound) ───
    process.removeListener('SIGINT', earlySigint);
    const ctx = { aborted: false };
    process.on('SIGINT', () => { ctx.aborted = true; console.log('\nПрерывание — формирую отчёт по собранным данным...'); });
    const resMon = startResourceMonitor({ watchEventLoop: true });
    stats.startedAt = Date.now();
    if (!smoke && scn.load.warmupSec && !scn.load.stages) stats.warmupUntil = stats.startedAt + scn.load.warmupSec * 1000;
    if (smoke) {
      console.log('');
      const producer = openProducer(scn);
      const verify = openSqlSession(scn, scn._verifyDriver);
      const corr = randomUUID();
      const vars = { ...scn._resolvedVars, corr };
      let msg, query;
      try { const u = {}; msg = renderTemplate(scn.produce.message, vars, u); query = renderTemplate(scn.verify.query, vars, u); } // общий used + try/catch: неизвестный плейсхолдер = чистый FAIL, не краш
      catch (e) {
        stats.record({ name: 'pipeline' }, { ms: 0, status: 0, ok: false, kind: 'config', errMsg: e.message }, Date.now());
        console.log(`  [FAIL] pipeline: ошибка шаблона produce/verify — ${e.message}`);
      }
      if (msg !== undefined) {
        const t0 = performance.now();
        producer.send(msg);
        let materialized = false;
        while (performance.now() - t0 < scn.pipeline.timeoutMs) {
          const r = await verify.query(query, Math.max(1, Math.min(scn.timeoutMs, scn.pipeline.timeoutMs - (performance.now() - t0))));
          if (r.ok && (r.rows || 0) >= 1) { materialized = true; break; }
          await sleep(scn.pipeline.pollIntervalMs);
        }
        const lag = performance.now() - t0;
        stats.record({ name: 'pipeline' }, materialized ? { ms: lag, status: 'OK', ok: true } : { ms: lag, status: 0, ok: false, kind: 'stuck', errMsg: `не материализовалось за ${scn.pipeline.timeoutMs}ms` }, Date.now());
        console.log(materialized
          ? `  [OK]   pipeline: сообщение материализовалось за ${fmtMs(lag)}ms (corr=${corr.slice(0, 8)}…)`
          : `  [FAIL] pipeline: НЕ материализовалось за ${scn.pipeline.timeoutMs}ms — проверьте топик/консьюмер/verify.query (corr=${corr.slice(0, 8)}…)`);
      }
      await producer.close(); await verify.close();
    } else {
      if (scn.load.workers > 1) console.log('ПРЕДУПРЕЖДЕНИЕ: kind:"pipeline" выполняется однопоточно (поллинг I/O-bound) — load.workers игнорируется');
      const endAt = stats.startedAt + scn.load.durationSec * 1000;
      const rampMs = scn.load.rampUpSec * 1000;
      let lastTotal = 0;
      const progress = flags.quiet ? null : setInterval(() => {
        const rps = (stats.total - lastTotal) / 5; lastTotal = stats.total;
        console.log(`  t=${Math.round((Date.now() - stats.startedAt) / 1000)}с событий=${stats.total} rps=${rps.toFixed(0)} не долетело/ошибок=${stats.errors}`);
      }, 5000);
      await runPipelineSlice({ scn, stats, vuCount: scn.load.vus, vuBase: 0, vuStride: 1, totalVus: scn.load.vus, endAt, rampMs, effectiveMaxRps: scn.load.maxRps || 0, ctx, startedAt: stats.startedAt });
      if (progress) clearInterval(progress);
    }
    stats.endedAt = Date.now();
    resource = resMon.finish();
  } else if (smoke) {
    // прогоняем каждую цепочку целиком (шаги по порядку с реальным захватом переменных) по 1 разу
    stats.startedAt = Date.now();
    console.log('');
    const isSql = scn.kind === 'sql';
    const smokeConn = isSql ? openSqlSession(scn) : null;
    try {
      for (const flow of scn._flows) {
        const isJourney = flow.steps.length > 1;
        if (isJourney) console.log(`  ▸ цепочка "${flow.name}":`);
        const vars = { ...scn._resolvedVars };
        for (const step of flow.steps) {
          const r = await callOnce(scn, step, vars, preToken, smokeConn);
          let rec = r;
          if (r.ok && step.capture && scn.kind !== 'sql') { // capture в SQL пока не поддержан — не пытаемся (у SQL-записи нет r.text)
            const cap = applyCaptures(step, r.text, vars);
            if (!cap.ok) rec = { ms: r.ms, status: r.status, ok: false, kind: 'capture', errMsg: cap.error };
          }
          stats.record(step, rec, Date.now());
          const capNote = rec.ok && step.capture ? ` [captured: ${Object.keys(step.capture).join(', ')}]` : '';
          const pad = isJourney ? '    ' : '  ';
          const verb = isSql ? 'SQL' : step.method;
          const okDesc = isSql ? `OK${rec.rows != null ? ` rows=${rec.rows}` : ''}` : `HTTP ${rec.status}`;
          const badDesc = (rec.status && !isSql) ? `HTTP ${rec.status}` : rec.kind;
          const line = rec.ok
            ? `${pad}[OK]   ${step.name}: ${verb} → ${okDesc} (${fmtMs(rec.ms)}ms)${capNote}`
            : `${pad}[FAIL] ${step.name}: ${verb} → ${badDesc} (${fmtMs(rec.ms)}ms) ${rec.errMsg || rec.snippet || ''}`.trimEnd();
          console.log(line);
          if (!rec.ok && isJourney) { console.log(`    ↳ цепочка оборвана на этом шаге, остальные шаги пропущены`); break; }
        }
      }
    } finally { if (smokeConn) await smokeConn.close(); }
    stats.endedAt = Date.now();
  } else if (scn.load.workers > 1) {
    // ─── многопоточный режим: worker_threads на несколько ядер ──
    process.removeListener('SIGINT', earlySigint); // дальше — свой обработчик прерывания нагрузки
    const workers = Math.min(scn.load.workers, scn.load.vus);
    console.log(`Потоков-генераторов: ${workers} (ядер доступно: ${os.cpus().length})`);
    const resMon = startResourceMonitor({ watchEventLoop: false });
    stats.startedAt = Date.now();
    const endAt = stats.startedAt + scn.load.durationSec * 1000;
    const rampMs = scn.load.rampUpSec * 1000;
    const scnJson = JSON.stringify(scn);

    // распределяем VU по воркерам ЧЕРЕДУЯ индексы (round-robin): воркер w владеет
    // глобальными индексами w, w+workers, w+2*workers… — при stages активный набор [0,target)
    // ложится на все потоки равномерно, а не сериализуется на младший поток.
    const base = Math.floor(scn.load.vus / workers);
    const rem = scn.load.vus % workers;
    const parts = [];
    const workerLoginFailures = [];
    const workerErrors = [];
    let elLagMaxMs = 0;
    const ticks = new Array(workers).fill(null).map(() => ({ total: 0, errors: 0 }));
    const workerObjs = [];

    let progressTimer = null;
    const startProgress = () => {
      if (flags.quiet) return;
      let lastTotal = 0;
      progressTimer = setInterval(() => {
        const total = ticks.reduce((a, t) => a + t.total, 0);
        const errors = ticks.reduce((a, t) => a + t.errors, 0);
        const rps = (total - lastTotal) / 5;
        lastTotal = total;
        const errPct = total ? ((100 * errors) / total).toFixed(1) : '0.0';
        console.log(`  t=${Math.round((Date.now() - stats.startedAt) / 1000)}с всего=${total} rps=${rps.toFixed(0)} err=${errPct}% (${workers} потоков)`);
      }, 5000);
    };

    const runResult = await new Promise((resolve) => {
      let done = 0;
      const finalize = () => { if (progressTimer) clearInterval(progressTimer); resolve(); };
      process.on('SIGINT', () => { console.log('\nПрерывание — останавливаю потоки...'); for (const w of workerObjs) w.postMessage('abort'); });
      for (let w = 0; w < workers; w++) {
        const vuCount = base + (w < rem ? 1 : 0); // столько индексов в residue-классе w
        const worker = new Worker(fileURLToPath(import.meta.url), {
          workerData: {
            role: 'load-slice', scnJson, preToken, vuCount, vuBase: w, vuStride: workers,
            totalVus: scn.load.vus, endAt, rampMs, startedAt: stats.startedAt,
            effectiveMaxRps: scn.load.maxRps ? scn.load.maxRps / workers : 0,
          },
        });
        workerObjs.push(worker);
        worker.on('message', (m) => {
          if (m.type === 'tick') { ticks[w] = { total: m.total, errors: m.errors }; }
          else if (m.type === 'result') {
            parts.push(m.stats);
            workerLoginFailures.push(...m.loginFailures);
            if (m.elLagMaxMs != null) elLagMaxMs = Math.max(elLagMaxMs, m.elLagMaxMs);
          }
        });
        worker.on('error', (e) => { workerErrors.push(`Поток #${w}: ${e.message}`); console.log(`Поток #${w} упал: ${e.message}`); });
        worker.on('exit', () => { if (++done === workers) finalize(); });
      }
      startProgress();
    });
    void runResult;

    if (!parts.length) { console.log('Ни один поток не вернул результат — прогон не удался.'); await runTeardownPhase(); process.exit(2); }
    // частичный крах: часть нагрузки не выполнена — нельзя выдавать это за валидный результат
    if (parts.length < workers) {
      incompleteWorkers = workers - parts.length;
      console.log(`⚠ ВНИМАНИЕ: ${incompleteWorkers} из ${workers} потоков не вернули данные (${workerErrors.join('; ') || 'причина неизвестна'}). Результат НЕПОЛНЫЙ.`);
    }
    stats = mergeStats(parts, scn._steps);
    // stats.endedAt уже выставлен mergeStats = max(endedAt воркеров) — НЕ перетираем его пост-фактум
    if (scn.load.warmupSec && !scn.load.stages) stats.warmupUntil = stats.startedAt + scn.load.warmupSec * 1000;
    loginFailures.push(...workerLoginFailures);
    resource = resMon.finish(elLagMaxMs);
  } else {
    // ─── однопоточный режим ──
    process.removeListener('SIGINT', earlySigint); // дальше — свой обработчик прерывания нагрузки
    const resMon = startResourceMonitor({ watchEventLoop: true });
    const ctx = { aborted: false };
    process.on('SIGINT', () => { ctx.aborted = true; console.log('\nПрерывание — формирую отчёт по собранным данным...'); });

    stats.startedAt = Date.now();
    if (scn.load.warmupSec && !scn.load.stages) stats.warmupUntil = stats.startedAt + scn.load.warmupSec * 1000;
    const endAt = stats.startedAt + scn.load.durationSec * 1000;
    const rampMs = scn.load.rampUpSec * 1000;

    let lastTotal = 0;
    const winLat = [];
    const progress = flags.quiet ? null : setInterval(() => {
      const now = Date.now();
      const rps = (stats.total - lastTotal) / 5;
      lastTotal = stats.total;
      const sorted = winLat.splice(0).sort((a, b) => a - b);
      const errPct = stats.total ? ((100 * stats.errors) / stats.total).toFixed(1) : '0.0';
      const tgt = scn.load.stages ? ` цель=${Math.round(stageTargetAt(scn.load.stages, now - stats.startedAt))}VU` : '';
      console.log(`  t=${Math.round((now - stats.startedAt) / 1000)}с всего=${stats.total} rps=${rps.toFixed(0)} err=${errPct}% p95(окно)=${fmtMs(pct(sorted, 95))}ms${tgt}`);
    }, 5000);

    await runLoadSlice({
      scn, preToken, stats, vuCount: scn.load.vus, vuBase: 0, vuStride: 1, totalVus: scn.load.vus,
      endAt, rampMs, effectiveMaxRps: scn.load.maxRps || 0, ctx, loginFailures,
      // при --quiet winLat никогда не сливается (нет прогресс-таймера) — не копим его вовсе, иначе утечёт
      onSample: flags.quiet ? null : (ms) => winLat.push(ms), startedAt: stats.startedAt,
    });
    stats.endedAt = Date.now();
    if (progress) clearInterval(progress);
    resource = resMon.finish();
  }

  if (targetMon) targetMetrics = summarizeTargetMetrics(targetMon.stop(), scn.load.warmupSec && !scn.load.stages ? scn.load.warmupSec : 0);

  // teardown — очистка (best-effort, выполняется после нагрузки, даже если она упала/прервана)
  await runTeardownPhase();

  const rep = buildReport(scn, stats, { smoke, loginFailures, resource, incompleteWorkers, targetMetrics, lifecycle });
  printReport(rep);

  const outFile = flags.out || 'loadgen-result.json';
  try {
    const dir = dirname(outFile);
    if (dir && dir !== '.') mkdirSync(dir, { recursive: true });
    writeFileSync(outFile, JSON.stringify(rep, null, 2), 'utf8');
    console.log(`\nJSON-результат: ${outFile}`);
  } catch (e) {
    console.log(`\nНе удалось записать ${outFile}: ${e.message}`);
  }

  // отчёты для CI
  const writeReport = (path, content, label) => {
    try {
      const dir = dirname(path); if (dir && dir !== '.') mkdirSync(dir, { recursive: true });
      writeFileSync(path, content, 'utf8'); console.log(`${label}: ${path}`);
    } catch (e) { console.log(`Не удалось записать ${path}: ${e.message}`); }
  };
  if (flags.junit) writeReport(flags.junit, toJUnitXml(rep), 'JUnit XML');
  if (flags.md) writeReport(flags.md, toMarkdown(rep), 'Markdown-отчёт');
  if (flags.html) writeReport(flags.html, toHtml(rep), 'HTML-отчёт');

  // --baseline: сразу сравнить с эталонным прогоном (CI-гейт регрессий)
  let regressed = false;
  if (!smoke && flags.baseline) {
    if (!existsSync(flags.baseline)) {
      console.log(`\n⚠ --baseline: файл ${flags.baseline} не найден — сравнение пропущено (сохраните текущий как базу для следующего раза).`);
    } else {
      try {
        const baseline = JSON.parse(readFileSync(flags.baseline, 'utf8'));
        const cmp = compareResults(baseline, rep, parseCompareOpts(flags));
        console.log('');
        printCompare(cmp, baseline.scenario || flags.baseline, rep.scenario || '(текущий)');
        regressed = cmp.verdict === 'REGRESSED';
      } catch (e) {
        console.log(`\n⚠ --baseline: не удалось сравнить с ${flags.baseline}: ${e.message}`);
      }
    }
  }

  // exit: FAIL по порогам ИЛИ регресс против базы → 2
  process.exit(rep.verdict === 'PASS' && !regressed ? 0 : 2);
}

// ─────────────────────────────────────────────── worker-режим ──

/** Точка входа воркера: гоняет свою долю VU и отсылает статистику в главный поток. */
async function runWorkerSlice() {
  const { scnJson, preToken, vuCount, vuBase, vuStride, totalVus, endAt, rampMs, effectiveMaxRps, startedAt } = workerData;
  const scn = JSON.parse(scnJson);
  if (scn.kind === 'sql') scn._sqlDriver = resolveSqlDriver(scn.sql); // RegExp не переживает JSON — пересобираем драйвер в воркере
  const stats = makeStats(scn._steps);
  const loginFailures = [];
  const ctx = { aborted: false };
  parentPort.on('message', (m) => { if (m === 'abort') ctx.aborted = true; });
  const elMon = monitorEventLoopDelay({ resolution: 20 });
  elMon.enable();
  const tick = setInterval(() => parentPort.postMessage({ type: 'tick', total: stats.total, errors: stats.errors }), 2000);
  if (tick.unref) tick.unref();
  stats.startedAt = startedAt || Date.now();
  if (scn.load.warmupSec && !scn.load.stages) stats.warmupUntil = stats.startedAt + scn.load.warmupSec * 1000; // окно разогрева от ГЛОБАЛЬНОГО старта
  await runLoadSlice({ scn, preToken, stats, vuCount, vuBase, vuStride, totalVus, endAt, rampMs, effectiveMaxRps, ctx, loginFailures, startedAt });
  stats.endedAt = Date.now();
  clearInterval(tick);
  elMon.disable();
  parentPort.postMessage({ type: 'result', stats: serializeStats(stats), loginFailures, elLagMaxMs: elMon.max / 1e6 });
  parentPort.close(); // снимаем listener, иначе воркер не завершится
}

// ─────────────────────────────────────────────── main ──

// Экспорт чистых функций для self-тестов (node:test). При import модуль НЕ запускает CLI.
export { extractPath, renderTemplate, parseCsv, normalizePath, isIdSegment, evaluateResponse, applyCaptures, validateScenario, makeStats, mergeStats, serializeStats, compareResults, stageTargetAt, toJUnitXml, toMarkdown, parseMemMB, summarizeTargetMetrics, resolveEnvInScenario, encodeForm, buildMultipart, histogram, percentileFromHistogram, mergeResults, toHtml, asciiHistogram, resolveSqlDriver, parseSqlChunk, parseKafkaLag };

// Запуск CLI только при прямом вызове `node loadgen.mjs ...` (не при import из теста).
const invokedDirectly = !!process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href;

if (!isMainThread && workerData && workerData.role === 'load-slice') {
  await runWorkerSlice();
} else if (invokedDirectly) {
  await runCli();
}

async function runCli() {
const { cmd, positional, flags } = parseArgs(process.argv.slice(2));

const HELP = `loadgen v${VERSION} — REST load generator (Node >= 18, без зависимостей)

Команды:
  probe <baseUrl> [path ...]        проверить доступность цели (exit 0/3)
  init [--out FILE] [--force]        создать шаблон сценария
    --preset NAME                    готовый сценарий: smoke|browse|journey|stress|ci|write
    --list-presets                   показать доступные пресеты с описанием
  validate <scenario.json>          проверить сценарий (exit 0/1)
  profile <log|csv|json>            построить черновик сценария из статистики N запросов
    --format access|csv|json         формат входа (по умолчанию — автоопределение)
    --base-url URL                   цель для сценария
    --top N                          сколько самых частых эндпоинтов взять (по умолч. 20)
    --include-writes                 включить POST/PUT/DELETE (по умолчанию только чтение)
    --out FILE                       куда записать черновик сценария
  compare <base.json> <cur.json>    сравнить два результата run, найти регрессию (exit 0/2)
    --max-p95-regression-pct N       допустимый рост p95 в % (по умолч. 20)
    --max-error-increase-pp N        допустимый рост доли ошибок в пунктах (по умолч. 1)
    --out FILE                       записать сравнение в JSON
  merge <r1.json> <r2.json> [...]   агрегировать прогоны с N машин: суммарный RPS +
                                     корректные перцентили из гистограмм (exit 0/2)
    --out FILE                       записать агрегат в JSON
  run <scenario.json>               прогнать нагрузку (exit 0=PASS, 2=FAIL, 3=цель недоступна)
    --smoke                          каждый запрос по 1 разу, последовательно (проверка конфига)
    --vus N --duration N             переопределить нагрузку
    --workers N                      число потоков-генераторов (по умолч. из сценария/1)
    --base-url URL                   переопределить цель
    --out FILE                       файл JSON-результата (по умолч. loadgen-result.json)
    --junit FILE                     дополнительно записать отчёт в JUnit XML (для CI)
    --md FILE                        дополнительно записать отчёт в Markdown (для PR-комментария)
    --html FILE                      дополнительно записать красивый HTML-отчёт (бар-чарт латентности)
    --baseline FILE                  сравнить результат с эталоном (регресс → exit 2)
    --allow-writes                   подтвердить изменяющие запросы
    --confirm-external               подтвердить нагрузку на внешний хост
    --quiet                          без прогресса каждые 5с

Параллелизм: load.workers (или --workers) распределяет VU по потокам worker_threads на
несколько ядер. Отчёт всегда включает секцию РЕСУРСЫ ГЕНЕРАТОРА и предупреждает, если
упёрлись в CPU/event-loop/память (тогда латентность завышена самим генератором).

Рекомендуемый порядок: probe → (profile) → init → validate → run --smoke → run`;

switch (cmd) {
  case 'probe': await cmdProbe(positional); break;
  case 'init': cmdInit(flags); break;
  case 'validate': cmdValidate(positional); break;
  case 'profile': cmdProfile(positional, flags); break;
  case 'compare': cmdCompare(positional, flags); break;
  case 'merge': cmdMerge(positional, flags); break;
  case 'run': await cmdRun(positional, flags); break;
  case undefined:
  case 'help':
  case '--help':
    console.log(HELP); break;
  default:
    die(1, `Неизвестная команда "${cmd}".\n\n${HELP}`);
}
}
