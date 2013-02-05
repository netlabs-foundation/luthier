package uy.com.netlabs.esb
package runner

import scala.tools.nsc.Settings
import scala.tools.nsc.interpreter.{ IMain, IR }
import scala.concurrent._
import scala.concurrent.duration._
import scala.collection.JavaConversions._
import java.nio.file._
import org.slf4j._
import scala.annotation.varargs

class FlowsRunner(val appContext: AppContext,
                  compilerSettings: Settings) {
  import FlowsRunner._

  private[this] val compiler = new IMain(compilerSettings)
  private[this] val initialized = Promise[Unit]()
  compiler.initialize {
    //declare basic imports
    if (compiler.addImports("uy.com.netlabs.esb._",
      "uy.com.netlabs.esb.typelist._",
      "scala.language._") != IR.Success) initialized.failure(new IllegalStateException("Could not add default imports"))
    else initialized.success(())
  }

  val runnerFlows = new Flows {
    val appContext = FlowsRunner.this.appContext
  }

  // a compiler that will wait until its fully instantiated, just the first time.
  private[this] lazy val lazyCompiler = {
    if (!initialized.isCompleted) logger.info("Waiting for compiler to finish initializing")
    Await.result(initialized.future, Duration.Inf)
    compiler
  }

  def this(appContextName: String) = this(new AppContext {
    val name = appContextName
    val rootLocation = Paths.get("")
  }, FlowsRunner.defaultCompilerSettings)
  def this() = this("Runner")

  /**
   * Binds the given object into the compiler instance, so its accessible as a global variable from the
   * flows
   */
  def bindObject(name: String, classDecl: String, obj: Any) {
    lazyCompiler.bind(name, classDecl, obj) match {
      case IR.Error => throw new IllegalStateException("Could not bind " + name)
      case IR.Incomplete => throw new IllegalStateException("Definition incomplete for variable " +name)
      case _ =>
    }
  }

  @varargs def load(flows: Path*) = flows.map { path =>
    val h = new FlowHandler(lazyCompiler, path.toAbsolutePath().toString)
    h.load() //attempt to initialize it synchronously
    h.startWatching(runnerFlows)
    h
  }.to[Array]
}
object FlowsRunner {
  private[FlowsRunner] val logger = LoggerFactory.getLogger(classOf[FlowsRunner])
  val defaultCompilerSettings = {
    val settings = new Settings
    settings.YmethodInfer.value = true
    settings.usejavacp.value = true
    //    settings.debug.value = true
    //    settings.log.value = List("typer")
    //    settings.Yinferdebug.value = true
    settings.classpath.value = classpath()
    settings
  }

  private[this] def classpath() = {
    import java.net.URLClassLoader
    def cp(cl: URLClassLoader) = {
      cl.getURLs().map(u => new java.io.File(u.toURI()))
    }
    val urlsFromClasspath = Seq(getClass.getClassLoader(), ClassLoader.getSystemClassLoader()).flatMap {
      case cl: URLClassLoader => cp(cl)
      case other              => logger.warn("Weird classloader: " + other + ": " + other.getClass); Set.empty
    }.distinct

    val baseDir = Paths.get(".")
    val (basePathForLibs, baseUrl) = getClass.getResource(getClass.getSimpleName + ".class") match {
      case null => throw new IllegalStateException("Could not deduct where I'm running from!")
      case u =>
        val p = u.toString
        val pathToJar = p.substring(0, p.lastIndexOf('!'))
        Paths.get(pathToJar.stripPrefix("jar:file:")).getParent -> pathToJar
    }
    //having found myself in this universe
    val manifest = new java.util.jar.Manifest(new java.net.URL(baseUrl + "!/META-INF/MANIFEST.MF").openStream())
    val mainAttrs = manifest.getMainAttributes()
    val cpInManifest = mainAttrs.getValue(java.util.jar.Attributes.Name.CLASS_PATH)

    val urlsFromManifest = cpInManifest.split(" ").map(j => j.split("/").foldLeft(basePathForLibs)((d, p) => d.resolve(p))).map(_.toFile).filter(_.exists)
    val allUrls = urlsFromClasspath ++ urlsFromManifest

    logger.info("FlowsRunner using classpath:\n\t" + allUrls.map(_.toString).mkString("\n\t"))
    allUrls.map(_.toString).mkString(":")
  }
}