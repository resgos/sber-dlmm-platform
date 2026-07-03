# Клиенты loadgen: Node.js, Python, Java, Scala

Движок нагрузки **один** — `../bin/loadgen.mjs` (Node >= 18). Клиенты — тонкие обёртки
без зависимостей: запускают движок процессом, отдают результат в родном для языка виде.
Так тесты нагрузки встраиваются в CI любого проекта команды, а логика (перцентили,
вердикт, диагностика) не дублируется по языкам.

| Язык | Файл | Мин. версия | Как подключить |
|---|---|---|---|
| Node.js | `loadgen-client.mjs` | Node 18 | `import { run } from '.../clients/loadgen-client.mjs'` |
| Python | `loadgen_client.py` | Python 3.8 | скопировать файл в проект / добавить в PYTHONPATH |
| Java | `LoadgenClient.java` | Java 11 | скопировать в src/test/java (один класс, без пакета) |
| Scala | `LoadgenClient.scala` | Scala 2.12 | скопировать в src/test/scala |

Общий контракт всех клиентов:

- `probe(baseUrl, paths)` → доступна ли цель;
- `run(scenario, opts)` → полный JSON-результат движка (verdict, latencyMs, perRequest,
  checksSummary, paramImpact, hints, errorsDetail) + exitCode + консольный отчёт;
- exit 1 (кривой сценарий) и exit 3 (цель недоступна) → исключение;
- `verdict: FAIL` по порогам → НЕ исключение: assert'ите сами — это осознанная проверка теста.

## Примеры CI

### pytest

```python
from loadgen_client import run

def test_api_holds_load():
    r = run("load/pools.json", duration=60)
    assert r["verdict"] == "PASS", "\n".join(r["hints"])
    assert r["latencyMs"]["p95"] < 200
```

### JUnit 5

```java
@Test
void apiHoldsLoad() throws Exception {
    var lg = new LoadgenClient(Path.of("load-agent/bin/loadgen.mjs"));
    var r = lg.run(Path.of("load/pools.json"), new LoadgenClient.RunOptions().duration(60));
    assertTrue(r.passed, r.consoleOutput);
}
```

### scalatest / munit

```scala
test("api holds load") {
  val r = LoadgenClient.run(Paths.get("load/pools.json"),
    LoadgenClient.RunOptions(durationSec = Some(60)))
  assert(r.passed, r.consoleOutput)
}
```

### node:test

```js
import { run } from './load-agent/clients/loadgen-client.mjs';
test('api holds load', async () => {
  const r = await run('load/pools.json', { duration: 60 });
  assert.equal(r.verdict, 'PASS', r.hints.join('\n'));
});
```

## Замечания

- В CI движку нужен установленный Node.js (>= 18) — это единственное требование.
- Java: компилируйте с `-encoding UTF-8` (в javadoc русский текст): `javac -encoding UTF-8 LoadgenClient.java`.
  Клиенты Node/Python/Java проверены запуском; Scala-клиент написан по тому же контракту,
  но на машине автора scalac отсутствовал — при первом использовании прогоните компиляцию.
- Смоук перед полной нагрузкой: `run(..., smoke=True)` — быстрая функциональная проверка
  конфига, удобно в отдельном тесте перед нагрузочным.
- Предохранители движка действуют и через клиентов: запись требует `allowWrites` в сценарии
  **и** опцию allow_writes/allowWrites, внешние хосты — confirm_external/confirmExternal.
