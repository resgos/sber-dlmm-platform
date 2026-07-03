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
import { dirname } from 'node:path';
import { randomUUID } from 'node:crypto';
import { performance } from 'node:perf_hooks';

const VERSION = '1.0.0';
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

function renderTemplate(str, vars) {
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
      const v = vars[expr];
      return String(Array.isArray(v) ? v[Math.floor(Math.random() * v.length)] : v);
    }
    throw new Error(`неизвестный placeholder {{${expr}}}`);
  });
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
      const needsValue = ['out', 'vus', 'duration', 'base-url', 'max-rps'].includes(name);
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
const KNOWN_REQ_KEYS = ['name', 'method', 'path', 'weight', 'body', 'headers', 'expectStatus'];
const KNOWN_LOAD_KEYS = ['vus', 'durationSec', 'rampUpSec', 'thinkTimeMs', 'maxRps'];
const HTTP_METHODS = ['GET', 'HEAD', 'OPTIONS', 'POST', 'PUT', 'PATCH', 'DELETE'];
const WRITE_METHODS = ['POST', 'PUT', 'PATCH', 'DELETE'];

function loadScenario(file) {
  if (!existsSync(file)) die(1, `Файл сценария не найден: ${file}\nСоздайте его: node loadgen.mjs init --out ${file}`);
  let raw;
  try { raw = readFileSync(file, 'utf8'); } catch (e) { die(1, `Не удалось прочитать ${file}: ${e.message}`); }
  try { return JSON.parse(raw); } catch (e) {
    die(1, `Файл ${file} — не валидный JSON: ${e.message}\nЧастые причины: лишняя запятая после последнего элемента, комментарии //, одинарные кавычки.`);
  }
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
    if (typeof scn.vars !== 'object' || Array.isArray(scn.vars)) push(errors, '"vars" должен быть объектом: { "имя": [значения] | { "setupPath": "...", "extract": "..." } }');
    else {
      for (const [name, def] of Object.entries(scn.vars)) {
        if (Array.isArray(def)) {
          if (!def.length) push(errors, `vars.${name}: пустой список значений`);
        } else if (typeof def === 'object' && def !== null) {
          if (!def.setupPath) push(errors, `vars.${name}: нет setupPath (GET-запрос, из которого берём значения)`);
          if (!def.extract) push(errors, `vars.${name}: нет extract (путь до значений в JSON, например "content[*].id")`);
        } else {
          push(errors, `vars.${name}: должен быть массивом значений или объектом { setupPath, extract }`);
        }
      }
    }
  }

  // requests
  const varNames = Object.keys(scn.vars || {});
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
      if (r.expectStatus !== undefined && (!Array.isArray(r.expectStatus) || r.expectStatus.some((s) => !Number.isInteger(s)))) {
        push(errors, `${label}: expectStatus должен быть массивом целых чисел, например [200, 404]`);
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
    if (Array.isArray(def)) { out[name] = def; continue; }
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

async function callOnce(scn, req, vars, token) {
  let url, body;
  const headers = { ...(scn.headers || {}), ...(req.headers || {}) };
  if (token) headers['Authorization'] = `Bearer ${token}`;
  try {
    url = new URL(renderTemplate(req.path, vars), scn.baseUrl).toString();
    if (req.body !== undefined) {
      body = renderTemplate(typeof req.body === 'string' ? req.body : JSON.stringify(req.body), vars);
      if (!Object.keys(headers).some((h) => h.toLowerCase() === 'content-type')) headers['Content-Type'] = 'application/json';
    }
  } catch (e) {
    return { ms: 0, status: 0, ok: false, kind: 'config', errMsg: e.message };
  }
  const t0 = performance.now();
  try {
    const res = await fetch(url, { method: req.method, headers, body, signal: AbortSignal.timeout(scn.timeoutMs) });
    const text = req.method === 'HEAD' ? '' : await res.text();
    const ms = performance.now() - t0;
    const ok = req.expectStatus ? req.expectStatus.includes(res.status) : res.status >= 200 && res.status < 400;
    return { ms, status: res.status, ok, snippet: ok ? undefined : text.slice(0, 250) };
  } catch (e) {
    return { ms: performance.now() - t0, status: 0, ok: false, kind: errKind(e), errMsg: errText(e) };
  }
}

// ─────────────────────────────────────────────── статистика и отчёт ──

function makeStats(requests) {
  const per = new Map();
  for (const r of requests) per.set(r.name, { lat: [], count: 0, errors: 0, statuses: new Map(), errSamples: new Map() });
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
      const key = r.status || (r.kind || 'err');
      s.statuses.set(key, (s.statuses.get(key) || 0) + 1);
      if (r.ok) {
        s.lat.push([now, r.ms]);
      } else {
        this.errors++;
        s.errors++;
        const ek = `${r.status || r.kind}`;
        if (!s.errSamples.has(ek)) s.errSamples.set(ek, (r.errMsg || r.snippet || '').slice(0, 200));
      }
    },
  };
}

function buildReport(scn, stats, opts = {}) {
  const durSec = Math.max(0.001, (stats.endedAt - stats.startedAt) / 1000);
  const allLat = [];
  const rows = [];
  const errorsDetail = [];
  for (const [name, s] of stats.per) {
    const lat = s.lat.map(([, ms]) => ms).sort((a, b) => a - b);
    allLat.push(...s.lat);
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

  const th = scn.thresholds;
  const p95 = pct(latSorted, 95);
  const checks = opts.smoke ? [] : [
    { name: `p95 ${fmtMs(p95)}ms <= ${th.p95Ms}ms`, pass: p95 <= th.p95Ms },
    { name: `errors ${totalErrPct.toFixed(2)}% <= ${th.errorRatePct}%`, pass: totalErrPct <= th.errorRatePct },
  ];
  const pass = checks.every((c) => c.pass) && (!opts.smoke || stats.errors === 0);

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
  if (!opts.smoke && pass && totalErrPct === 0 && p95 < th.p95Ms * 0.3 && stats.total > 50) {
    hints.push(`Система легко держит эту нагрузку (p95 ${fmtMs(p95)}ms при пороге ${th.p95Ms}ms). Чтобы найти предел — повышайте нагрузку: --vus ${Math.min(MAX_VUS, scn.load.vus * 2)}.`);
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
    errorsDetail, degradation, checks, verdict: pass ? 'PASS' : 'FAIL', hints,
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
  if (rep.loginFailures.length) {
    L('');
    L(`  ЛОГИН НЕ УДАЛСЯ у ${rep.loginFailures.length} VU: ${rep.loginFailures[0]}`);
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
      _hint: 'Переменные для {{placeholder}}. Либо статический список, либо {setupPath, extract} — значения возьмутся из GET-запроса перед стартом.',
      exampleId: { setupPath: '/api/v1/items?page=0&size=20', extract: 'content[*].id' },
    },
    requests: [
      { name: 'list items', method: 'GET', path: '/api/v1/items?page=0&size=20', weight: 5 },
      { name: 'item detail', method: 'GET', path: '/api/v1/items/{{exampleId}}', weight: 3 },
    ],
    _requests_hint: 'weight — относительная частота. Встроенные placeholders: {{uuid}}, {{ts}}, {{randInt:1-100}}. Для POST добавьте body и allowWrites:true.',
    load: { vus: 5, durationSec: 30, rampUpSec: 5, thinkTimeMs: [100, 300] },
    _load_hint: `vus — параллельные пользователи (max ${MAX_VUS}), durationSec — длительность (max ${MAX_DURATION_SEC}), maxRps — глобальный потолок запросов/сек (опционально)`,
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

async function cmdRun(positional, flags) {
  const file = positional[0];
  if (!file) die(1, 'Использование: node loadgen.mjs run <scenario.json> [--smoke] [--vus N] [--duration N] [--out FILE]');
  const scn = loadScenario(file);

  // переопределения CLI
  if (flags['base-url']) scn.baseUrl = flags['base-url'];
  if (flags.vus) { scn.load = scn.load || {}; scn.load.vus = Number(flags.vus); }
  if (flags.duration) { scn.load = scn.load || {}; scn.load.durationSec = Number(flags.duration); }
  if (flags['max-rps']) { scn.load = scn.load || {}; scn.load.maxRps = Number(flags['max-rps']); }

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
  console.log(`loadgen v${VERSION} | сценарий "${scn.name}" | цель ${scn.baseUrl} | режим ${smoke ? 'SMOKE (по 1 запросу)' : `LOAD (${scn.load.vus} VUs, ${scn.load.durationSec}с)`}`);

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

  const stats = makeStats(scn.requests);
  const loginFailures = [];

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
  } else {
    // весовая рулетка
    const cum = [];
    let acc = 0;
    for (const r of scn.requests) { acc += r.weight; cum.push([acc, r]); }
    const pick = () => {
      const x = Math.random() * acc;
      for (const [c, r] of cum) if (x < c) return r;
      return cum[cum.length - 1][1];
    };

    // глобальный rate limiter (окно 100мс)
    let winStart = 0, winCount = 0;
    const perWin = scn.load.maxRps ? scn.load.maxRps / 10 : Infinity;
    const rateGate = async () => {
      for (;;) {
        const now = Date.now();
        if (now - winStart >= 100) { winStart = now; winCount = 0; }
        if (winCount < perWin) { winCount++; return; }
        await sleep(10);
      }
    };

    const ctx = { aborted: false };
    process.on('SIGINT', () => { ctx.aborted = true; console.log('\nПрерывание — формирую отчёт по собранным данным...'); });

    stats.startedAt = Date.now();
    const endAt = stats.startedAt + scn.load.durationSec * 1000;
    const rampMs = scn.load.rampUpSec * 1000;
    const [ttMin, ttMax] = scn.load.thinkTimeMs;

    // прогресс каждые 5с
    let lastTotal = 0;
    const winLat = [];
    stats._winLat = winLat;
    const progress = flags.quiet ? null : setInterval(() => {
      const now = Date.now();
      const rps = (stats.total - lastTotal) / 5;
      lastTotal = stats.total;
      const sorted = winLat.splice(0).sort((a, b) => a - b);
      const errPct = stats.total ? ((100 * stats.errors) / stats.total).toFixed(1) : '0.0';
      console.log(`  t=${Math.round((now - stats.startedAt) / 1000)}с всего=${stats.total} rps=${rps.toFixed(0)} err=${errPct}% p95(окно)=${fmtMs(pct(sorted, 95))}ms`);
    }, 5000);

    const runVU = async (idx) => {
      if (rampMs) await sleep((rampMs * idx) / scn.load.vus);
      let token = preToken;
      if (scn.auth.type === 'login' && idx > 0) {
        try { token = await doLogin(scn); } catch (e) { loginFailures.push(e.message); return; }
      }
      while (Date.now() < endAt && !ctx.aborted) {
        if (scn.load.maxRps) await rateGate();
        const req = pick();
        const r = await callOnce(scn, req, scn._resolvedVars, token);
        const now = Date.now();
        stats.record(req, r, now);
        if (r.ok) winLat.push(r.ms);
        if (ttMax > 0) await sleep(ttMin + Math.random() * (ttMax - ttMin));
      }
    };

    await Promise.all(Array.from({ length: scn.load.vus }, (_, i) => runVU(i)));
    stats.endedAt = Date.now();
    if (progress) clearInterval(progress);
  }

  const rep = buildReport(scn, stats, { smoke, loginFailures });
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

// ─────────────────────────────────────────────── main ──

const { cmd, positional, flags } = parseArgs(process.argv.slice(2));

const HELP = `loadgen v${VERSION} — REST load generator (Node >= 18, без зависимостей)

Команды:
  probe <baseUrl> [path ...]   проверить доступность цели (exit 0/3)
  init [--out FILE] [--force]  создать шаблон сценария
  validate <scenario.json>     проверить сценарий (exit 0/1)
  run <scenario.json>          прогнать нагрузку (exit 0=PASS, 2=FAIL, 3=цель недоступна)
    --smoke                    каждый запрос по 1 разу, последовательно (проверка конфига)
    --vus N --duration N       переопределить нагрузку
    --base-url URL             переопределить цель
    --out FILE                 файл JSON-результата (по умолч. loadgen-result.json)
    --allow-writes             подтвердить изменяющие запросы
    --confirm-external         подтвердить нагрузку на внешний хост
    --quiet                    без прогресса каждые 5с

Рекомендуемый порядок: probe → init → validate → run --smoke → run`;

switch (cmd) {
  case 'probe': await cmdProbe(positional); break;
  case 'init': cmdInit(flags); break;
  case 'validate': cmdValidate(positional); break;
  case 'run': await cmdRun(positional, flags); break;
  case undefined:
  case 'help':
  case '--help':
    console.log(HELP); break;
  default:
    die(1, `Неизвестная команда "${cmd}".\n\n${HELP}`);
}
