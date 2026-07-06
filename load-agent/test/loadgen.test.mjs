/**
 * Регрессионные тесты ядра loadgen. Запуск: node --test  (из папки load-agent)
 * Только чистые функции — без сети. Ловят регрессии в парсинге, проверках, захвате и валидации.
 */
import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  extractPath, renderTemplate, parseCsv, normalizePath, isIdSegment,
  evaluateResponse, applyCaptures, validateScenario, makeStats, mergeStats, serializeStats, compareResults, stageTargetAt,
  toJUnitXml, toMarkdown, parseMemMB, summarizeTargetMetrics, resolveEnvInScenario, encodeForm, buildMultipart,
  histogram, percentileFromHistogram, mergeResults, toHtml, asciiHistogram,
  resolveSqlDriver, parseSqlChunk, checkAssertExpect, parseKafkaLag,
} from '../bin/loadgen.mjs';

// ─── extractPath ──
test('extractPath: dot + array index + fan', () => {
  const j = { data: { items: [{ id: 1 }, { id: 2 }] }, content: [{ id: 'a' }, { id: 'b' }] };
  assert.equal(extractPath(j, 'data.items[0].id'), 1);
  assert.deepEqual(extractPath(j, 'content[*].id'), ['a', 'b']);
  assert.equal(extractPath(j, 'nope.deep'), undefined);
});

// ─── renderTemplate ──
test('renderTemplate: один и тот же {{var}} даёт одно значение в пределах вызова', () => {
  const used = {};
  const out = renderTemplate('/a/{{id}}/b/{{id}}', { id: ['x', 'y', 'z'] }, used);
  const p = out.split('/'); // ['', 'a', <val>, 'b', <val>]
  assert.equal(p[2], p[4], 'оба вхождения {{id}} должны совпасть');
  assert.equal(used.id, p[2]);
  assert.ok(['x', 'y', 'z'].includes(p[2]));
});
test('renderTemplate: builtins и randInt', () => {
  assert.match(renderTemplate('{{uuid}}', {}), /^[0-9a-f-]{36}$/);
  const n = Number(renderTemplate('{{randInt:5-5}}', {}));
  assert.equal(n, 5);
});
test('renderTemplate: неизвестный placeholder бросает', () => {
  assert.throws(() => renderTemplate('{{nope}}', {}), /nope/);
});

// ─── parseCsv (RFC 4180) ──
test('parseCsv: кавычки, запятые и переводы строк в поле', () => {
  const rows = parseCsv('id,desc\n1,"a,b"\n2,"line1\nline2"\n');
  assert.deepEqual(rows[0], ['id', 'desc']);
  assert.deepEqual(rows[1], ['1', 'a,b']);
  assert.deepEqual(rows[2], ['2', 'line1\nline2']);
});
test('parseCsv: незакрытая кавычка бросает', () => {
  assert.throws(() => parseCsv('a\n"oops'), /кавычк/);
});

// ─── normalizePath / isIdSegment ──
test('isIdSegment: числа, uuid, длинный hex', () => {
  assert.equal(isIdSegment('123'), true);
  assert.equal(isIdSegment('550e8400-e29b-41d4-a716-446655440000'), true);
  assert.equal(isIdSegment('deadbeefdeadbeef'), true);
  assert.equal(isIdSegment('pools'), false);
});
test('normalizePath: ID → {{pN}} + сбор значений', () => {
  const { template, values } = normalizePath('/api/v1/pools/42/bins?from=1');
  assert.equal(template, '/api/v1/pools/{{p1}}/bins?from=1');
  assert.deepEqual(values, [['42']]);
});

// ─── evaluateResponse (checks) ──
test('evaluateResponse: статус вне ожидаемого = ошибка', () => {
  const r = evaluateResponse({ checks: { status: [200] } }, 500, '{}', 10, {});
  assert.equal(r.ok, false);
});
test('evaluateResponse: jsonPath на null-теле не даёт ложный PASS', () => {
  const r = evaluateResponse({ checks: { jsonPath: 'content[*].id' } }, 200, 'null', 5, {});
  assert.equal(r.ok, false);
});
test('evaluateResponse: jsonPathEquals с [*] требует ВСЕ значения', () => {
  const body = JSON.stringify({ items: [{ s: 'A' }, { s: 'B' }] });
  const bad = evaluateResponse({ checks: { jsonPathEquals: { path: 'items[*].s', value: 'A' } } }, 200, body, 5, {});
  assert.equal(bad.ok, false);
  const good = evaluateResponse({ checks: { jsonPathEquals: { path: 'items[*].s', value: 'A' } } }, 200, JSON.stringify({ items: [{ s: 'A' }, { s: 'A' }] }), 5, {});
  assert.equal(good.ok, true);
});
test('evaluateResponse: jsonPathEquals [*] — битый элемент (null/без поля) ПРОВАЛивает, не теряется (фикс ревью)', () => {
  const chk = { checks: { jsonPathEquals: { path: 'orders[*].status', value: 'ACTIVE' } } };
  // средний элемент null — раньше молча выкидывался и давал ложный PASS
  const withNull = evaluateResponse(chk, 200, JSON.stringify({ orders: [{ status: 'ACTIVE' }, { status: null }, { status: 'ACTIVE' }] }), 5, {});
  assert.equal(withNull.ok, false, 'элемент со status:null должен провалить "все = ACTIVE"');
  // средний элемент без поля status вовсе
  const missing = evaluateResponse(chk, 200, JSON.stringify({ orders: [{ status: 'ACTIVE' }, {}, { status: 'ACTIVE' }] }), 5, {});
  assert.equal(missing.ok, false, 'элемент без поля status должен провалить проверку');
});
test('evaluateResponse: maxMs помечает slow, но ok', () => {
  const r = evaluateResponse({ checks: { status: [200], maxMs: 5 } }, 200, '{}', 50, {});
  assert.equal(r.ok, true);
  assert.equal(r.slow, true);
});

// ─── applyCaptures ──
test('applyCaptures: связывает переменные из ответа', () => {
  const vars = {};
  const r = applyCaptures({ name: 's', capture: { poolId: 'content[0].id', bin: 'content[0].activeBinId' } },
    JSON.stringify({ content: [{ id: 'P1', activeBinId: 8388608 }] }), vars);
  assert.equal(r.ok, true);
  assert.equal(vars.poolId, 'P1');
  assert.equal(vars.bin, '8388608');
});
test('applyCaptures: отсутствующий путь = провал', () => {
  const r = applyCaptures({ name: 's', capture: { x: 'nope.deep' } }, '{}', {});
  assert.equal(r.ok, false);
  assert.match(r.error, /не дал скалярного/);
});
test('applyCaptures: не-JSON тело = провал', () => {
  const r = applyCaptures({ name: 's', capture: { x: 'id' } }, 'not json', {});
  assert.equal(r.ok, false);
});

// ─── validateScenario: flows ──
function baseFlowScn(steps) {
  return { baseUrl: 'http://localhost:8080', flows: [{ name: 'f', steps }] };
}
test('validateScenario: корректная цепочка проходит + нормализуется', () => {
  const scn = baseFlowScn([
    { name: 'a', method: 'GET', path: '/x', capture: { id: 'data.id' } },
    { name: 'b', method: 'GET', path: '/y/{{id}}' },
  ]);
  const { errors } = validateScenario(scn);
  assert.deepEqual(errors, []);
  assert.equal(scn._hasExplicitFlows, true);
  assert.equal(scn._steps.length, 2);
  assert.equal(scn._flows[0]._track, true);
});
test('validateScenario: forward-reference захвата = ошибка', () => {
  const scn = baseFlowScn([
    { name: 'a', method: 'GET', path: '/y/{{late}}' },
    { name: 'b', method: 'GET', path: '/x', capture: { late: 'id' } },
  ]);
  const { errors } = validateScenario(scn);
  assert.ok(errors.some((e) => /\{\{late\}\}/.test(e)), 'должна быть ошибка про недоступный {{late}}');
});
test('validateScenario: дубли имён шагов между flow = ошибка', () => {
  const scn = {
    baseUrl: 'http://localhost:8080',
    flows: [
      { name: 'f1', steps: [{ name: 'dup', method: 'GET', path: '/a' }] },
      { name: 'f2', steps: [{ name: 'dup', method: 'GET', path: '/b' }] },
    ],
  };
  const { errors } = validateScenario(scn);
  assert.ok(errors.some((e) => /уже используется/.test(e)));
});
test('validateScenario: дубль имён ЦЕПОЧЕК = ошибка (метрики flow ключуются по имени)', () => {
  const scn = {
    baseUrl: 'http://localhost:8080',
    flows: [
      { name: 'same', steps: [{ name: 'a', method: 'GET', path: '/a' }] },
      { name: 'same', steps: [{ name: 'b', method: 'GET', path: '/b' }] },
    ],
  };
  const { errors } = validateScenario(scn);
  assert.ok(errors.some((e) => /уже используется/.test(e)), 'дубль имени цепочки должен быть ошибкой');
});
test('validateScenario: имя цепочки = имя запроса = ошибка', () => {
  const scn = {
    baseUrl: 'http://localhost:8080',
    requests: [{ name: 'X', method: 'GET', path: '/r' }],
    flows: [{ name: 'X', steps: [{ name: 'step1', method: 'GET', path: '/f' }] }],
  };
  const { errors } = validateScenario(scn);
  assert.ok(errors.some((e) => /уже используется/.test(e)));
});
test('validateScenario: write-шаг в цепочке без allowWrites = ошибка', () => {
  const scn = baseFlowScn([{ name: 'w', method: 'POST', path: '/x', body: {} }]);
  const { errors } = validateScenario(scn);
  assert.ok(errors.some((e) => /allowWrites/.test(e)));
});
test('validateScenario: ни requests, ни flows = ошибка', () => {
  const { errors } = validateScenario({ baseUrl: 'http://localhost:8080' });
  assert.ok(errors.some((e) => /requests.*flows|flows.*requests/.test(e)));
});
test('validateScenario: обратная совместимость — requests-only нормализуется в одношаговые flow', () => {
  const scn = { baseUrl: 'http://localhost:8080', requests: [{ name: 'r', method: 'GET', path: '/x' }] };
  const { errors } = validateScenario(scn);
  assert.deepEqual(errors, []);
  assert.equal(scn._hasExplicitFlows, false);
  assert.equal(scn._flows[0]._track, false);
  assert.equal(scn._flows[0].steps.length, 1);
});
test('validateScenario: NaN durationSec/maxRps ловится (напр. --duration 30s → Number=NaN) — не ложный зелёный (фикс ревью)', () => {
  // так cmdRun пишет нечисловой CLI-флаг: Number("30s") = NaN. Раньше typeof NaN==="number" пролезал.
  const dur = validateScenario({ baseUrl: 'http://localhost:8080', requests: [{ name: 'r', method: 'GET', path: '/x' }], load: { durationSec: NaN } });
  assert.ok(dur.errors.some((e) => /durationSec/.test(e)), 'NaN durationSec должен быть ошибкой');
  const rps = validateScenario({ baseUrl: 'http://localhost:8080', requests: [{ name: 'r', method: 'GET', path: '/x' }], load: { maxRps: NaN } });
  assert.ok(rps.errors.some((e) => /maxRps/.test(e)), 'NaN maxRps должен быть ошибкой');
});
test('validateScenario: тело на GET/HEAD — ошибка конфига, а не 100% ошибок в рантайме (фикс ревью)', () => {
  const getBody = validateScenario({ baseUrl: 'http://localhost:8080', requests: [{ name: 'r', method: 'GET', path: '/x', body: { q: 'hi' } }] });
  assert.ok(getBody.errors.some((e) => /GET.*не может нести тело|тело/.test(e)), 'GET+body должен быть ошибкой');
  const headBt = validateScenario({ baseUrl: 'http://localhost:8080', requests: [{ name: 'r', method: 'HEAD', path: '/x', bodyType: 'form', body: { q: 'hi' } }] });
  assert.ok(headBt.errors.some((e) => /HEAD.*не может нести тело|тело/.test(e)), 'HEAD+bodyType должен быть ошибкой');
});
test('summarizeTargetMetrics: warmupSec исключает cold-start пик метрик цели (фикс ревью)', () => {
  const data = { intervalSec: 5, errors: [], samples: [
    { t: 0, values: { 'svc CPU %': 120 } },  // прогрев — пик
    { t: 15, values: { 'svc CPU %': 40 } },
    { t: 20, values: { 'svc CPU %': 45 } },
  ] };
  const noWarmup = summarizeTargetMetrics(data, 0);
  assert.equal(noWarmup.metrics[0].max, 120, 'без warmup пик виден');
  const warmed = summarizeTargetMetrics(data, 10);
  assert.equal(warmed.metrics[0].max, 45, 'с warmupSec=10 пик t=0 исключён');
});

// ─── makeStats / mergeStats + flowStats ──
// ─── compareResults (детект регрессий) ──
function res({ p95 = 100, p99 = 150, err = 0, rps = 50, perRequest = [], flows = [], scenario = 's', baseUrl = 'http://x' } = {}) {
  return { scenario, baseUrl, latencyMs: { p95, p99 }, errorRatePct: err, rps, perRequest, flows };
}
test('compareResults: одинаковые метрики = STABLE', () => {
  const c = compareResults(res(), res());
  assert.equal(c.verdict, 'STABLE');
  assert.equal(c.regressions.length, 0);
});
test('compareResults: рост p95 выше порога = REGRESSED', () => {
  const c = compareResults(res({ p95: 100 }), res({ p95: 200 }));
  assert.equal(c.verdict, 'REGRESSED');
  assert.ok(c.overall.p95.regressed);
});
test('compareResults: мелкий рост в пределах minMs = не регресс', () => {
  const c = compareResults(res({ p95: 10 }), res({ p95: 13 })); // +3ms < minMs(5)
  assert.equal(c.verdict, 'STABLE');
});
test('compareResults: рост ошибок выше порога = REGRESSED', () => {
  const c = compareResults(res({ err: 0 }), res({ err: 2 }));
  assert.equal(c.verdict, 'REGRESSED');
  assert.ok(c.overall.errorRatePct.regressed);
});
test('compareResults: заметное улучшение p95 = IMPROVED', () => {
  const c = compareResults(res({ p95: 200 }), res({ p95: 100 }));
  assert.equal(c.verdict, 'IMPROVED');
});
test('compareResults: локальный регресс одного запроса ловится', () => {
  const base = res({ perRequest: [{ name: 'a', p95: 50, errPct: 0 }, { name: 'b', p95: 50, errPct: 0 }] });
  const cur = res({ perRequest: [{ name: 'a', p95: 50, errPct: 0 }, { name: 'b', p95: 200, errPct: 0 }] });
  const c = compareResults(base, cur);
  assert.equal(c.verdict, 'REGRESSED');
  assert.ok(c.perRequest.find((r) => r.name === 'b').regressed);
});
test('compareResults: новые/пропавшие запросы и разные сценарии в warnings', () => {
  const base = res({ scenario: 'old', perRequest: [{ name: 'a', p95: 50, errPct: 0 }] });
  const cur = res({ scenario: 'new', perRequest: [{ name: 'b', p95: 50, errPct: 0 }] });
  const c = compareResults(base, cur);
  assert.deepEqual(c.added, ['b']);
  assert.deepEqual(c.removed, ['a']);
  assert.ok(c.warnings.some((w) => /РАЗНЫЕ сценари/.test(w)));
});
test('compareResults: падение завершаемости цепочки = REGRESSED', () => {
  const base = res({ flows: [{ name: 'j', completionPct: 100 }] });
  const cur = res({ flows: [{ name: 'j', completionPct: 80 }] });
  const c = compareResults(base, cur);
  assert.equal(c.verdict, 'REGRESSED');
});
test('compareResults: дрейф имени (query-параметр) НЕ прячет регресс', () => {
  const base = res({ perRequest: [{ name: 'GET /a', p95: 20, errPct: 0 }] });
  const cur = res({ perRequest: [{ name: 'GET /a?x=1', p95: 900, errPct: 0 }] });
  const c = compareResults(base, cur);
  assert.equal(c.verdict, 'REGRESSED', 'регресс должен ловиться через нормализованный ключ');
});
test('compareResults: id-сегмент в имени схлопывается для матчинга', () => {
  const base = res({ perRequest: [{ name: 'GET /pools/111', p95: 20, errPct: 0 }] });
  const cur = res({ perRequest: [{ name: 'GET /pools/999', p95: 900, errPct: 0 }] });
  assert.equal(compareResults(base, cur).verdict, 'REGRESSED');
});
test('compareResults: NaN-порог НЕ отключает гейт (Number.isFinite guard)', () => {
  const c = compareResults(res({ p95: 100 }), res({ p95: 5000 }), { maxP95RegressionPct: NaN });
  assert.equal(c.verdict, 'REGRESSED', 'кривой порог должен откатываться к дефолту, а не отключать гейт');
});
test('compareResults: нулевая база p95 → deltaPct null, без Infinity, регресс есть', () => {
  const c = compareResults(res({ p95: 0 }), res({ p95: 800 }));
  assert.equal(c.overall.p95.deltaPct, null);
  assert.equal(c.overall.p95.regressed, true);
  assert.ok(JSON.stringify(c).indexOf('Infinity') === -1, 'в JSON не должно быть Infinity');
});

// ─── per-request thresholds (валидация) ──
test('validateScenario: thresholds.perRequest на корректное имя проходит', () => {
  const scn = { baseUrl: 'http://localhost:8080', requests: [{ name: 'a', method: 'GET', path: '/x' }],
    thresholds: { perRequest: { a: { p95Ms: 100 } } } };
  assert.deepEqual(validateScenario(scn).errors, []);
});
test('validateScenario: опечатка в имени perRequest = ОШИБКА (иначе SLO молча не enforce)', () => {
  const scn = { baseUrl: 'http://localhost:8080', requests: [{ name: 'a', method: 'GET', path: '/x' }],
    thresholds: { perRequest: { nope: { p95Ms: 50 } } } };
  assert.ok(validateScenario(scn).errors.some((e) => /nope/.test(e)), 'опечатка имени должна быть ошибкой');
});
test('validateScenario: perRequest без порогов = ошибка', () => {
  const scn = { baseUrl: 'http://localhost:8080', requests: [{ name: 'a', method: 'GET', path: '/x' }],
    thresholds: { perRequest: { a: {} } } };
  assert.ok(validateScenario(scn).errors.some((e) => /perRequest/.test(e)));
});

// ─── warmupSec (валидация) ──
test('validateScenario: warmupSec >= durationSec = ошибка', () => {
  const scn = { baseUrl: 'http://localhost:8080', requests: [{ name: 'a', method: 'GET', path: '/x' }],
    load: { vus: 1, durationSec: 10, warmupSec: 10 } };
  assert.ok(validateScenario(scn).errors.some((e) => /warmupSec/.test(e)));
});

// ─── toJUnitXml / toMarkdown ──
function sampleRep(verdict = 'FAIL') {
  return {
    scenario: 'demo & <test>', baseUrl: 'http://x', mode: 'load', verdict, durationSec: 20, startedAt: '2026-01-01T00:00:00Z',
    total: 100, rps: 5, errorRatePct: 0, workers: 1,
    latencyMs: { p95: 30, p99: 40 },
    perRequest: [{ name: 'GET /a?x=1', count: 100, rps: 5, errPct: 0, p95: 30, p99: 40, statuses: '200:100' }],
    flows: [{ name: 'j', completionPct: 90, p95Ms: 100, topBreak: { step: 's2' } }],
    checks: [{ name: 'p95 30ms <= 500ms', pass: true }, { name: '[GET /a] p95 30ms <= 1ms', pass: false }],
    resource: { saturated: [], cpuBusyCores: 0.1, cores: 8, elLagMaxMs: 20 }, hints: ['подсказка'],
  };
}
test('toJUnitXml: валидный XML, экранирование, failure на нарушенном пороге', () => {
  const xml = toJUnitXml(sampleRep('FAIL'));
  assert.match(xml, /^<\?xml/);
  assert.ok(xml.includes('&amp;') && xml.includes('&lt;test&gt;'), 'спецсимволы экранированы');
  assert.ok(xml.includes('&lt;='), '<= экранирован');
  assert.ok(xml.includes('<failure'), 'нарушенный порог = failure');
  assert.match(xml, /failures="/);
  // теги сбалансированы (грубая проверка)
  const open = (xml.match(/<testcase /g) || []).length;
  const flow = (xml.match(/classname="flows"/g) || []).length;
  assert.equal(flow, 1);
  assert.ok(open >= 3);
});
test('toMarkdown: содержит вердикт, таблицу и цепочки', () => {
  const md = toMarkdown(sampleRep('PASS'));
  assert.ok(md.includes('✅ PASS'));
  assert.ok(md.includes('| запрос |'));
  assert.ok(md.includes('90% завершено'));
});
test('toJUnitXml: C0-управляющие символы вырезаются (well-formed XML)', () => {
  const rep = sampleRep('PASS');
  rep.perRequest = [{ name: 'healthcheck', count: 1, rps: 1, errPct: 0, p95: 5, p99: 5, statuses: '200:1' }];
  const xml = toJUnitXml(rep);
  for (const ch of xml) { const c = ch.charCodeAt(0); assert.ok(!(c < 32 && c !== 9 && c !== 10 && c !== 13), `control char ${c} утёк в XML`); }
  assert.ok(xml.includes('healthcheck'));
});
test('toMarkdown: пайп | в имени экранируется (не ломает таблицу)', () => {
  const rep = sampleRep('PASS');
  rep.perRequest = [{ name: 'GET /a|b', count: 1, rps: 1, errPct: 0, p95: 5, p99: 5, statuses: '200:1' }];
  const md = toMarkdown(rep);
  assert.ok(md.includes('GET /a\\|b'), 'пайп должен быть экранирован');
});

// ─── монитор цели: parseMemMB / summarizeTargetMetrics ──
test('parseMemMB: разные единицы docker stats', () => {
  assert.equal(parseMemMB('616MiB / 7.606GiB'), 616);
  assert.equal(parseMemMB('1.5GiB'), 1536);
  assert.equal(parseMemMB('512KiB'), Number((512 / 1024).toFixed(1)));
  assert.equal(parseMemMB('мусор'), null);
});
test('summarizeTargetMetrics: min/avg/max/last/пик + насыщение CPU', () => {
  const data = { intervalSec: 5, errors: [], samples: [
    { t: 0, values: { 'svc CPU %': 10, 'svc MEM МБ': 500 } },
    { t: 5, values: { 'svc CPU %': 92, 'svc MEM МБ': 520 } },
    { t: 10, values: { 'svc CPU %': 40, 'svc MEM МБ': 510 } },
  ] };
  const s = summarizeTargetMetrics(data);
  const cpu = s.metrics.find((m) => m.name === 'svc CPU %');
  assert.equal(cpu.max, 92);
  assert.equal(cpu.peakAtSec, 5);
  assert.equal(cpu.last, 40);
  assert.equal(cpu.saturatedCpu, true);
  const mem = s.metrics.find((m) => m.name === 'svc MEM МБ');
  assert.equal(mem.saturatedCpu, false); // не CPU-метрика
});
test('summarizeTargetMetrics: нет сэмплов → null', () => {
  assert.equal(summarizeTargetMetrics({ intervalSec: 5, errors: [], samples: [] }), null);
});
test('summarizeTargetMetrics: дробная шкала CPU (0-1) — насыщение при >=0.85', () => {
  const s = summarizeTargetMetrics({ intervalSec: 5, errors: [], samples: [
    { t: 0, values: { 'svc CPU': 0.3 } }, { t: 5, values: { 'svc CPU': 0.95 } } ] });
  assert.equal(s.metrics[0].saturatedCpu, true);
});
test('summarizeTargetMetrics: счётчик с "cpu" в имени (max>100) НЕ флагуется как насыщение', () => {
  const s = summarizeTargetMetrics({ intervalSec: 5, errors: [], samples: [
    { t: 0, values: { 'cpu_seconds_total': 1200 } }, { t: 5, values: { 'cpu_seconds_total': 5000 } } ] });
  assert.equal(s.metrics[0].saturatedCpu, false);
});
test('validateScenario: опечатка в monitor.thresholds имени = ОШИБКА', () => {
  const scn = { baseUrl: 'http://x', requests: [{ name: 'a', method: 'GET', path: '/x' }],
    monitor: { docker: { containers: ['svc'] }, thresholds: { 'svc CPU': { max: 85 } } } }; // без " %"
  assert.ok(validateScenario(scn).errors.some((e) => /svc CPU/.test(e) && /нет такой метрики/.test(e)));
});
test('validateScenario: monitor без docker/prometheus = ошибка; кривой prometheus = ошибка', () => {
  const empty = { baseUrl: 'http://x', requests: [{ name: 'a', method: 'GET', path: '/x' }], monitor: {} };
  assert.ok(validateScenario(empty).errors.some((e) => /monitor/.test(e)));
  const badProm = { baseUrl: 'http://x', requests: [{ name: 'a', method: 'GET', path: '/x' }], monitor: { prometheus: { url: 'http://p' } } };
  assert.ok(validateScenario(badProm).errors.some((e) => /queries/.test(e)));
});

// ─── пресеты init --preset обязаны быть валидны (защита от дрейфа схемы) ──
test('presets: все встроенные пресеты валидируются', async () => {
  const { readFileSync, existsSync } = await import('node:fs');
  const { fileURLToPath } = await import('node:url');
  const { dirname, join } = await import('node:path');
  const dir = join(dirname(fileURLToPath(import.meta.url)), '..', 'presets');
  for (const name of ['smoke', 'browse', 'journey', 'stress', 'ci', 'write']) {
    const p = join(dir, `${name}.json`);
    assert.ok(existsSync(p), `нет файла пресета ${name}.json`);
    const scn = JSON.parse(readFileSync(p, 'utf8'));
    resolveEnvInScenario(scn, { LOADGEN_PW: 'x', TARGET_URL: 'http://localhost:8080' }, new Set());
    const { errors } = validateScenario(scn);
    assert.deepEqual(errors, [], `пресет ${name} невалиден: ${errors.join('; ')}`);
  }
});

// ─── SLO-пороги: p99Ms / rpsMin ──
test('validateScenario: p99Ms/rpsMin валидируются, per-request p99Ms тоже', () => {
  const ok = { baseUrl: 'http://x', requests: [{ name: 'a', method: 'GET', path: '/x' }],
    thresholds: { p99Ms: 300, rpsMin: 100, perRequest: { a: { p99Ms: 200 } } } };
  assert.deepEqual(validateScenario(ok).errors, []);
  const bad = { baseUrl: 'http://x', requests: [{ name: 'a', method: 'GET', path: '/x' }], thresholds: { p99Ms: -5 } };
  assert.ok(validateScenario(bad).errors.some((e) => /p99Ms/.test(e)));
  const bad2 = { baseUrl: 'http://x', requests: [{ name: 'a', method: 'GET', path: '/x' }], thresholds: { rpsMin: 'x' } };
  assert.ok(validateScenario(bad2).errors.some((e) => /rpsMin/.test(e)));
});
test('mergeResults: p99Ms и rpsMin входят в вердикт агрегата', () => {
  const mk = (rps) => ({ scenario: 's', baseUrl: 'x', total: 1000, errors: 0, rps, durationSec: 10, latencyMs: {},
    hist: histogram(Array.from({ length: 1000 }, () => 20)), histBoundsV: 1,
    perRequest: [{ name: 'a', count: 1000, errPct: 0, rps, hist: histogram(Array.from({ length: 1000 }, () => 20)) }],
    thresholds: { p95Ms: 1000, errorRatePct: 1, p99Ms: 10, rpsMin: 1000 } }); // p99 20>10 нарушит; RPS сумма 600<1000 нарушит
  const m = mergeResults([mk(300), mk(300)]);
  assert.equal(m.verdict, 'FAIL');
  assert.ok(m.checks.some((c) => /p99/.test(c.name) && !c.pass));
  assert.ok(m.checks.some((c) => /RPS/.test(c.name) && !c.pass));
});

// ─── красивый вывод: toHtml / asciiHistogram ──
test('asciiHistogram: непустые бакеты с барами', () => {
  const h = histogram([5, 5, 5, 40, 40, 300]); // три группы
  const rows = asciiHistogram(h);
  assert.ok(rows.length >= 2);
  assert.ok(rows.some((r) => r.includes('█')), 'должны быть бары');
  assert.ok(rows.every((r) => /\d+$/.test(r)), 'в конце строки — счётчик');
});
test('asciiHistogram: пустая гистограмма → []', () => {
  assert.deepEqual(asciiHistogram([]), []);
  assert.deepEqual(asciiHistogram(histogram([])), []);
});
test('toHtml: валидный самодостаточный HTML с вердиктом, экранированием и баром', () => {
  const rep = {
    scenario: 'demo <x> & y', baseUrl: 'http://t', mode: 'load', verdict: 'FAIL', durationSec: 10, startedAt: '2026-01-01', workers: 1,
    total: 100, rps: 5, errorRatePct: 2, latencyMs: { p95: 30, p99: 40 }, hist: histogram([10, 10, 40, 300]),
    perRequest: [{ name: 'GET /a<b>', count: 100, rps: 5, errPct: 2, p95: 30, p99: 40 }],
    checks: [{ name: 'p95 30 <= 20', pass: false }], hints: ['подсказка'], version: '1.14.0',
  };
  const html = toHtml(rep);
  assert.match(html, /^<!doctype html>/i);
  assert.ok(html.includes('demo &lt;x&gt; &amp; y'), 'спецсимволы экранированы');
  assert.ok(html.includes('class="badge">FAIL'), 'бейдж вердикта');
  assert.ok(html.includes('<rect'), 'SVG-бары гистограммы');
  assert.ok(!html.includes('<script'), 'без скриптов (самодостаточный статичный отчёт)');
});

// ─── гистограммы и слияние прогонов (merge / распределёнка) ──
test('histogram + percentileFromHistogram: перцентиль в пределах ширины бакета', () => {
  const samples = Array.from({ length: 1000 }, (_, i) => (i < 950 ? 10 : 200)).sort((a, b) => a - b); // 95% =10мс, 5% =200мс
  const h = histogram(samples);
  assert.equal(h.reduce((a, b) => a + b, 0), 1000);
  assert.ok(percentileFromHistogram(h, 50) <= 10 && percentileFromHistogram(h, 90) <= 15);
  assert.ok(percentileFromHistogram(h, 99) >= 100); // хвост в 200мс-бакете
});
test('histogram: суммируется точно (два прогона → корректная общая гистограмма)', () => {
  const a = histogram([5, 5, 50]);
  const b = histogram([5, 300]);
  const sum = a.map((v, i) => v + b[i]);
  const both = histogram([5, 5, 5, 50, 300]);
  assert.deepEqual(sum, both);
});
test('mergeResults: суммарный RPS/total + вердикт из порогов', () => {
  const mk = (rps, p95bucketFill) => ({
    scenario: 's', baseUrl: 'http://x', total: 1000, errors: 0, rps, durationSec: 10,
    latencyMs: { p95: 20 }, hist: histogram(Array.from({ length: 1000 }, () => p95bucketFill)), histBoundsV: 1,
    perRequest: [{ name: 'api', count: 1000, rps, errPct: 0, hist: histogram(Array.from({ length: 1000 }, () => p95bucketFill)) }],
    thresholds: { p95Ms: 100, errorRatePct: 1 }, tool: 'loadgen',
  });
  const m = mergeResults([mk(300, 20), mk(320, 20)]);
  assert.equal(m.machines, 2);
  // RPS считается из объединённого окна (total / max(durationSec)), а НЕ суммой per-window rps:
  // 2000 запросов / 10с = 200 (сумма 300+320=620 была бы завышена и дала ложный PASS порога rpsMin)
  assert.equal(m.rps, 200);
  assert.equal(m.total, 2000);
  assert.equal(m.verdict, 'PASS');   // p95 20 <= 100
  assert.equal(m.perRequest[0].rps, 200); // 2000 / 10с
});
test('mergeResults: rpsMin из объединённого окна — рассинхрон окон НЕ даёт ложный PASS (фикс ревью)', () => {
  // две машины: у каждой 1000 запросов, но окна 10с и 30с (типичный рассинхрон warmup/раннего финиша).
  // Сумма per-window rps (100+33=133) прошла бы порог rpsMin:120. Честно: 2000/30 ≈ 66.7 < 120 → FAIL.
  const mk = (durationSec, rps) => ({
    scenario: 's', baseUrl: 'http://x', total: 1000, errors: 0, rps, durationSec,
    latencyMs: { p95: 20 }, hist: histogram(Array.from({ length: 1000 }, () => 20)), histBoundsV: 1,
    perRequest: [{ name: 'api', count: 1000, rps, errPct: 0, hist: histogram(Array.from({ length: 1000 }, () => 20)) }],
    thresholds: { rpsMin: 120 }, tool: 'loadgen',
  });
  const m = mergeResults([mk(10, 100), mk(30, 33.3)]);
  assert.equal(m.rps, Number((2000 / 30).toFixed(1))); // 66.7, а не 133
  assert.equal(m.verdict, 'FAIL');                       // 66.7 < 120 — порог честно нарушен
});
test('mergeResults: результат без hist (старая версия) = ошибка', () => {
  assert.throws(() => mergeResults([{ scenario: 's', baseUrl: 'x', total: 1, errors: 0, rps: 1, latencyMs: {} },
    { scenario: 's', baseUrl: 'x', total: 1, errors: 0, rps: 1, latencyMs: {}, hist: [] }]), /гистограмм/);
});
test('mergeResults: несовместимая сетка бакетов (другая версия) = ошибка', () => {
  const good = { scenario: 's', baseUrl: 'x', total: 1, errors: 0, rps: 1, latencyMs: {}, hist: histogram([5]), histBoundsV: 1, perRequest: [], thresholds: {} };
  const wrongV = { ...good, histBoundsV: 2 };
  assert.throws(() => mergeResults([good, wrongV]), /сетка/);
  const wrongLen = { ...good, hist: [1, 2, 3] };
  assert.throws(() => mergeResults([good, wrongLen]), /сетка/);
});
test('mergeResults: per-request без hist (частичный файл) = ошибка, не ложный PASS', () => {
  const g = { scenario: 's', baseUrl: 'x', total: 10, errors: 0, rps: 1, latencyMs: {}, hist: histogram([5]), histBoundsV: 1,
    perRequest: [{ name: 'slow', count: 10, errPct: 0, rps: 1 }], thresholds: { perRequest: { slow: { p95Ms: 100 } } } }; // нет pr.hist
  assert.throws(() => mergeResults([g, g]), /гистограм/);
});

// ─── тела не-JSON: form / multipart ──
test('encodeForm: url-кодирование и подстановка плейсхолдеров', () => {
  assert.equal(encodeForm({ grant_type: 'password', u: '{{name}}', 'sp ace': 'a&b' }, { name: 'ivan' }, {}),
    'grant_type=password&u=ivan&sp%20ace=a%26b');
});
test('encodeForm: числа и boolean приводятся к строке', () => {
  assert.equal(encodeForm({ n: 5, b: true }, {}, {}), 'n=5&b=true');
});
test('validateScenario: bodyType form требует объект-body; неизвестный bodyType = ошибка', () => {
  const bad = { baseUrl: 'http://x', requests: [{ name: 'r', method: 'POST', path: '/x', bodyType: 'form', body: 'raw' }], allowWrites: true };
  assert.ok(validateScenario(bad).errors.some((e) => /form.*объект|объект.*form/i.test(e) || /требует body-объект/.test(e)));
  const bad2 = { baseUrl: 'http://x', requests: [{ name: 'r', method: 'POST', path: '/x', bodyType: 'xml', body: {} }], allowWrites: true };
  assert.ok(validateScenario(bad2).errors.some((e) => /bodyType/.test(e)));
});
test('buildMultipart: собирает text-поле с CRLF-фреймингом и boundary в Content-Type', () => {
  const mp = buildMultipart('.', { field: '{{v}}' }, { v: 'hello' }, {});
  const s = mp.body.toString();
  assert.match(mp.contentType, /^multipart\/form-data; boundary=----loadgen/);
  const b = mp.contentType.split('boundary=')[1];
  assert.ok(s.includes(`--${b}\r\nContent-Disposition: form-data; name="field"\r\n\r\nhello\r\n`));
  assert.ok(s.endsWith(`--${b}--\r\n`));
});
test('buildMultipart: плейсхолдеры в filename/type рендерятся', () => {
  // файл этого теста читается как содержимое — путь абсолютный к самому себе
  const self = new URL(import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1');
  const mp = buildMultipart('.', { doc: { file: self, filename: '{{nm}}.csv', type: 'text/{{fmt}}' } }, { nm: 'rep', fmt: 'csv' }, {});
  const s = mp.body.toString();
  assert.ok(s.includes('filename="rep.csv"'), 'filename должен рендериться');
  assert.ok(s.includes('Content-Type: text/csv'), 'type должен рендериться');
});

// ─── подстановка ${ENV} ──
test('resolveEnvInScenario: подставляет заданные env и дефолты, ловит недостающие', () => {
  const scn = { baseUrl: 'http://${HOST:-localhost}:8080', auth: { token: '${TOK}' },
    headers: { 'X-Key': '${MISSING}' }, requests: [{ path: '/x/${TOK}' }] };
  const missing = resolveEnvInScenario(scn, { TOK: 'secret' }, new Set());
  assert.equal(scn.baseUrl, 'http://localhost:8080');   // дефолт
  assert.equal(scn.auth.token, 'secret');               // из env
  assert.equal(scn.requests[0].path, '/x/secret');      // вложенно
  assert.deepEqual([...missing], ['MISSING']);          // без env и без дефолта
});
test('resolveEnvInScenario: $${VAR} остаётся литералом ${VAR}', () => {
  const scn = { name: 'literal $${KEEP} here' };
  resolveEnvInScenario(scn, {}, new Set());
  assert.equal(scn.name, 'literal ${KEEP} here');
});
test('resolveEnvInScenario: пустая env-строка считается незаданной (идёт дефолт)', () => {
  const scn = { a: '${EMPTY:-fallback}' };
  resolveEnvInScenario(scn, { EMPTY: '' }, new Set());
  assert.equal(scn.a, 'fallback');
});

// ─── setup / teardown (жизненный цикл) ──
test('validateScenario: setup-захват доступен нагрузке (нет ошибки placeholder)', () => {
  const scn = { baseUrl: 'http://x',
    setup: [{ name: 's', method: 'GET', path: '/list', capture: { pid: 'content[0].id' } }],
    requests: [{ name: 'use', method: 'GET', path: '/item/{{pid}}' }] };
  assert.deepEqual(validateScenario(scn).errors, []);
});
test('validateScenario: setup НЕ видит свой будущий захват (forward-ref = ошибка)', () => {
  const scn = { baseUrl: 'http://x',
    setup: [{ name: 's', method: 'GET', path: '/x/{{pid}}', capture: { pid: 'id' } }],
    requests: [{ name: 'r', method: 'GET', path: '/y' }] };
  assert.ok(validateScenario(scn).errors.some((e) => /\{\{pid\}\}/.test(e)));
});
test('validateScenario: teardown видит захваты setup', () => {
  const scn = { baseUrl: 'http://x',
    setup: [{ name: 's', method: 'POST', path: '/create', capture: { pid: 'id' } }],
    requests: [{ name: 'r', method: 'GET', path: '/y' }],
    teardown: [{ name: 't', method: 'DELETE', path: '/item/{{pid}}' }],
    allowWrites: true };
  assert.deepEqual(validateScenario(scn).errors, []);
});
test('validateScenario: write в setup без allowWrites = ошибка', () => {
  const scn = { baseUrl: 'http://x',
    setup: [{ name: 's', method: 'POST', path: '/create' }],
    requests: [{ name: 'r', method: 'GET', path: '/y' }] };
  assert.ok(validateScenario(scn).errors.some((e) => /allowWrites/.test(e)));
});

// ─── stageTargetAt (профиль нагрузки) ──
test('stageTargetAt: линейный разгон/плато/спад', () => {
  const stages = [{ vus: 20, durationSec: 10 }, { vus: 20, durationSec: 10 }, { vus: 0, durationSec: 10 }];
  assert.equal(stageTargetAt(stages, 0), 0);          // старт с 0
  assert.equal(stageTargetAt(stages, 5000), 10);      // середина разгона → 10
  assert.equal(stageTargetAt(stages, 10000), 20);     // конец разгона → 20
  assert.equal(stageTargetAt(stages, 15000), 20);     // плато
  assert.equal(stageTargetAt(stages, 20000), 20);     // конец плато
  assert.equal(stageTargetAt(stages, 25000), 10);     // середина спада → 10
  assert.equal(stageTargetAt(stages, 30000), 0);      // конец → 0
  assert.equal(stageTargetAt(stages, 99999), 0);      // после конца держим последний уровень
});
test('validateScenario: stages выводят пик vus и суммарную длительность', () => {
  const scn = { baseUrl: 'http://localhost:8080', requests: [{ name: 'r', method: 'GET', path: '/x' }],
    load: { stages: [{ vus: 50, durationSec: 20 }, { vus: 200, durationSec: 40 }, { vus: 0, durationSec: 10 }] } };
  const { errors } = validateScenario(scn);
  assert.deepEqual(errors, []);
  assert.equal(scn.load.vus, 200);        // пик
  assert.equal(scn.load.durationSec, 70); // сумма
});
test('validateScenario: пустой/битый stages = ошибка', () => {
  const bad = { baseUrl: 'http://localhost:8080', requests: [{ name: 'r', method: 'GET', path: '/x' }], load: { stages: [] } };
  assert.ok(validateScenario(bad).errors.some((e) => /stages/.test(e)));
  const bad2 = { baseUrl: 'http://localhost:8080', requests: [{ name: 'r', method: 'GET', path: '/x' }], load: { stages: [{ vus: -1, durationSec: 5 }] } };
  assert.ok(validateScenario(bad2).errors.some((e) => /stages/.test(e)));
});

// ─── разбивка латентности TTFB/download (запись в статистику) ──
test('makeStats.record: сохраняет TTFB третьим элементом выборки', () => {
  const st = makeStats([{ name: 'r' }]);
  st.record({ name: 'r' }, { ok: true, ms: 100, ttfb: 60, status: 200 }, 1000);
  const lat = st.per.get('r').lat;
  assert.deepEqual(lat[0], [1000, 100, 60]);
});
test('makeStats.record: без TTFB подставляет ms (для старых/ошибочных выборок)', () => {
  const st = makeStats([{ name: 'r' }]);
  st.record({ name: 'r' }, { ok: true, ms: 42, status: 200 }, 5);
  assert.deepEqual(st.per.get('r').lat[0], [5, 42, 42]);
});
test('mergeStats: тройки [now,ms,ttfb] переносятся при слиянии воркеров', () => {
  const a = makeStats([{ name: 'r' }]);
  a.record({ name: 'r' }, { ok: true, ms: 10, ttfb: 8, status: 200 }, 1);
  a.startedAt = 0; a.endedAt = 1;
  const merged = mergeStats([serializeStats(a)], [{ name: 'r' }]);
  assert.deepEqual(merged.per.get('r').lat[0], [1, 10, 8]);
});

test('mergeStats: складывает per-step и flowStats из нескольких частей', () => {
  const steps = [{ name: 's1' }, { name: 's2' }];
  const a = makeStats(steps); a.startedAt = 100; a.endedAt = 200;
  a.record({ name: 's1' }, { ok: true, ms: 10, status: 200, used: {} }, 150);
  a.recordFlow('f', true, 30, null);
  const b = makeStats(steps); b.startedAt = 120; b.endedAt = 260;
  b.record({ name: 's1' }, { ok: false, kind: 'check', status: 200, errMsg: 'x', used: {} }, 160);
  b.recordFlow('f', false, 0, 's2');
  const merged = mergeStats([serializeStats(a), serializeStats(b)], steps);
  assert.equal(merged.total, 2);
  assert.equal(merged.errors, 1);
  assert.equal(merged.startedAt, 100);
  assert.equal(merged.endedAt, 260);
  const f = merged.flowStats.get('f');
  assert.equal(f.started, 2);
  assert.equal(f.completed, 1);
  assert.equal(f.breaks.get('s2'), 1);
});

// ─── SQL-режим (kind:"sql") ──
test('resolveSqlDriver: пресет psql + переопределения custom', () => {
  const pg = resolveSqlDriver({ driver: 'psql', command: ['psql', '-d', 'x'] });
  assert.deepEqual(pg.command, ['psql', '-d', 'x', '-q', '-A']); // флаги пресета дописаны
  assert.match(pg.markCmd, /__LOADGEN_ROW_DONE__/); // маркер конца результата
  assert.match(pg.markCmd, /LGSTAT=:ERROR/);         // in-band статус для надёжного детекта ошибки
  assert.ok(pg.statusRe instanceof RegExp && pg.timeRe instanceof RegExp && pg.errRe instanceof RegExp);
  assert.equal(pg.timeUnit, 'ms');
  const custom = resolveSqlDriver({ driver: 'custom', command: ['mysql'], mark: '-- DONE', timeRegex: 'took (\\d+)ms' });
  assert.equal(custom.markCmd, '-- DONE');
  assert.ok(custom.timeRe.test('took 5ms'));
});
test('parseSqlChunk: psql SELECT — строки из футера, латентность из Time, LGSTAT=false → ok', () => {
  const cfg = resolveSqlDriver({ driver: 'psql', command: ['psql'] });
  const r = parseSqlChunk('n\n1\n2\n3\n(3 rows)\nTime: 4.029 ms\nLGSTAT=false 00000\n', cfg, 99);
  assert.equal(r.ok, true);
  assert.equal(r.rows, 3);
  assert.equal(r.ms, 4.029); // серверное время, НЕ wall-clock 99
});
test('parseSqlChunk: psql LGSTAT=true → провал вида query (in-band статус, не stderr)', () => {
  const cfg = resolveSqlDriver({ driver: 'psql', command: ['psql'] });
  const r = parseSqlChunk('ERROR:  column "x" does not exist\nTime: 1.1 ms\nLGSTAT=true 42703\n', cfg, 50);
  assert.equal(r.ok, false);
  assert.equal(r.kind, 'query');
  assert.match(r.errMsg, /column "x" does not exist/);
});
test('parseSqlChunk: LGSTAT=true даже без текста ошибки (используем SQLSTATE)', () => {
  const cfg = resolveSqlDriver({ driver: 'psql', command: ['psql'] });
  const r = parseSqlChunk('\nTime: 1.0 ms\nLGSTAT=true 23505\n', cfg, 50); // stderr-текст ушёл по гонке
  assert.equal(r.ok, false);
  assert.match(r.errMsg, /23505/);
});
test('parseSqlChunk: LGSTAT=false АВТОРИТЕТЕН — «протёкший» из соседа ERROR-текст НЕ даёт ложную ошибку (фикс гонки)', () => {
  const cfg = resolveSqlDriver({ driver: 'psql', command: ['psql'] });
  // в чанке есть строка ERROR: (припозднившийся stderr предыдущего запроса), но LGSTAT говорит false
  const r = parseSqlChunk('ERROR:  relation "prev" does not exist\n1\n(1 row)\nTime: 0.5 ms\nLGSTAT=false 00000\n', cfg, 50);
  assert.equal(r.ok, true, 'статус=false важнее протёкшего ERROR-текста');
  assert.equal(r.rows, 1);
});
test('parseSqlChunk: без statusRe (custom-драйвер) — ошибка по stderr-regex (fallback)', () => {
  const cfg = resolveSqlDriver({ driver: 'custom', command: ['mysql'], mark: '-- D', errorRegex: '^ERROR' });
  const r = parseSqlChunk('ERROR 1146: table missing\n', cfg, 20);
  assert.equal(r.ok, false);
  assert.equal(r.kind, 'query');
});
test('parseSqlChunk: write без футера строк → ok, rows 0; wall-clock fallback если нет Time', () => {
  const cfg = resolveSqlDriver({ driver: 'psql', command: ['psql'] });
  const ins = parseSqlChunk('\nTime: 0.7 ms\n', cfg, 50);
  assert.equal(ins.ok, true); assert.equal(ins.rows, 0); assert.equal(ins.ms, 0.7);
  const noTime = parseSqlChunk('somerow\n(1 row)\n', cfg, 42); // нет строки Time — берём wall
  assert.equal(noTime.ms, 42);
});
test('parseSqlChunk: sqlline печатает секунды → переводим в мс', () => {
  const cfg = resolveSqlDriver({ driver: 'sqlline', command: ['sqlline'] });
  const r = parseSqlChunk('X\n1 row selected (0.05 seconds)\n', cfg, 999);
  assert.equal(r.ok, true);
  assert.equal(r.ms, 50); // 0.05 сек × 1000
});
test('validateScenario: корректный kind:"sql" проходит без baseUrl и резолвит драйвер', () => {
  const scn = { kind: 'sql', sql: { driver: 'psql', command: ['psql', '-d', 'x'] },
    requests: [{ name: 'q', sql: 'select 1', checks: { minRows: 1, maxMs: 50 } }], load: { vus: 2, durationSec: 2 } };
  const { errors } = validateScenario(scn);
  assert.deepEqual(errors, []);
  assert.ok(scn._sqlDriver, 'драйвер скомпилирован');
  assert.equal(scn._steps.length, 1);
});
test('validateScenario: kind:"sql" без блока sql / без command = ошибка', () => {
  const noSql = validateScenario({ kind: 'sql', requests: [{ name: 'q', sql: 'select 1' }], load: { vus: 1, durationSec: 1 } });
  assert.ok(noSql.errors.some((e) => /sql/.test(e)), 'нет блока sql — ошибка');
  const noCmd = validateScenario({ kind: 'sql', sql: { driver: 'psql' }, requests: [{ name: 'q', sql: 'select 1' }], load: { vus: 1, durationSec: 1 } });
  assert.ok(noCmd.errors.some((e) => /command/.test(e)), 'нет command — ошибка');
});
test('validateScenario: SQL-шаг без поля sql = ошибка; неверный minRows = ошибка', () => {
  const noQuery = validateScenario({ kind: 'sql', sql: { driver: 'psql', command: ['psql'] }, requests: [{ name: 'x' }], load: { vus: 1, durationSec: 1 } });
  assert.ok(noQuery.errors.some((e) => /нет "sql"|sql/.test(e)));
  const badRows = validateScenario({ kind: 'sql', sql: { driver: 'psql', command: ['psql'] }, requests: [{ name: 'x', sql: 'select 1', checks: { minRows: -1 } }], load: { vus: 1, durationSec: 1 } });
  assert.ok(badRows.errors.some((e) => /minRows/.test(e)));
});
test('validateScenario: {{var}} в тексте SQL проверяется на объявленность', () => {
  const undef = validateScenario({ kind: 'sql', sql: { driver: 'psql', command: ['psql'] }, requests: [{ name: 'x', sql: 'select * from t limit {{missing}}' }], load: { vus: 1, durationSec: 1 } });
  assert.ok(undef.errors.some((e) => /\{\{missing\}\}/.test(e)), 'необъявленный placeholder в sql — ошибка');
  const ok = validateScenario({ kind: 'sql', sql: { driver: 'psql', command: ['psql'] }, vars: { lim: [1, 2] }, requests: [{ name: 'x', sql: 'select * from t limit {{lim}}' }], load: { vus: 1, durationSec: 1 } });
  assert.deepEqual(ok.errors, []);
});

// ─── SQL-режим: фиксы адверсариального ревью ──
test('resolveSqlDriver: psql задаёт серверный statement_timeout (защита от утечки backend при таймауте)', () => {
  const pg = resolveSqlDriver({ driver: 'psql', command: ['psql'] });
  assert.match(pg.stmtTimeout, /statement_timeout/);
  assert.match(pg.stmtTimeout, /\{ms\}/); // подставляется timeoutMs при открытии сессии
});
test('validateScenario: SQL-запись без allowWrites = ошибка (двойная защита и для SQL)', () => {
  const scn = { kind: 'sql', sql: { driver: 'psql', command: ['psql'] },
    requests: [{ name: 'ins', sql: 'insert into t(v) values (1)' }], load: { vus: 1, durationSec: 1 } };
  const { errors } = validateScenario(scn);
  assert.ok(errors.some((e) => /allowWrites/.test(e)), 'INSERT без allowWrites должен блокироваться на уровне сценария');
});
test('validateScenario: write-guard SQL ловит VACUUM/REFRESH/SELECT INTO (не только DML)', () => {
  for (const q of ['vacuum analyze t', 'refresh materialized view mv', 'select * into backup from t']) {
    const { errors } = validateScenario({ kind: 'sql', sql: { driver: 'psql', command: ['psql'] },
      requests: [{ name: 'q', sql: q }], load: { vus: 1, durationSec: 1 } });
    assert.ok(errors.some((e) => /allowWrites/.test(e)), `«${q}» должен считаться изменяющим`);
  }
  // чистый SELECT остаётся read-only (не требует allowWrites)
  const ro = validateScenario({ kind: 'sql', sql: { driver: 'psql', command: ['psql'] },
    requests: [{ name: 'q', sql: 'select count(*) from information_schema.tables' }], load: { vus: 1, durationSec: 1 } });
  assert.deepEqual(ro.errors, []);
});
test('validateScenario: minRows на INSERT без RETURNING = предупреждение (не вернёт строк)', () => {
  const { warnings } = validateScenario({ kind: 'sql', sql: { driver: 'psql', command: ['psql'] }, allowWrites: true,
    requests: [{ name: 'ins', sql: 'insert into t(v) values (1)', checks: { minRows: 1 } }], load: { vus: 1, durationSec: 1 } });
  assert.ok(warnings.some((w) => /RETURNING|не возвращает строк/.test(w)));
});
test('validateScenario: custom SQL-драйвер без statusRegex = предупреждение best-effort по ошибкам', () => {
  const { warnings } = validateScenario({ kind: 'sql', sql: { driver: 'custom', command: ['mysql'], mark: '-- D' },
    requests: [{ name: 'q', sql: 'select 1' }], load: { vus: 1, durationSec: 1 } });
  assert.ok(warnings.some((w) => /best-effort|statusRegex|stderr/.test(w)));
});

// ─── pipeline-режим (kind:"pipeline") ──
function basePipe(over = {}) {
  return {
    kind: 'pipeline', allowWrites: true,
    produce: { command: ['kafka-console-producer', '--topic', 't'], message: '{{corr}}:v' },
    verify: { sql: { driver: 'psql', command: ['psql'] }, query: "select 1 from t where corr='{{corr}}'" },
    pipeline: { pollIntervalMs: 50, timeoutMs: 3000 },
    load: { vus: 2, durationSec: 5 }, ...over,
  };
}
test('validateScenario: корректный kind:"pipeline" проходит, резолвит verify-драйвер, _steps=[pipeline]', () => {
  const scn = basePipe();
  const { errors } = validateScenario(scn);
  assert.deepEqual(errors, []);
  assert.ok(scn._verifyDriver, 'verify-драйвер скомпилирован');
  assert.equal(scn._steps.length, 1);
  assert.equal(scn._steps[0].name, 'pipeline');
  assert.equal(scn.pipeline.pollIntervalMs, 50);
});
test('validateScenario: pipeline без produce.command / без {{corr}} в message = ошибка', () => {
  const noCmd = validateScenario(basePipe({ produce: { message: '{{corr}}:v' } }));
  assert.ok(noCmd.errors.some((e) => /produce/.test(e)));
  const noCorr = validateScenario(basePipe({ produce: { command: ['p'], message: 'no-key' } }));
  assert.ok(noCorr.errors.some((e) => /\{\{corr\}\}/.test(e)));
});
test('validateScenario: pipeline без verify / без {{corr}} в query = ошибка', () => {
  const noVer = validateScenario(basePipe({ verify: undefined }));
  assert.ok(noVer.errors.some((e) => /verify/.test(e)));
  const noCorr = validateScenario(basePipe({ verify: { sql: { driver: 'psql', command: ['psql'] }, query: 'select 1' } }));
  assert.ok(noCorr.errors.some((e) => /\{\{corr\}\}/.test(e)));
});
test('validateScenario: pipeline без allowWrites = ошибка (produce = запись)', () => {
  const { errors } = validateScenario(basePipe({ allowWrites: undefined }));
  assert.ok(errors.some((e) => /allowWrites/.test(e)));
});

// ─── pipeline: фиксы адверсариального ревью ──
test('validateScenario: pipeline с необъявленным {{плейсхолдером}} в message = ошибка (не краш в рантайме)', () => {
  const bad = validateScenario(basePipe({ produce: { command: ['p'], message: '{{corr}}:{{typo}}' } }));
  assert.ok(bad.errors.some((e) => /\{\{typo\}\}/.test(e)), 'опечатка плейсхолдера должна ловиться на validate');
  const ok = validateScenario(basePipe({
    vars: { region: ['eu', 'us'] },
    produce: { command: ['p'], message: '{{corr}}:{{region}}' },
    verify: { sql: { driver: 'psql', command: ['psql'] }, query: "select 1 from t where corr='{{corr}}' and r='{{region}}'" },
  }));
  assert.deepEqual(ok.errors, [], 'объявленный list-var + corr — валидно');
});
test('renderTemplate: общий used согласует list-переменную между двумя шаблонами (фикс рассинхрона pipeline)', () => {
  const u = {};
  const a = renderTemplate('{{region}}', { region: ['eu', 'us', 'asia', 'af'] }, u);
  const b = renderTemplate('prefix-{{region}}', { region: ['eu', 'us', 'asia', 'af'] }, u);
  assert.equal(b, 'prefix-' + a, 'второй шаблон переиспользует значение из общего used — produce и verify видят одно значение');
});

// ─── монитор consumer-lag Kafka (monitor.kafka) ──
test('parseKafkaLag: сумма LAG по партициям из describe-вывода', () => {
  const out = [
    'GROUP           TOPIC       PARTITION  CURRENT-OFFSET  LOG-END-OFFSET  LAG   CONSUMER-ID  HOST  CLIENT-ID',
    'g1              events      0          100             150             50    c1           /1    cid1',
    'g1              events      1          200             230             30    c1           /1    cid1',
    'g1              events      2          10              10              0     c1           /1    cid1',
  ].join('\n');
  const r = parseKafkaLag(out);
  assert.equal(r.lag, 80); // 50+30+0
  assert.equal(r.rows, 3);
});
test('parseKafkaLag: нет строк данных (пустая группа/только заголовок) = null', () => {
  assert.equal(parseKafkaLag('GROUP TOPIC PARTITION CURRENT-OFFSET LOG-END-OFFSET LAG\n'), null);
  assert.equal(parseKafkaLag('Consumer group g1 has no active members.\n'), null);
});
test('summarizeTargetMetrics: растущий consumer-lag помечается risingLag (backpressure)', () => {
  const rising = summarizeTargetMetrics({ intervalSec: 2, errors: [], samples: [
    { t: 0, values: { 'svc lag': 10 } }, { t: 2, values: { 'svc lag': 800 } },
    { t: 4, values: { 'svc lag': 2500 } }, { t: 6, values: { 'svc lag': 5000 } },
  ] });
  assert.equal(rising.metrics.find((x) => x.name === 'svc lag').risingLag, true);
  const stable = summarizeTargetMetrics({ intervalSec: 2, errors: [], samples: [
    { t: 0, values: { 'svc lag': 100 } }, { t: 2, values: { 'svc lag': 90 } }, { t: 4, values: { 'svc lag': 110 } },
  ] });
  assert.equal(stable.metrics.find((x) => x.name === 'svc lag').risingLag, false);
});
test('validateScenario: monitor.kafka требует command и groups; порог по "<группа> lag"', () => {
  const base = { baseUrl: 'http://localhost:8080', requests: [{ name: 'r', method: 'GET', path: '/x' }], load: { vus: 1, durationSec: 1 } };
  const noGroups = validateScenario({ ...base, monitor: { kafka: { command: ['kafka-consumer-groups'] } } });
  assert.ok(noGroups.errors.some((e) => /groups/.test(e)));
  const ok = validateScenario({ ...base, monitor: { kafka: { command: ['kafka-consumer-groups', '--bootstrap-server', 'x'], groups: ['g1'] }, thresholds: { 'g1 lag': { max: 100 } } } });
  assert.deepEqual(ok.errors, []);
  const typo = validateScenario({ ...base, monitor: { kafka: { command: ['k'], groups: ['g1'] }, thresholds: { 'g2 lag': { max: 100 } } } });
  assert.ok(typo.errors.some((e) => /нет такой метрики/.test(e)));
});

// ─── монитор consumer-lag Kafka (kind-agnostic monitor.kafka) ──
test('parseKafkaLag: суммирует LAG по партициям из вывода kafka-consumer-groups --describe', () => {
  const out = [
    'GROUP TOPIC PARTITION CURRENT-OFFSET LOG-END-OFFSET LAG CONSUMER-ID HOST CLIENT-ID',
    'g events 0 100 150 50 consumer-1 /10.0.0.1 c1',
    'g events 1 200 500 300 consumer-1 /10.0.0.1 c1',
  ].join('\n');
  const r = parseKafkaLag(out);
  assert.equal(r.lag, 350); // 50 + 300
  assert.equal(r.rows, 2);
});
test('parseKafkaLag: оффлайн-консьюмер (CONSUMER-ID=-) с реальным лагом считается; never-committed (LAG=-) пропускается', () => {
  const offline = parseKafkaLag('g t 0 100 600 500 - - -');
  assert.equal(offline.lag, 500); // главный backpressure-кейс
  const nolag = parseKafkaLag('g t 0 - - - - - -');
  assert.equal(nolag, null); // нет числового LAG → нет данных
  assert.equal(parseKafkaLag('GROUP TOPIC PARTITION ...\n(warning prose)'), null); // только заголовок/шум
  assert.equal(parseKafkaLag(''), null);
});
test('summarizeTargetMetrics: растущий consumer-lag → risingLag (backpressure); всплеск/стабильный — нет', () => {
  const rising = summarizeTargetMetrics({ intervalSec: 2, errors: [], samples: [
    { t: 0, values: { 'svc lag': 10 } }, { t: 2, values: { 'svc lag': 800 } }, { t: 4, values: { 'svc lag': 3000 } }, { t: 6, values: { 'svc lag': 6000 } },
  ] });
  assert.equal(rising.metrics.find((x) => x.name === 'svc lag').risingLag, true);
  const spike = summarizeTargetMetrics({ intervalSec: 2, errors: [], samples: [ // всплеск, потом спал — не backpressure
    { t: 0, values: { 'svc lag': 10 } }, { t: 2, values: { 'svc lag': 9000 } }, { t: 4, values: { 'svc lag': 20 } },
  ] });
  assert.equal(spike.metrics.find((x) => x.name === 'svc lag').risingLag, false);
  const stable = summarizeTargetMetrics({ intervalSec: 2, errors: [], samples: [
    { t: 0, values: { 'svc lag': 5000 } }, { t: 2, values: { 'svc lag': 4900 } }, { t: 4, values: { 'svc lag': 5100 } },
  ] });
  assert.equal(stable.metrics.find((x) => x.name === 'svc lag').risingLag, false);
});
test('validateScenario: monitor.kafka требует command и groups; порог по "<группа> lag" сверяется по имени', () => {
  const base = { baseUrl: 'http://localhost:8080', requests: [{ name: 'r', method: 'GET', path: '/x' }], load: { vus: 1, durationSec: 1 } };
  const noGroups = validateScenario({ ...base, monitor: { kafka: { command: ['kafka-consumer-groups'] } } });
  assert.ok(noGroups.errors.some((e) => /groups/.test(e)));
  const typo = validateScenario({ ...base, monitor: { kafka: { command: ['k', '--bootstrap-server', 'x'], groups: ['g1'] }, thresholds: { 'g2 lag': { max: 100 } } } });
  assert.ok(typo.errors.some((e) => /нет такой метрики/.test(e)), 'опечатка имени порога = ошибка');
  const ok = validateScenario({ ...base, monitor: { kafka: { command: ['k', '--bootstrap-server', 'x'], groups: ['g1'] }, thresholds: { 'g1 lag': { max: 100 } } } });
  assert.deepEqual(ok.errors, []);
});

// ─── assert-фаза (проверки корректности после нагрузки) ──
test('checkAssertExpect: value/minValue/maxValue/minRows/notEmpty', () => {
  assert.equal(checkAssertExpect('0', 1, { value: '0' }), true);
  assert.equal(checkAssertExpect('5', 1, { value: '0' }), false);
  assert.equal(checkAssertExpect('100', 1, { minValue: 50 }), true);
  assert.equal(checkAssertExpect('40', 1, { minValue: 50 }), false);
  assert.equal(checkAssertExpect('40', 1, { maxValue: 50 }), true);
  assert.equal(checkAssertExpect('abc', 1, { minValue: 1 }), false); // не число → провал
  assert.equal(checkAssertExpect('x', 3, { minRows: 2 }), true);
  assert.equal(checkAssertExpect('x', 1, { minRows: 2 }), false);
  assert.equal(checkAssertExpect('v', 1, { notEmpty: true }), true);
  assert.equal(checkAssertExpect('', 0, { notEmpty: true }), false);
});
test('validateScenario: assert требует kind sql/pipeline, sql+expect с РОВНО одним условием', () => {
  const sqlBase = { kind: 'sql', sql: { driver: 'psql', command: ['psql'] }, requests: [{ name: 'r', sql: 'select 1' }], load: { vus: 1, durationSec: 1 } };
  const ok = validateScenario({ ...sqlBase, assert: [{ name: 'c', sql: 'select count(*) from t', expect: { minValue: 1 } }] });
  assert.deepEqual(ok.errors, []);
  assert.equal(ok.errors.length, 0);
  const twoExpect = validateScenario({ ...sqlBase, assert: [{ name: 'c', sql: 'select 1', expect: { value: '0', minValue: 1 } }] });
  assert.ok(twoExpect.errors.some((e) => /РОВНО ОДНО/.test(e)));
  const noSql = validateScenario({ ...sqlBase, assert: [{ name: 'c', expect: { value: '0' } }] });
  assert.ok(noSql.errors.some((e) => /sql/.test(e)));
  // assert на HTTP = ошибка
  const http = validateScenario({ baseUrl: 'http://localhost:8080', requests: [{ name: 'r', method: 'GET', path: '/x' }], load: { vus: 1, durationSec: 1 }, assert: [{ name: 'c', sql: 'select 1', expect: { value: '0' } }] });
  assert.ok(http.errors.some((e) => /sql.*pipeline|только для/.test(e)));
});
