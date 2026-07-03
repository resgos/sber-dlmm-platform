"""Клиент loadgen для Python >= 3.8. Без зависимостей (stdlib).

Тонкая обёртка над load-agent/bin/loadgen.mjs: движок нагрузки один и оттестирован,
клиент только запускает его как процесс и разбирает JSON-результат.
Требуется Node.js >= 18 в PATH (или передайте node="путь/к/node").

Пример (pytest):

    from loadgen_client import run, LoadgenError

    def test_pools_endpoint_holds_load():
        result = run("scenarios/pools.json", duration=30)
        assert result["verdict"] == "PASS", result["hints"]
        assert result["latencyMs"]["p95"] < 200

Поля результата — см. README движка: verdict, latencyMs{p50..max}, rps, errorRatePct,
perRequest[], checksSummary[], paramImpact[], hints[], errorsDetail[].
"""
from __future__ import annotations

import json
import os
import subprocess
import tempfile
import uuid

__all__ = ["run", "probe", "validate", "LoadgenError", "TargetUnreachableError"]

_DEFAULT_LOADGEN = os.path.normpath(
    os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "bin", "loadgen.mjs")
)


class LoadgenError(RuntimeError):
    """Ошибка конфигурации сценария или использования CLI (exit code 1)."""


class TargetUnreachableError(LoadgenError):
    """Цель недоступна или pre-flight (логин/vars) не прошёл (exit code 3)."""


def _exec(args, timeout):
    return subprocess.run(args, capture_output=True, text=True, encoding="utf-8", timeout=timeout)


def probe(base_url, paths=(), *, node="node", loadgen=None, timeout=60):
    """Проверка доступности цели. Возвращает dict {reachable: bool, output: str}."""
    proc = _exec([node, loadgen or _DEFAULT_LOADGEN, "probe", base_url, *paths], timeout)
    return {"reachable": proc.returncode == 0, "output": proc.stdout + proc.stderr}


def validate(scenario, *, node="node", loadgen=None, timeout=60):
    """Валидация сценария. Возвращает dict {ok: bool, output: str}."""
    proc = _exec([node, loadgen or _DEFAULT_LOADGEN, "validate", str(scenario)], timeout)
    return {"ok": proc.returncode == 0, "output": proc.stdout + proc.stderr}


def run(
    scenario,
    *,
    smoke=False,
    vus=None,
    duration=None,
    base_url=None,
    allow_writes=False,
    confirm_external=False,
    out=None,
    node="node",
    loadgen=None,
    timeout=1200,
):
    """Прогон нагрузки. Возвращает dict с полным JSON-результатом движка.

    Дополнительно кладёт в результат exitCode (0 = PASS, 2 = FAIL) и consoleOutput.
    Бросает LoadgenError (кривой сценарий) или TargetUnreachableError (цель лежит).
    Вердикт по порогам НЕ бросает исключение — проверяйте result["verdict"] сами.
    """
    out_path = out or os.path.join(tempfile.gettempdir(), f"loadgen-{uuid.uuid4().hex}.json")
    cmd = [node, loadgen or _DEFAULT_LOADGEN, "run", str(scenario), "--quiet", "--out", out_path]
    if smoke:
        cmd.append("--smoke")
    if vus is not None:
        cmd += ["--vus", str(vus)]
    if duration is not None:
        cmd += ["--duration", str(duration)]
    if base_url is not None:
        cmd += ["--base-url", base_url]
    if allow_writes:
        cmd.append("--allow-writes")
    if confirm_external:
        cmd.append("--confirm-external")

    proc = _exec(cmd, timeout)
    console = proc.stdout + proc.stderr
    if proc.returncode == 1:
        raise LoadgenError(console.strip())
    if proc.returncode == 3:
        raise TargetUnreachableError(console.strip())

    try:
        with open(out_path, encoding="utf-8") as f:
            result = json.load(f)
    except FileNotFoundError as e:
        raise LoadgenError(f"Движок не записал результат ({out_path}). Вывод:\n{console}") from e
    finally:
        if out is None and os.path.exists(out_path):
            os.unlink(out_path)

    result["exitCode"] = proc.returncode
    result["consoleOutput"] = console
    return result
