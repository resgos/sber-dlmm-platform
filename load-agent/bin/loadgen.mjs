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
import { dirname, join, isAbsolute } from 'node:path';
import { randomUUID } from 'node:crypto';
import { performance, monitorEventLoopDelay } from 'node:perf_hooks';
import { Worker, isMainThread, parentPort, workerData } from 'node:worker_threads';
import { fileURLToPath } from 'node:url';
import os from 'node:os';

const VERSION = '1.2.0';
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

/** Извлечение по dot-пути с поддержкой [*] и [N]: "content[*].id", "data.accessToken", "[0].id" */
function extractPath(root, path) {
  let nodes = [root];
  let fan = false;
  for (const part of String(path).split('.')) {
    const m = part.match(/^([\w$-]*)(?:\[(\*|\d+)\])?$/);
    if (!m) throw new Error(`некорректный extract-путь "${path}" (сегмент "${part}")`);
    const [, key, idx] = m;
    nodes = nodes.flatMap((n) => {
      if (n == null) return [];
      let v = key === '' ? n : n[key];
      if (v == null) return [];
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
      const needsValue = ['out', 'vus', 'duration', 'base-url', 'max-rps', 'workers', 'top', 'format'].includes(name);
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

const KNOWN_ROOT_KEYS = ['name', 'baseUrl', 'headers', 'timeoutMs', 'auth', 'vars', 'requests', 'load', 'thresholds', 'allowWrites'];
const KNOWN_REQ_KEYS = ['name', 'method', 'path', 'weight', 'body', 'headers', 'expectStatus', 'checks'];
const KNOWN_CHECK_KEYS = ['status', 'maxMs', 'notEmpty', 'bodyContains', 'jsonPath', 'jsonPathEquals'];
const KNOWN_LOAD_KEYS = ['vus', 'durationSec', 'rampUpSec', 'thinkTimeMs', 'maxRps', 'workers'];
const HTTP_METHODS = ['GET', 'HEAD', 'OPTIONS', 'POST', 'PUT', 'PATCH', 'DELETE'];
const WRITE_METHODS = ['POST', 'PUT', 'PATCH', 'DELETE'];

function loadScenario(file) {
  if (!existsSync(file)) die(1, `Файл сценария не найден: ${file}\nСоздайте его: node loadgen.mjs init --out ${file}`);
  let raw;
  try { raw = readFileSync(file, 'utf8'); } catch (e) { die(1, `Не удалось прочитать ${file}: ${e.message}`); }
  let scn;
  try { scn = JSON.parse(raw); } catch (e) {
    die(1, `Файл ${file} — не валидный JSON: ${e.message}\nЧастые причины: лишняя запятая после последнего элемента, комментарии //, одинарные кавычки.`);
  }
  if (scn && typeof scn === 'object' && !Array.isArray(scn)) scn._dir = dirname(file) || '.';
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

  // baseUrl
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

  // requests
  const varNames = Object.keys(scn.vars || {}).filter((k) => !k.startsWith('_'));
  if (!Array.isArray(scn.requests) || !scn.requests.length) {
    push(errors, 'Нужен непустой массив "requests". Каждый элемент: { "name": "...", "method": "GET", "path": "/..." , "weight": 1 }');
  } else {
    scn.requests.forEach((r, i) => {
      const label = `requests[${i}]${r?.name ? ` ("${r.name}")` : ''}`;
      if (typeof r !== 'object' || r === null) { push(errors, `${label}: должен быть объектом`); return; }
      for (const k of Object.keys(r)) {
        if (k.startsWith('_')) continue;
        if (!KNOWN_REQ_KEYS.includes(k)) {
          const s = suggestKey(k, KNOWN_REQ_KEYS);
          push(warnings, `${label}: неизвестный ключ "${k}"${s ? ` — возможно, "${s}"` : ''}`);
        }
      }
      if (!r.name) { r.name = `${r.method || 'GET'} ${r.path || `#${i}`}`; }
      r.method = String(r.method || 'GET').toUpperCase();
      if (!HTTP_METHODS.includes(r.method)) push(errors, `${label}: метод "${r.method}" не поддерживается (${HTTP_METHODS.join(', ')})`);
      if (!r.path) push(errors, `${label}: нет "path"`);
      else if (!/^\//.test(r.path) && !/^https?:\/\//.test(r.path)) push(errors, `${label}: path должен начинаться с "/" (сейчас: "${r.path}")`);
      if (r.weight === undefined) r.weight = 1;
      if (typeof r.weight !== 'number' || r.weight <= 0) push(errors, `${label}: weight должен быть положительным числом`);
      if (r.expectStatus !== undefined && (!Array.isArray(r.expectStatus) || !r.expectStatus.length || r.expectStatus.some((s) => !Number.isInteger(s)))) {
        push(errors, `${label}: expectStatus должен быть НЕПУСТЫМ массивом целых чисел, например [200, 404]`);
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
          if (String(r.method).toUpperCase() === 'HEAD' && ['notEmpty', 'bodyContains', 'jsonPath', 'jsonPathEquals'].some((k) => c[k] !== undefined)) {
            push(errors, `${label}: метод HEAD не возвращает тело — проверки notEmpty/bodyContains/jsonPath/jsonPathEquals невозможны, оставьте status/maxMs`);
          }
        }
      }
      // placeholders
      const used = [
        ...listPlaceholders(r.path || ''),
        ...(r.body !== undefined ? listPlaceholders(typeof r.body === 'string' ? r.body : JSON.stringify(r.body)) : []),
      ];
      for (const ph of used) {
        if (BUILTIN_PLACEHOLDERS.includes(ph) || /^randInt:-?\d+--?\d+$/.test(ph)) continue;
        if (!varNames.includes(ph)) {
          push(errors, `${label}: placeholder {{${ph}}} не объявлен в "vars". Объявлены: ${varNames.length ? varNames.join(', ') : '(ничего)'}. Встроенные: {{uuid}}, {{ts}}, {{randInt:A-B}}`);
        }
      }
    });
    // уникальность имён: статистика и checksSummary агрегируются по имени запроса
    const seenNames = new Map();
    scn.requests.forEach((r, i) => {
      if (!r || !r.name) return;
      if (seenNames.has(r.name)) {
        push(errors, `requests[${i}]: имя "${r.name}" уже используется в requests[${seenNames.get(r.name)}] — имена запросов должны быть уникальны (метрики считаются по имени). Задайте разные "name".`);
      } else seenNames.set(r.name, i);
    });
  }

  // writes
  const writeReqs = (scn.requests || []).filter((r) => r && WRITE_METHODS.includes(String(r.method || '').toUpperCase()));
  if (writeReqs.length && scn.allowWrites !== true) {
    push(errors,
      `Сценарий содержит изменяющие запросы (${writeReqs.map((r) => `"${r.name}"`).join(', ')}), но allowWrites не установлен в true.\n` +
      `  Это защита от случайной порчи данных. Если писать в систему ДЕЙСТВИТЕЛЬНО нужно и пользователь это явно разрешил:\n` +
      `  1) добавьте в сценарий "allowWrites": true;  2) запускайте с флагом --allow-writes.`);
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
    scn.load.vus = scn.load.vus ?? 5;
    scn.load.durationSec = scn.load.durationSec ?? 30;
    scn.load.rampUpSec = scn.load.rampUpSec ?? 0;
    if (!Number.isInteger(scn.load.vus) || scn.load.vus < 1) push(errors, `load.vus должен быть целым >= 1, сейчас: ${JSON.stringify(scn.load.vus)}`);
    if (scn.load.vus > MAX_VUS) push(errors, `load.vus=${scn.load.vus} превышает жёсткий лимит ${MAX_VUS} (защита от случайного DoS)`);
    if (typeof scn.load.durationSec !== 'number' || scn.load.durationSec < 1) push(errors, `load.durationSec должен быть числом >= 1`);
    if (scn.load.durationSec > MAX_DURATION_SEC) push(errors, `load.durationSec=${scn.load.durationSec} превышает жёсткий лимит ${MAX_DURATION_SEC} сек`);
    if (typeof scn.load.rampUpSec !== 'number' || scn.load.rampUpSec < 0) push(errors, `load.rampUpSec должен быть числом >= 0`);
    let tt = scn.load.thinkTimeMs ?? 0;
    if (typeof tt === 'number') tt = [tt, tt];
    if (!Array.isArray(tt) || tt.length !== 2 || tt.some((x) => typeof x !== 'number' || x < 0) || tt[0] > tt[1]) {
      push(errors, 'load.thinkTimeMs должен быть числом или парой [minМс, maxМс], min <= max');
    } else scn.load.thinkTimeMs = tt;
    if (scn.load.maxRps !== undefined && (typeof scn.load.maxRps !== 'number' || scn.load.maxRps < 1)) push(errors, 'load.maxRps должен быть числом >= 1');
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
        try { v = extractPath(json, c.jsonPathEquals.path); } catch { v = undefined; }
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

async function callOnce(scn, req, vars, token) {
  let url, body;
  const used = {};
  const headers = { ...(scn.headers || {}), ...(req.headers || {}) };
  if (token) headers['Authorization'] = `Bearer ${token}`;
  try {
    url = new URL(renderTemplate(req.path, vars, used), scn.baseUrl).toString();
    if (req.body !== undefined) {
      body = renderTemplate(typeof req.body === 'string' ? req.body : JSON.stringify(req.body), vars, used);
      if (!Object.keys(headers).some((h) => h.toLowerCase() === 'content-type')) headers['Content-Type'] = 'application/json';
    }
  } catch (e) {
    return { ms: 0, status: 0, ok: false, kind: 'config', errMsg: e.message, used };
  }
  const t0 = performance.now();
  try {
    const res = await fetch(url, { method: req.method, headers, body, signal: AbortSignal.timeout(scn.timeoutMs) });
    const text = req.method === 'HEAD' ? '' : await res.text();
    return evaluateResponse(req, res.status, text, performance.now() - t0, used);
  } catch (e) {
    return { ms: performance.now() - t0, status: 0, ok: false, kind: errKind(e), errMsg: errText(e), used };
  }
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

/** Гоняет vuCount виртуальных пользователей до endAt, записывая в stats. Используется и в главном потоке, и в воркере. */
async function runLoadSlice({ scn, preToken, stats, vuCount, vuOffset, totalVus, endAt, rampMs, effectiveMaxRps, ctx, loginFailures, onSample }) {
  const pick = makePicker(scn.requests);
  const rateGate = makeRateGate(effectiveMaxRps);
  const [ttMin, ttMax] = scn.load.thinkTimeMs;
  const runVU = async (localIdx) => {
    const globalIdx = vuOffset + localIdx;
    if (rampMs) await sleep((rampMs * globalIdx) / Math.max(1, totalVus));
    // все VU используют общий токен из pre-flight: логин выполняется ОДИН раз, без шторма
    // на старте (иначе rampUp:0 + сотни VU = сотни одновременных POST /auth/login).
    const token = preToken;
    void loginFailures;
    while (Date.now() < endAt && !ctx.aborted) {
      if (rateGate) await rateGate();
      const req = pick();
      const r = await callOnce(scn, req, scn._resolvedVars, token);
      stats.record(req, r, Date.now());
      if (onSample && r.ok) onSample(r.ms);
      if (ttMax > 0) await sleep(ttMin + Math.random() * (ttMax - ttMin));
    }
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
  return { total: stats.total, errors: stats.errors, startedAt: stats.startedAt, endedAt: stats.endedAt, per };
}

function mergeStats(parts, requests) {
  const base = makeStats(requests);
  base.startedAt = Math.min(...parts.map((p) => p.startedAt));
  base.endedAt = Math.max(...parts.map((p) => p.endedAt));
  for (const part of parts) {
    base.total += part.total;
    base.errors += part.errors;
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
  }
  return base;
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

function makeStats(requests) {
  const per = new Map();
  for (const r of requests) per.set(r.name, { lat: [], count: 0, errors: 0, slow: 0, statuses: new Map(), errSamples: new Map(), perVar: new Map() });
  return {
    per,
    startedAt: 0,
    endedAt: 0,
    total: 0,
    errors: 0,
    record(req, r, now) {
      const s = this.per.get(req.name);
      this.total++;
      s.count++;
      const key = r.kind === 'check' ? `${r.status}✗check` : (r.status || r.kind || 'err');
      s.statuses.set(key, (s.statuses.get(key) || 0) + 1);
      if (r.ok) {
        if (s.lat.length < MAX_LAT_SAMPLES) s.lat.push([now, r.ms]); else s.latDropped = (s.latDropped || 0) + 1;
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
  const durSec = Math.max(0.001, (stats.endedAt - stats.startedAt) / 1000);
  const allLat = [];
  const rows = [];
  const errorsDetail = [];
  let latDroppedTotal = 0;
  for (const [name, s] of stats.per) {
    const lat = s.lat.map(([, ms]) => ms).sort((a, b) => a - b);
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
      statuses,
    });
    for (const [ek, sample] of s.errSamples) errorsDetail.push({ request: name, error: ek, sample });
  }
  const latSorted = allLat.map(([, ms]) => ms).sort((a, b) => a - b);
  const totalErrPct = stats.total ? (100 * stats.errors) / stats.total : 0;

  // деградация: p95 первой половины vs второй
  let degradation = null;
  if (allLat.length > 100) {
    const mid = stats.startedAt + (stats.endedAt - stats.startedAt) / 2;
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
    const req = (scn.requests || []).find((r) => r.name === name);
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
  const checks = opts.smoke ? [] : [
    { name: `p95 ${fmtMs(p95)}ms <= ${th.p95Ms}ms`, pass: p95 <= th.p95Ms },
    { name: `errors ${totalErrPct.toFixed(2)}% <= ${th.errorRatePct}%`, pass: totalErrPct <= th.errorRatePct },
  ];
  const incompleteWorkers = opts.incompleteWorkers || 0;
  const pass = checks.every((c) => c.pass) && (!opts.smoke || stats.errors === 0) && incompleteWorkers === 0;
  if (incompleteWorkers > 0) checks.push({ name: `все потоки-генераторы вернули данные (не вернули: ${incompleteWorkers})`, pass: false });

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

  if (incompleteWorkers > 0) hints.push(`⚠ ${incompleteWorkers} поток(ов)-генератор(ов) упали и не вернули данные — их доля нагрузки НЕ выполнена. Отчёт неполный, вердикт принудительно FAIL. Проверьте память/стабильность и повторите.`);

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

  return {
    tool: 'loadgen', version: VERSION,
    scenario: scn.name || '(без имени)', baseUrl: scn.baseUrl,
    mode: opts.smoke ? 'smoke' : 'load',
    startedAt: new Date(stats.startedAt).toISOString(),
    durationSec: Number(durSec.toFixed(1)),
    vus: opts.smoke ? 1 : scn.load.vus,
    total: stats.total, rps: Number((stats.total / durSec).toFixed(1)),
    errors: stats.errors, errorRatePct: Number(totalErrPct.toFixed(2)),
    latencyMs: { p50: pct(latSorted, 50), p90: pct(latSorted, 90), p95, p99: pct(latSorted, 99), max: latSorted.length ? latSorted[latSorted.length - 1] : 0 },
    perRequest: rows.map((r) => ({ ...r, rps: Number(r.rps.toFixed(1)), errPct: Number(r.errPct.toFixed(2)), p50: Math.round(r.p50), p90: Math.round(r.p90), p95: Math.round(r.p95), p99: Math.round(r.p99), max: Math.round(r.max) })),
    workers, resource: res, incompleteWorkers,
    errorsDetail, degradation, checksSummary, paramImpact, checks, verdict: pass ? 'PASS' : 'FAIL', hints,
    loginFailures: opts.loginFailures || [],
  };
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
  L('');
  L('==================== ИТОГ ====================');
  L(`VERDICT: ${rep.verdict}`);
  L(`Сценарий: ${rep.scenario} | Режим: ${rep.mode} | Цель: ${rep.baseUrl}`);
  L(`Запросов: ${rep.total} за ${rep.durationSec}с (RPS ${rep.rps}, VUs ${rep.vus}) | Ошибок: ${rep.errors} (${rep.errorRatePct}%)`);
  const lm = rep.latencyMs;
  L(`Латентность мс: p50 ${fmtMs(lm.p50)} | p90 ${fmtMs(lm.p90)} | p95 ${fmtMs(lm.p95)} | p99 ${fmtMs(lm.p99)} | max ${fmtMs(lm.max)}`);
  for (const c of rep.checks) L(`Порог: ${c.name} ${c.pass ? 'OK' : 'НАРУШЕН'}`);
  L('==============================================');
  if (rep.hints.length) {
    L('ПОДСКАЗКИ:');
    for (const h of rep.hints) L(`  • ${h}`);
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

function cmdInit(flags) {
  const out = flags.out || 'scenario.json';
  if (existsSync(out) && !flags.force) die(1, `Файл ${out} уже существует. Используйте --force для перезаписи или другое имя через --out.`);
  const template = {
    _comment: 'Шаблон сценария loadgen. Ключи с _ игнорируются. Удалите ненужные блоки.',
    name: 'my-load-test',
    baseUrl: 'http://localhost:8080',
    _baseUrl_hint: 'Только схема://хост:порт, без пути.',
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
    load: { vus: 5, durationSec: 30, rampUpSec: 5, thinkTimeMs: [100, 300], workers: 1 },
    _load_hint: `vus — параллельные пользователи (max ${MAX_VUS}), durationSec — длительность (max ${MAX_DURATION_SEC}), maxRps — глобальный потолок запросов/сек (опц.), workers — число потоков-генераторов на разные ядра (по умолч. 1; поднимайте, если генератор упирается в CPU)`,
    thresholds: { p95Ms: 1000, errorRatePct: 1 },
    _thresholds_hint: 'Пороги для вердикта PASS/FAIL',
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
  const writes = scn.requests.filter((r) => WRITE_METHODS.includes(r.method)).length;
  console.log(`OK: сценарий валиден. Запросов: ${scn.requests.length} (изменяющих: ${writes}), VUs: ${scn.load.vus}, длительность: ${scn.load.durationSec}с, цель: ${scn.baseUrl}`);
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

async function cmdRun(positional, flags) {
  const file = positional[0];
  if (!file) die(1, 'Использование: node loadgen.mjs run <scenario.json> [--smoke] [--vus N] [--duration N] [--out FILE]');
  const scn = loadScenario(file);

  // переопределения CLI
  if (flags['base-url']) scn.baseUrl = flags['base-url'];
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

  // защита: запись
  const hasWrites = scn.requests.some((r) => WRITE_METHODS.includes(r.method));
  if (hasWrites && !flags['allow-writes']) {
    die(1, 'Сценарий содержит изменяющие запросы (allowWrites:true задан), но для запуска нужен ещё явный флаг --allow-writes.\nЭто двойная защита: убедитесь, что пользователь явно разрешил запись в целевую систему.');
  }
  // защита: внешний хост
  const host = new URL(scn.baseUrl).hostname;
  if (!isPrivateHost(host) && !flags['confirm-external']) {
    die(1, `Цель ${scn.baseUrl} — внешний хост (не localhost/приватная сеть).\n` +
      'Нагрузка на чужие системы без разрешения недопустима. Если это ВАШ сервер и тест согласован — добавьте флаг --confirm-external.');
  }

  const smoke = !!flags.smoke;
  console.log(`loadgen v${VERSION} | сценарий "${scn.name || '(без имени)'}" | цель ${scn.baseUrl} | режим ${smoke ? 'SMOKE (по 1 запросу)' : `LOAD (${scn.load.vus} VUs, ${scn.load.durationSec}с)`}`);

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

  let stats = makeStats(scn.requests);
  const loginFailures = [];
  let resource = null;
  let incompleteWorkers = 0;

  if (smoke) {
    stats.startedAt = Date.now();
    console.log('');
    for (const req of scn.requests) {
      const r = await callOnce(scn, req, scn._resolvedVars, preToken);
      stats.record(req, r, Date.now());
      const line = r.ok
        ? `  [OK]   ${req.name}: ${req.method} → HTTP ${r.status} (${fmtMs(r.ms)}ms)`
        : `  [FAIL] ${req.name}: ${req.method} → ${r.status ? `HTTP ${r.status}` : r.kind} (${fmtMs(r.ms)}ms) ${r.errMsg || r.snippet || ''}`.trimEnd();
      console.log(line);
    }
    stats.endedAt = Date.now();
  } else if (scn.load.workers > 1) {
    // ─── многопоточный режим: worker_threads на несколько ядер ──
    const workers = Math.min(scn.load.workers, scn.load.vus);
    console.log(`Потоков-генераторов: ${workers} (ядер доступно: ${os.cpus().length})`);
    const resMon = startResourceMonitor({ watchEventLoop: false });
    stats.startedAt = Date.now();
    const endAt = stats.startedAt + scn.load.durationSec * 1000;
    const rampMs = scn.load.rampUpSec * 1000;
    const scnJson = JSON.stringify(scn);

    // распределяем VU по воркерам
    const base = Math.floor(scn.load.vus / workers);
    const rem = scn.load.vus % workers;
    const parts = [];
    const workerLoginFailures = [];
    const workerErrors = [];
    let elLagMaxMs = 0;
    const ticks = new Array(workers).fill(null).map(() => ({ total: 0, errors: 0 }));
    const workerObjs = [];
    let offset = 0;

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
        const vuCount = base + (w < rem ? 1 : 0);
        const vuOffset = offset;
        offset += vuCount;
        const worker = new Worker(fileURLToPath(import.meta.url), {
          workerData: {
            role: 'load-slice', scnJson, preToken, vuCount, vuOffset,
            totalVus: scn.load.vus, endAt, rampMs,
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

    if (!parts.length) { console.log('Ни один поток не вернул результат — прогон не удался.'); process.exit(2); }
    // частичный крах: часть нагрузки не выполнена — нельзя выдавать это за валидный результат
    if (parts.length < workers) {
      incompleteWorkers = workers - parts.length;
      console.log(`⚠ ВНИМАНИЕ: ${incompleteWorkers} из ${workers} потоков не вернули данные (${workerErrors.join('; ') || 'причина неизвестна'}). Результат НЕПОЛНЫЙ.`);
    }
    stats = mergeStats(parts, scn.requests);
    // stats.endedAt уже выставлен mergeStats = max(endedAt воркеров) — НЕ перетираем его пост-фактум
    loginFailures.push(...workerLoginFailures);
    resource = resMon.finish(elLagMaxMs);
  } else {
    // ─── однопоточный режим ──
    const resMon = startResourceMonitor({ watchEventLoop: true });
    const ctx = { aborted: false };
    process.on('SIGINT', () => { ctx.aborted = true; console.log('\nПрерывание — формирую отчёт по собранным данным...'); });

    stats.startedAt = Date.now();
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
      console.log(`  t=${Math.round((now - stats.startedAt) / 1000)}с всего=${stats.total} rps=${rps.toFixed(0)} err=${errPct}% p95(окно)=${fmtMs(pct(sorted, 95))}ms`);
    }, 5000);

    await runLoadSlice({
      scn, preToken, stats, vuCount: scn.load.vus, vuOffset: 0, totalVus: scn.load.vus,
      endAt, rampMs, effectiveMaxRps: scn.load.maxRps || 0, ctx, loginFailures,
      onSample: (ms) => winLat.push(ms),
    });
    stats.endedAt = Date.now();
    if (progress) clearInterval(progress);
    resource = resMon.finish();
  }

  const rep = buildReport(scn, stats, { smoke, loginFailures, resource, incompleteWorkers });
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
  process.exit(rep.verdict === 'PASS' ? 0 : 2);
}

// ─────────────────────────────────────────────── worker-режим ──

/** Точка входа воркера: гоняет свою долю VU и отсылает статистику в главный поток. */
async function runWorkerSlice() {
  const { scnJson, preToken, vuCount, vuOffset, totalVus, endAt, rampMs, effectiveMaxRps } = workerData;
  const scn = JSON.parse(scnJson);
  const stats = makeStats(scn.requests);
  const loginFailures = [];
  const ctx = { aborted: false };
  parentPort.on('message', (m) => { if (m === 'abort') ctx.aborted = true; });
  const elMon = monitorEventLoopDelay({ resolution: 20 });
  elMon.enable();
  const tick = setInterval(() => parentPort.postMessage({ type: 'tick', total: stats.total, errors: stats.errors }), 2000);
  if (tick.unref) tick.unref();
  stats.startedAt = Date.now();
  await runLoadSlice({ scn, preToken, stats, vuCount, vuOffset, totalVus, endAt, rampMs, effectiveMaxRps, ctx, loginFailures });
  stats.endedAt = Date.now();
  clearInterval(tick);
  elMon.disable();
  parentPort.postMessage({ type: 'result', stats: serializeStats(stats), loginFailures, elLagMaxMs: elMon.max / 1e6 });
  parentPort.close(); // снимаем listener, иначе воркер не завершится
}

// ─────────────────────────────────────────────── main ──

if (!isMainThread && workerData && workerData.role === 'load-slice') {
  await runWorkerSlice();
} else {
  await runCli();
}

async function runCli() {
const { cmd, positional, flags } = parseArgs(process.argv.slice(2));

const HELP = `loadgen v${VERSION} — REST load generator (Node >= 18, без зависимостей)

Команды:
  probe <baseUrl> [path ...]        проверить доступность цели (exit 0/3)
  init [--out FILE] [--force]        создать шаблон сценария
  validate <scenario.json>          проверить сценарий (exit 0/1)
  profile <log|csv|json>            построить черновик сценария из статистики N запросов
    --format access|csv|json         формат входа (по умолчанию — автоопределение)
    --base-url URL                   цель для сценария
    --top N                          сколько самых частых эндпоинтов взять (по умолч. 20)
    --include-writes                 включить POST/PUT/DELETE (по умолчанию только чтение)
    --out FILE                       куда записать черновик сценария
  run <scenario.json>               прогнать нагрузку (exit 0=PASS, 2=FAIL, 3=цель недоступна)
    --smoke                          каждый запрос по 1 разу, последовательно (проверка конфига)
    --vus N --duration N             переопределить нагрузку
    --workers N                      число потоков-генераторов (по умолч. из сценария/1)
    --base-url URL                   переопределить цель
    --out FILE                       файл JSON-результата (по умолч. loadgen-result.json)
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
  case 'run': await cmdRun(positional, flags); break;
  case undefined:
  case 'help':
  case '--help':
    console.log(HELP); break;
  default:
    die(1, `Неизвестная команда "${cmd}".\n\n${HELP}`);
}
}
