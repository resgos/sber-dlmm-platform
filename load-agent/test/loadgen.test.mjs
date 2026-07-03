/**
 * Регрессионные тесты ядра loadgen. Запуск: node --test  (из папки load-agent)
 * Только чистые функции — без сети. Ловят регрессии в парсинге, проверках, захвате и валидации.
 */
import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  extractPath, renderTemplate, parseCsv, normalizePath, isIdSegment,
  evaluateResponse, applyCaptures, validateScenario, makeStats, mergeStats, serializeStats,
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

// ─── makeStats / mergeStats + flowStats ──
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
