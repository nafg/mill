package mill.integration

import ch.epfl.scala.{bsp4j => b}
import com.google.gson.{Gson, GsonBuilder}
import coursier.cache.CacheDefaults
import mill.api.BuildInfo
import mill.bsp.Constants
import mill.testrunner.TestRunnerUtils
import org.eclipse.{lsp4j => l}
import org.eclipse.lsp4j.jsonrpc.services.JsonRequest

import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.{CompletableFuture, ExecutorService, Executors, ThreadFactory}

import scala.jdk.CollectionConverters._
import scala.reflect.ClassTag

object BspServerTestUtil {

  /**
   * Set to true when running tests to update the snapshots on the disk. Do not forget to set it back to
   * false after that.
   */
  val updateSnapshots = false

  private[mill] def bsp4jVersion: String = sys.props.getOrElse("BSP4J_VERSION", ???)

  trait DummyBuildClient extends b.BuildClient {
    def onBuildLogMessage(params: b.LogMessageParams): Unit = ()
    def onBuildPublishDiagnostics(params: b.PublishDiagnosticsParams): Unit = ()
    def onBuildShowMessage(params: b.ShowMessageParams): Unit = ()
    def onBuildTargetDidChange(params: b.DidChangeBuildTarget): Unit = ()
    def onBuildTaskFinish(params: b.TaskFinishParams): Unit = ()
    def onBuildTaskProgress(params: b.TaskProgressParams): Unit = ()
    def onBuildTaskStart(params: b.TaskStartParams): Unit = ()
    def onRunPrintStderr(params: b.PrintParams): Unit = ()
    def onRunPrintStdout(params: b.PrintParams): Unit = ()
  }

  object DummyBuildClient extends DummyBuildClient

  val gson: Gson = new GsonBuilder().setPrettyPrinting().create()
  def compareWithGsonSnapshot[T: ClassTag](
      value: T,
      snapshotPath: os.Path,
      normalizedLocalValues: Seq[(String, String)] = Nil
  ): Unit = {

    def normalizeLocalValues(input: String, inverse: Boolean = false): String =
      normalizedLocalValues.foldLeft(input) {
        case (input0, (from0, to0)) =>
          val (from, to) = if (inverse) (to0, from0) else (from0, to0)
          input0.replace(from, to)
      }.replaceAll("\"javaHome\": \"[^\"]+\"", "\"javaHome\": \"java-home\"")

    // This can be false only when generating test data for the first time.
    // In that case, updateSnapshots needs to be true, so that we write test data on disk.
    val snapshotExists = os.exists(snapshotPath)
    val expectedValueOpt = Option.when(snapshotExists) {
      gson.fromJson(
        normalizeLocalValues(os.read(snapshotPath), inverse = true),
        implicitly[ClassTag[T]].runtimeClass
      )
    }

    lazy val jsonStr = normalizeLocalValues(
      gson.toJson(
        value,
        implicitly[ClassTag[T]].runtimeClass
      )
    )

    lazy val expectedJsonStr = expectedValueOpt match {
      case Some(v) => normalizeLocalValues(gson.toJson(v, implicitly[ClassTag[T]].runtimeClass))
      case None => ""
    }
    if (updateSnapshots) {
      System.err.println(if (snapshotExists) s"Updating $snapshotPath"
      else s"Writing $snapshotPath")
      os.write.over(snapshotPath, jsonStr, createFolders = true)
    } else {
      Predef.assert(
        jsonStr == expectedJsonStr,
        if (snapshotExists) {
          val diff = os.call((
            // "git",
            "diff",
            "-u",
            os.temp(expectedJsonStr, suffix = s"${snapshotPath.last}-expectedJsonStr"),
            os.temp(jsonStr, suffix = s"${snapshotPath.last}-jsonStr")
          ))
          s"""Error: value differs from snapshot in $snapshotPath
             |
             |You might want to set BspServerTestUtil.updateSnapshots to true,
             |run this test again, and commit the updated test data files.
             |
             |$diff
             |""".stripMargin
        } else s"Error: no snapshot found at $snapshotPath"
      )
    }
  }

  def compareLogWithSnapshot(
      log: String,
      snapshotPath: os.Path,
      ignoreLine: String => Boolean = _ => false
  ): Unit = {

    val logLines = log.linesIterator.filterNot(ignoreLine).toVector
    val snapshotLinesOpt = Option.when(os.exists(snapshotPath))(os.read.lines(snapshotPath))

    val matches = snapshotLinesOpt match {
      case Some(snapshotLines) =>
        if (snapshotLines.length == logLines.length)
          snapshotLines.iterator
            .zip(logLines.iterator)
            .zipWithIndex
            .map {
              case ((snapshotLine, logLine), lineIdx) =>
                val cmp = TestRunnerUtils.matchesGlob(snapshotLine)
                cmp(logLine) || {
                  System.err.println(s"Line ${lineIdx + 1} differs:")
                  System.err.println(s"  Expected: $snapshotLine")
                  System.err.println(s"  Got: $logLine")
                  false
                }
            }
            .foldLeft(true)(_ && _)
        else {
          System.err.println(s"Expected ${snapshotLines.length} lines, got ${logLines.length}")
          false
        }
      case None =>
        System.err.println(s"$snapshotPath not found")
        false
    }

    if (updateSnapshots) {
      if (!matches) {
        System.err.println(s"Updating $snapshotPath")
        os.write.over(snapshotPath, logLines.mkString(System.lineSeparator()), createFolders = true)
      }
    } else
      assert(matches)
  }

  val bspJsonrpcPool: ExecutorService = Executors.newCachedThreadPool(
    new ThreadFactory {
      val counter = new AtomicInteger
      def newThread(runnable: Runnable): Thread = {
        val t = new Thread(runnable, s"mill-bsp-integration-${counter.incrementAndGet()}")
        t.setDaemon(true)
        t
      }
    }
  )

  trait MillBuildServer extends b.BuildServer with b.JvmBuildServer
      with b.JavaBuildServer with b.ScalaBuildServer {
    @JsonRequest("millTest/loggingTest")
    def loggingTest(): CompletableFuture[Object]
  }

  def withBspServer[T](
      workspacePath: os.Path,
      millTestSuiteEnv: Map[String, String],
      bspLog: Option[(Array[Byte], Int) => Unit] = None,
      client: b.BuildClient = DummyBuildClient
  )(f: (MillBuildServer, b.InitializeBuildResult) => T): T = {

    val bspMetadataFile = workspacePath / Constants.bspDir / s"${Constants.serverName}.json"
    assert(os.exists(bspMetadataFile))
    val contents = os.read(bspMetadataFile)
    assert(
      !contents.contains("--debug"),
      contents.contains(s""""bspVersion":"$bsp4jVersion"""")
    )

    val outputOnErrorOnly = System.getenv("CI") != null

    val contentsJson = ujson.read(contents)
    val bspCommand = contentsJson("argv").arr.map(_.str)
    val stderr = new ByteArrayOutputStream
    val proc = os.proc(bspCommand).spawn(
      cwd = workspacePath,
      stderr =
        if (bspLog.isDefined || outputOnErrorOnly)
          os.ProcessOutput { (bytes, len) =>
            if (outputOnErrorOnly)
              stderr.write(bytes, 0, len)
            else
              System.err.write(bytes, 0, len)
            for (f <- bspLog)
              f(bytes, len)
          }
        else os.Inherit,
      env = millTestSuiteEnv
    )

    var success = false
    try {
      val launcher = new l.jsonrpc.Launcher.Builder[MillBuildServer]
        .setExecutorService(bspJsonrpcPool)
        .setInput(proc.stdout.wrapped)
        .setOutput(proc.stdin.wrapped)
        .setRemoteInterface(classOf[MillBuildServer])
        .setLocalService(client)
        .setExceptionHandler { t =>
          System.err.println(s"Error during LSP processing: $t")
          t.printStackTrace(System.err)
          l.jsonrpc.RemoteEndpoint.DEFAULT_EXCEPTION_HANDLER.apply(t)
        }
        .create()

      launcher.startListening()

      val buildServer = launcher.getRemoteProxy()

      val initRes = buildServer.buildInitialize(
        new b.InitializeBuildParams(
          "Mill Integration",
          BuildInfo.millVersion,
          b.Bsp4j.PROTOCOL_VERSION,
          workspacePath.toNIO.toUri.toASCIIString,
          new b.BuildClientCapabilities(List("java", "scala", "kotlin").asJava)
        )
      ).get()

      val value =
        try f(buildServer, initRes)
        finally {
          buildServer.buildShutdown().get()
          buildServer.onBuildExit()
        }
      success = true
      value
    } finally {
      try {
        proc.stdin.close()
        proc.stdout.close()

        proc.join(30000L)
      } finally {
        if (!success && outputOnErrorOnly) {
          System.err.println(" == BSP server output ==")
          System.err.write(stderr.toByteArray)
          System.err.println(" == end of BSP server output ==")
        }
      }
    }
  }

  lazy val millWorkspace: os.Path = {
    val value = Option(System.getenv("MILL_PROJECT_ROOT")).getOrElse(???)
    os.Path(value)
  }

  def normalizeLocalValuesForTesting(
      workspacePath: os.Path,
      coursierCache: os.Path = os.Path(CacheDefaults.location),
      javaHome: os.Path = os.Path(sys.props("java.home")),
      javaVersion: String = sys.props("java.version")
  ): Seq[(String, String)] =
    Seq(
      workspacePath.toNIO.toUri.toASCIIString.stripSuffix("/") -> "file:///workspace",
      coursierCache.toNIO.toUri.toASCIIString -> "file:///coursier-cache/",
      millWorkspace.toNIO.toUri.toASCIIString -> "file:///mill-workspace/",
      javaHome.toNIO.toUri.toASCIIString.stripSuffix("/") -> "file:///java-home",
      os.home.toNIO.toUri.toASCIIString.stripSuffix("/") -> "file:///user-home",
      ("\"" + javaVersion + "\"") -> "\"<java-version>\"",
      workspacePath.toString -> "/workspace",
      coursierCache.toString -> "/coursier-cache",
      millWorkspace.toString -> "/mill-workspace",
      os.home.toString -> "/user-home"
    )
}
