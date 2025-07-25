package mill.meta

import mill.*
import mill.api.Result
import mill.api.internal.internal
import mill.constants.CodeGenConstants.buildFileExtensions
import mill.constants.OutFiles.*
import mill.define.{PathRef, Discover, RootModule0, Task}
import mill.scalalib.{Dep, DepSyntax, Lib, ScalaModule}
import mill.scalalib.api.{CompilationResult, Versions}
import mill.util.BuildInfo
import mill.api.internal.MillScalaParser
import mill.define.JsonFormatters.given

import scala.jdk.CollectionConverters.ListHasAsScala

/**
 * Mill module for pre-processing a Mill `build.mill` and related files and then
 * compiling them as a normal [[ScalaModule]]. Parses `build.mill`, walks any
 * `import $file`s, wraps the script files to turn them into valid Scala code
 * and then compiles them with the `mvnDeps` extracted from the `//| mvnDeps:`
 * calls within the scripts.
 */
@internal
trait MillBuildRootModule()(implicit
    rootModuleInfo: RootModule0.Info
) extends ScalaModule {
  override def bspDisplayName0: String = rootModuleInfo
    .projectRoot
    .relativeTo(rootModuleInfo.topLevelProjectRoot)
    .segments
    .++(super.bspDisplayName0.split("/"))
    .mkString("/")

  override def moduleDir: os.Path = rootModuleInfo.projectRoot / os.up / millBuild
  override def intellijModulePath: os.Path = moduleDir / os.up

  override def scalaVersion: T[String] = BuildInfo.scalaVersion

  val scriptSourcesPaths = mill.define.BuildCtx.withFilesystemCheckerDisabled {
    FileImportGraph
      .walkBuildFiles(rootModuleInfo.projectRoot / os.up, rootModuleInfo.output)
      .sorted
  }

  /**
   * All script files (that will get wrapped later)
   * @see [[generatedSources]]
   */
  def scriptSources: T[Seq[PathRef]] = Task.Sources(
    scriptSourcesPaths* // Ensure ordering is deterministic
  )

  def parseBuildFiles: T[FileImportGraph] = Task {
    scriptSources()
    mill.define.BuildCtx.withFilesystemCheckerDisabled {
      MillBuildRootModule.parseBuildFiles(MillScalaParser.current.value, rootModuleInfo)
    }
  }

  def cliImports: T[Seq[String]] = Task.Input {
    val imports = CliImports.value
    if (imports.nonEmpty) {
      Task.log.debug(s"Using cli-provided runtime imports: ${imports.mkString(", ")}")
    }
    imports
  }

  override def mandatoryMvnDeps = Task {
    Seq(mvn"com.lihaoyi::mill-libs:${Versions.millVersion}") ++
      // only include mill-runner for meta-builds
      Option.when(rootModuleInfo.projectRoot / os.up != rootModuleInfo.topLevelProjectRoot) {
        mvn"com.lihaoyi::mill-runner-meta:${Versions.millVersion}"
      }
  }

  override def runMvnDeps = Task {
    val imports = cliImports()
    val ivyImports = imports.collect {
      // compat with older Mill-versions
      case s"ivy:$rest" => rest
      case s"mvn:$rest" => rest
    }
    MillIvy.processMillMvnDepsignature(ivyImports).map(mill.scalalib.Dep.parse) ++
      // Needed at runtime to instantiate a `mill.eval.EvaluatorImpl` in the `build.mill`,
      // classloader but should not be available for users to compile against
      Seq(mvn"com.lihaoyi::mill-core-eval:${Versions.millVersion}")

  }

  override def platformSuffix: T[String] = s"_mill${BuildInfo.millBinPlatform}"

  override def generatedSources: T[Seq[PathRef]] = Task {
    generatedScriptSources().support
  }

  /**
   * Additional script files, we generate, since not all Mill source
   * files (e.g. `.sc` and `.mill`) can be fed to the compiler as-is.
   *
   * The `wrapped` files aren't supposed to appear under [[generatedSources]] and [[allSources]],
   * since they are derived from [[sources]] and would confuse any further tooling like IDEs.
   */
  def generatedScriptSources: T[(wrapped: Seq[PathRef], support: Seq[PathRef])] = Task {
    val wrapped = Task.dest / "wrapped"
    val support = Task.dest / "support"

    val parsed = parseBuildFiles()
    if (parsed.errors.nonEmpty) Task.fail(parsed.errors.mkString("\n"))
    else {
      CodeGen.generateWrappedAndSupportSources(
        rootModuleInfo.projectRoot / os.up,
        parsed.seenScripts,
        wrapped,
        support,
        rootModuleInfo.topLevelProjectRoot,
        rootModuleInfo.output,
        MillScalaParser.current.value
      )
      (wrapped = Seq(PathRef(wrapped)), support = Seq(PathRef(support)))
    }
  }

  def millBuildRootModuleResult = Task {
    Tuple3(
      runClasspath(),
      compile().classes,
      codeSignatures()
    )
  }

  def codeSignatures: T[Map[String, Int]] = Task(persistent = true) {
    os.remove.all(Task.dest / "previous")
    if (os.exists(Task.dest / "current"))
      os.move.over(Task.dest / "current", Task.dest / "previous")
    val debugEnabled = Task.log.debugEnabled
    val codesig = mill.codesig.CodeSig
      .compute(
        classFiles = os.walk(compile().classes.path).filter(_.ext == "class"),
        upstreamClasspath = compileClasspath().toSeq.map(_.path),
        ignoreCall = { (callSiteOpt, calledSig) =>
          // We can ignore all calls to methods that look like Targets when traversing
          // the call graph. We can do this because we assume `def` Targets are pure,
          // and so any changes in their behavior will be picked up by the runtime build
          // graph evaluator without needing to be accounted for in the post-compile
          // bytecode callgraph analysis.
          def isSimpleTarget(desc: mill.codesig.JvmModel.Desc) =
            (desc.ret.pretty == classOf[Task.Simple[?]].getName ||
              desc.ret.pretty == classOf[Worker[?]].getName) &&
              desc.args.isEmpty

          // We avoid ignoring method calls that are simple trait forwarders, because
          // we need the trait forwarders calls to be counted in order to wire up the
          // method definition that a Target is associated with during evaluation
          // (e.g. `myModuleObject.myTarget`) with its implementation that may be defined
          // somewhere else (e.g. `trait MyModuleTrait{ def myTarget }`). Only that one
          // step is necessary, after that the runtime build graph invalidation logic can
          // take over
          def isForwarderCallsiteOrLambda =
            callSiteOpt.nonEmpty && {
              val callSiteSig = callSiteOpt.get.sig

              (callSiteSig.name == (calledSig.name + "$") &&
                callSiteSig.static &&
                callSiteSig.desc.args.size == 1)
              || (
                // In Scala 3, lambdas are implemented by private instance methods,
                // not static methods, so they fall through the crack of "isSimpleTarget".
                // Here make the assumption that a zero-arg lambda called from a simpleTarget,
                // should in fact be tracked. e.g. see `integration.invalidation[codesig-hello]`,
                // where the body of the `def foo` target is a zero-arg lambda i.e. the argument
                // of `Cacher.cachedTarget`.
                // To be more precise I think ideally we should capture more information in the signature
                isSimpleTarget(callSiteSig.desc) && calledSig.name.contains("$anonfun")
              )
            }

          // We ignore Commands for the same reason as we ignore Targets, and also because
          // their implementations get gathered up all the via the `Discover` macro, but this
          // is primarily for use as external entrypoints and shouldn't really be counted as
          // part of the `millbuild.build#<init>` transitive call graph they would normally
          // be counted as
          def isCommand =
            calledSig.desc.ret.pretty == classOf[Command[?]].getName

          // Skip calls to `millDiscover`. `millDiscover` is bundled as part of `RootModule` for
          // convenience, but it should really never be called by any normal Mill module/task code,
          // and is only used by downstream code in `mill.eval`/`mill.resolve`. Thus although CodeSig's
          // conservative analysis considers potential calls from `build_.package_$#<init>` to
          // `millDiscover()`, we can safely ignore that possibility
          def isMillDiscover =
            calledSig.name == "millDiscover$lzyINIT1" ||
              calledSig.name == "millDiscover" ||
              callSiteOpt.exists(_.sig.name == "millDiscover")

          (isSimpleTarget(calledSig.desc) && !isForwarderCallsiteOrLambda) ||
          isCommand ||
          isMillDiscover
        },
        logger = new mill.codesig.Logger(
          Task.dest / "current",
          Option.when(debugEnabled)(Task.dest / "current")
        ),
        prevTransitiveCallGraphHashesOpt = () =>
          Option.when(os.exists(Task.dest / "previous/transitiveCallGraphHashes0.json"))(
            upickle.default.read[Map[String, Int]](
              os.read.stream(Task.dest / "previous/transitiveCallGraphHashes0.json")
            )
          )
      )

    codesig.transitiveCallGraphHashes
  }

  /**
   * All mill build source files.
   * These files are the inputs but not necessarily the same files we feed to the compiler,
   * since we need to process `.mill` files and generate additional Scala files from it.
   */
  override def sources: T[Seq[PathRef]] = Task {
    scriptSources() ++ super.sources()
  }

  override def allSourceFiles: T[Seq[PathRef]] = Task {
    val allMillSources =
      // the real input-sources
      allSources() ++
        // also sources, but derived from `scriptSources`
        generatedScriptSources().wrapped

    val candidates =
      Lib.findSourceFiles(allMillSources, Seq("scala", "java") ++ buildFileExtensions.asScala.toSeq)

    // We need to unlist those files, which we replaced by generating wrapper scripts
    val filesToExclude = Lib.findSourceFiles(scriptSources(), buildFileExtensions.asScala.toSeq)

    candidates.filterNot(filesToExclude.contains).map(PathRef(_))
  }

  def compileMvnDeps = Seq(
    mvn"com.lihaoyi::sourcecode:0.4.3-M5"
  )

  override def scalacPluginMvnDeps: T[Seq[Dep]] = Seq(
    mvn"com.lihaoyi:::scalac-mill-moduledefs-plugin:${Versions.millModuledefsVersion}"
      .exclude("com.lihaoyi" -> "sourcecode_3")
  )

  override def scalacOptions: T[Seq[String]] = Task { super.scalacOptions() ++ Seq("-deprecation") }

  /** Used in BSP IntelliJ, which can only work with directories */
  def dummySources: Sources = Task.Sources(Task.dest)

  def millVersion: T[String] = Task.Input { BuildInfo.millVersion }

  override def compile: T[CompilationResult] = Task(persistent = true) {
    val mv = millVersion()

    val prevMillVersionFile = Task.dest / s"mill-version"
    val prevMillVersion = Option(prevMillVersionFile)
      .filter(os.exists)
      .map(os.read(_).trim)
      .getOrElse("?")

    if (prevMillVersion != mv) {
      // Mill version changed, drop all previous incremental state
      // see https://github.com/com-lihaoyi/mill/issues/3874
      Task.log.debug(
        s"Detected Mill version change ${prevMillVersion} -> ${mv}. Dropping previous incremental compilation state"
      )
      os.remove.all(Task.dest)
      os.makeDir(Task.dest)
      os.write(prevMillVersionFile, mv)
    }

    // copied from `ScalaModule`
    jvmWorker()
      .worker()
      .compileMixed(
        upstreamCompileOutput = upstreamCompileOutput(),
        sources = Seq.from(allSourceFiles().map(_.path)),
        compileClasspath = compileClasspath().map(_.path),
        javacOptions = javacOptions() ++ mandatoryJavacOptions(),
        scalaVersion = scalaVersion(),
        scalaOrganization = scalaOrganization(),
        scalacOptions = allScalacOptions(),
        compilerClasspath = scalaCompilerClasspath(),
        scalacPluginClasspath = scalacPluginClasspath(),
        reporter = Task.reporter.apply(hashCode),
        reportCachedProblems = zincReportCachedProblems(),
        incrementalCompilation = zincIncrementalCompilation(),
        auxiliaryClassFileExtensions = zincAuxiliaryClassFileExtensions()
      )
  }
}

object MillBuildRootModule {

  class BootstrapModule()(implicit
      rootModuleInfo: RootModule0.Info
  ) extends mill.main.MainRootModule() with MillBuildRootModule() {
    override lazy val millDiscover = Discover[this.type]
  }

  case class Info(
      projectRoot: os.Path,
      output: os.Path,
      topLevelProjectRoot: os.Path
  )

  def parseBuildFiles(
      parser: MillScalaParser,
      millBuildRootModuleInfo: RootModule0.Info
  ): FileImportGraph = {
    FileImportGraph.parseBuildFiles(
      millBuildRootModuleInfo.topLevelProjectRoot,
      millBuildRootModuleInfo.projectRoot / os.up,
      millBuildRootModuleInfo.output
    )
  }
}
