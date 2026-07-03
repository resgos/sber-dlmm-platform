import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import java.util.UUID
import scala.collection.mutable.ListBuffer
import scala.sys.process._

/**
 * Клиент loadgen для Scala 2.12+/3. Без зависимостей.
 *
 * Тонкая обёртка над load-agent/bin/loadgen.mjs: движок нагрузки один и оттестирован,
 * клиент запускает его процессом и отдаёт JSON-результат строкой. Node.js >= 18 в PATH.
 *
 * Пример (munit/scalatest):
 * {{{
 * val r = LoadgenClient.run(Paths.get("scenarios/pools.json"), LoadgenClient.RunOptions(durationSec = Some(30)))
 * assert(r.passed, r.consoleOutput)
 * }}}
 */
object LoadgenClient {

  /** exit 1 = кривой сценарий/CLI, exit 3 = цель недоступна. */
  final case class LoadgenException(exitCode: Int, output: String)
      extends RuntimeException(s"loadgen exit=$exitCode:\n$output")

  /** verdict по порогам не бросает исключений — проверяйте passed. */
  final case class Result(exitCode: Int, verdict: String, rawJson: String, consoleOutput: String) {
    def passed: Boolean = verdict == "PASS"
  }

  final case class RunOptions(
      smoke: Boolean = false,
      vus: Option[Int] = None,
      durationSec: Option[Int] = None,
      baseUrl: Option[String] = None,
      allowWrites: Boolean = false,
      confirmExternal: Boolean = false,
      nodeCommand: String = "node"
  )

  private val VerdictRe = """"verdict"\s*:\s*"(PASS|FAIL)"""".r

  /** Проверка доступности цели: true = REACHABLE. */
  def probe(loadgenMjs: Path, baseUrl: String, paths: Seq[String] = Nil, nodeCommand: String = "node"): Boolean = {
    val (code, _) = exec(Seq(nodeCommand, loadgenMjs.toString, "probe", baseUrl) ++ paths)
    code == 0
  }

  def run(scenario: Path, opts: RunOptions = RunOptions(), loadgenMjs: Path = defaultLoadgen): Result = {
    val out = Paths.get(System.getProperty("java.io.tmpdir"), s"loadgen-${UUID.randomUUID()}.json")
    val cmd = ListBuffer(opts.nodeCommand, loadgenMjs.toString, "run", scenario.toString, "--quiet", "--out", out.toString)
    if (opts.smoke) cmd += "--smoke"
    opts.vus.foreach(v => cmd ++= Seq("--vus", v.toString))
    opts.durationSec.foreach(d => cmd ++= Seq("--duration", d.toString))
    opts.baseUrl.foreach(u => cmd ++= Seq("--base-url", u))
    if (opts.allowWrites) cmd += "--allow-writes"
    if (opts.confirmExternal) cmd += "--confirm-external"

    val (code, output) = exec(cmd.toSeq)
    if (code == 1 || code == 3) throw LoadgenException(code, output)
    try {
      val json = new String(Files.readAllBytes(out), StandardCharsets.UTF_8)
      val verdict = VerdictRe.findFirstMatchIn(json).map(_.group(1)).getOrElse("UNKNOWN")
      Result(code, verdict, json, output)
    } finally Files.deleteIfExists(out)
  }

  /** clients/ и bin/ лежат рядом внутри load-agent/ */
  def defaultLoadgen: Path =
    Paths.get(sys.env.getOrElse("LOADGEN_MJS", "load-agent/bin/loadgen.mjs"))

  private def exec(cmd: Seq[String]): (Int, String) = {
    // читаем потоки сами с явным UTF-8: ProcessLogger декодирует в кодировке JVM по умолчанию,
    // что на Windows (JVM <= 17, windows-1251) превращает русский вывод движка в мод͏жибейк
    val buf = new StringBuilder
    def slurp(in: java.io.InputStream): Unit = {
      val r = new java.io.BufferedReader(new java.io.InputStreamReader(in, StandardCharsets.UTF_8))
      try {
        var line = r.readLine()
        while (line != null) {
          buf.synchronized { buf.append(line).append('\n') }
          line = r.readLine()
        }
      } finally r.close()
    }
    val proc = Process(cmd).run(new ProcessIO(_.close(), slurp, slurp))
    val code = proc.exitValue()
    (code, buf.synchronized(buf.toString))
  }
}
