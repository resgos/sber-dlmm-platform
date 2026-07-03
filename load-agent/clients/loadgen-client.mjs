/**
 * Клиент loadgen для Node.js >= 18 (программное использование из тестов/скриптов).
 * Без зависимостей. Движок — ../bin/loadgen.mjs, клиент только запускает процесс.
 *
 * Пример (node:test):
 *   import { run } from '../clients/loadgen-client.mjs';
 *   const r = await run('scenarios/pools.json', { duration: 30 });
 *   assert.equal(r.verdict, 'PASS');
 */
import { spawn } from 'node:child_process';
import { readFileSync, unlinkSync, existsSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { tmpdir } from 'node:os';
import { randomUUID } from 'node:crypto';

const DEFAULT_LOADGEN = join(dirname(fileURLToPath(import.meta.url)), '..', 'bin', 'loadgen.mjs');

export class LoadgenError extends Error {
  constructor(message, exitCode) { super(message); this.exitCode = exitCode; }
}

function exec(args, timeoutMs) {
  return new Promise((resolve, reject) => {
    const p = spawn(process.execPath, args, { stdio: ['ignore', 'pipe', 'pipe'] });
    let out = '';
    p.stdout.on('data', (d) => { out += d; });
    p.stderr.on('data', (d) => { out += d; });
    const t = setTimeout(() => { p.kill(); reject(new LoadgenError(`loadgen не завершился за ${timeoutMs}мс`, -1)); }, timeoutMs);
    p.on('close', (code) => { clearTimeout(t); resolve({ code, out }); });
    p.on('error', (e) => { clearTimeout(t); reject(e); });
  });
}

/** Проверка доступности цели: { reachable, output } */
export async function probe(baseUrl, paths = [], { loadgen = DEFAULT_LOADGEN, timeoutMs = 60_000 } = {}) {
  const { code, out } = await exec([loadgen, 'probe', baseUrl, ...paths], timeoutMs);
  return { reachable: code === 0, output: out };
}

/** Валидация сценария: { ok, output } */
export async function validate(scenario, { loadgen = DEFAULT_LOADGEN, timeoutMs = 60_000 } = {}) {
  const { code, out } = await exec([loadgen, 'validate', String(scenario)], timeoutMs);
  return { ok: code === 0, output: out };
}

/**
 * Прогон нагрузки. Возвращает полный JSON-результат движка + exitCode + consoleOutput.
 * Бросает LoadgenError при кривом сценарии (exit 1) и недоступной цели (exit 3);
 * вердикт FAIL по порогам исключение НЕ бросает — проверяйте result.verdict.
 */
export async function run(scenario, opts = {}) {
  const {
    smoke = false, vus, duration, baseUrl,
    allowWrites = false, confirmExternal = false,
    out, loadgen = DEFAULT_LOADGEN, timeoutMs = 1_200_000,
  } = opts;
  const outPath = out || join(tmpdir(), `loadgen-${randomUUID()}.json`);
  const args = [loadgen, 'run', String(scenario), '--quiet', '--out', outPath];
  if (smoke) args.push('--smoke');
  if (vus !== undefined) args.push('--vus', String(vus));
  if (duration !== undefined) args.push('--duration', String(duration));
  if (baseUrl !== undefined) args.push('--base-url', baseUrl);
  if (allowWrites) args.push('--allow-writes');
  if (confirmExternal) args.push('--confirm-external');

  const { code, out: console_ } = await exec(args, timeoutMs);
  if (code === 1 || code === 3) throw new LoadgenError(console_.trim(), code);
  try {
    const result = JSON.parse(readFileSync(outPath, 'utf8'));
    result.exitCode = code;
    result.consoleOutput = console_;
    return result;
  } finally {
    if (!out && existsSync(outPath)) unlinkSync(outPath);
  }
}
