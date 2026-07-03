import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Клиент loadgen для Java 11+. Без зависимостей.
 *
 * <p>Тонкая обёртка над {@code load-agent/bin/loadgen.mjs}: движок нагрузки один и
 * оттестирован, клиент запускает его процессом и отдаёт JSON-результат строкой
 * (парсьте своим Jackson/Gson, либо используйте {@link Result#verdict} напрямую).
 * Требуется Node.js >= 18 в PATH.
 *
 * <p>Пример (JUnit 5):
 * <pre>{@code
 * LoadgenClient lg = new LoadgenClient(Paths.get("load-agent/bin/loadgen.mjs"));
 * LoadgenClient.Result r = lg.run(Paths.get("scenarios/pools.json"),
 *         new LoadgenClient.RunOptions().duration(30));
 * assertEquals("PASS", r.verdict, r.consoleOutput);
 * }</pre>
 */
public final class LoadgenClient {

    /** Ошибка конфигурации сценария/CLI (exit 1) или недоступная цель (exit 3). */
    public static final class LoadgenException extends RuntimeException {
        public final int exitCode;
        LoadgenException(int exitCode, String message) {
            super(message);
            this.exitCode = exitCode;
        }
    }

    /** Итог прогона. Вердикт по порогам не бросает исключений — проверяйте {@link #passed}. */
    public static final class Result {
        /** 0 = PASS, 2 = FAIL (пороги нарушены). */
        public final int exitCode;
        /** "PASS" или "FAIL". */
        public final String verdict;
        public final boolean passed;
        /** Полный JSON-результат движка (perRequest, checksSummary, paramImpact, hints...). */
        public final String rawJson;
        /** Человекочитаемый консольный отчёт движка. */
        public final String consoleOutput;

        Result(int exitCode, String verdict, String rawJson, String consoleOutput) {
            this.exitCode = exitCode;
            this.verdict = verdict;
            this.passed = "PASS".equals(verdict);
            this.rawJson = rawJson;
            this.consoleOutput = consoleOutput;
        }
    }

    /** Опции прогона; все необязательные. */
    public static final class RunOptions {
        boolean smoke;
        Integer vus;
        Integer durationSec;
        String baseUrl;
        boolean allowWrites;
        boolean confirmExternal;
        String nodeCommand = "node";
        long timeoutSec = 1200;

        public RunOptions smoke(boolean v) { this.smoke = v; return this; }
        public RunOptions vus(int v) { this.vus = v; return this; }
        public RunOptions duration(int seconds) { this.durationSec = seconds; return this; }
        public RunOptions baseUrl(String v) { this.baseUrl = v; return this; }
        public RunOptions allowWrites(boolean v) { this.allowWrites = v; return this; }
        public RunOptions confirmExternal(boolean v) { this.confirmExternal = v; return this; }
        public RunOptions nodeCommand(String v) { this.nodeCommand = v; return this; }
        public RunOptions timeoutSec(long v) { this.timeoutSec = v; return this; }
    }

    private static final Pattern VERDICT = Pattern.compile("\"verdict\"\\s*:\\s*\"(PASS|FAIL)\"");

    private final Path loadgenMjs;

    public LoadgenClient(Path loadgenMjs) {
        if (!Files.exists(loadgenMjs)) {
            throw new IllegalArgumentException("loadgen.mjs не найден: " + loadgenMjs.toAbsolutePath());
        }
        this.loadgenMjs = loadgenMjs;
    }

    /** Проверка доступности цели: true = REACHABLE. */
    public boolean probe(String baseUrl, String... paths) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>(List.of("node", loadgenMjs.toString(), "probe", baseUrl));
        cmd.addAll(List.of(paths));
        return exec(cmd, 60).exitCode == 0;
    }

    public Result run(Path scenario, RunOptions o) throws IOException, InterruptedException {
        if (o == null) o = new RunOptions();
        Path out = Paths.get(System.getProperty("java.io.tmpdir"), "loadgen-" + UUID.randomUUID() + ".json");
        List<String> cmd = new ArrayList<>(List.of(
                o.nodeCommand, loadgenMjs.toString(), "run", scenario.toString(),
                "--quiet", "--out", out.toString()));
        if (o.smoke) cmd.add("--smoke");
        if (o.vus != null) { cmd.add("--vus"); cmd.add(String.valueOf(o.vus)); }
        if (o.durationSec != null) { cmd.add("--duration"); cmd.add(String.valueOf(o.durationSec)); }
        if (o.baseUrl != null) { cmd.add("--base-url"); cmd.add(o.baseUrl); }
        if (o.allowWrites) cmd.add("--allow-writes");
        if (o.confirmExternal) cmd.add("--confirm-external");

        Exec e = exec(cmd, o.timeoutSec);
        if (e.exitCode == 1 || e.exitCode == 3) throw new LoadgenException(e.exitCode, e.output);
        try {
            String json = Files.readString(out, StandardCharsets.UTF_8);
            Matcher m = VERDICT.matcher(json);
            String verdict = m.find() ? m.group(1) : "UNKNOWN";
            return new Result(e.exitCode, verdict, json, e.output);
        } finally {
            Files.deleteIfExists(out);
        }
    }

    private static final class Exec {
        final int exitCode;
        final String output;
        Exec(int exitCode, String output) { this.exitCode = exitCode; this.output = output; }
    }

    private static Exec exec(List<String> cmd, long timeoutSec) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        // вывод читается в отдельном потоке: readAllBytes на текущем потоке заблокировал бы
        // waitFor и сделал бы таймаут мёртвым кодом при зависшем движке
        StringBuilder sb = new StringBuilder();
        Thread reader = new Thread(() -> {
            try (java.io.Reader r = new java.io.InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8)) {
                char[] buf = new char[8192];
                int n;
                while ((n = r.read(buf)) != -1) {
                    synchronized (sb) { sb.append(buf, 0, n); }
                }
            } catch (IOException ignored) {
                // поток закрыт при destroyForcibly — вывод уже собран частично
            }
        }, "loadgen-output-reader");
        reader.setDaemon(true);
        reader.start();
        if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            reader.join(2000);
            String partial;
            synchronized (sb) { partial = sb.toString(); }
            throw new LoadgenException(-1, "loadgen не завершился за " + timeoutSec + " сек. Частичный вывод:\n" + partial);
        }
        reader.join(5000);
        synchronized (sb) { return new Exec(p.exitValue(), sb.toString()); }
    }
}
