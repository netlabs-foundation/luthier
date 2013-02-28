package uy.com.netlabs.luthier
package runner

import scala.tools.nsc.Settings
import scala.tools.nsc.interpreter.{ IMain, IR }
import scala.util._
import scala.concurrent._
import scala.concurrent.duration._
import scala.collection.JavaConversions._
import java.nio.file._
import org.slf4j._
import scala.annotation.varargs

class FlowsRunner(val appContext: AppContext,
                  compilerSettings: Settings, classLoader: ClassLoader) {
  import FlowsRunner._

  private[this] val compiler = new IMain(compilerSettings)
  private[this] val initialized = Promise[Unit]()
  compiler.initialize {
    //declare basic imports
    initialized.success(())
    //    if (compiler.addImports("uy.com.netlabs.luthier._",
    //      "uy.com.netlabs.luthier.typelist._",
    //      "scala.language._") != IR.Success) initialized.failure(new IllegalStateException("Could not add default imports"))
    //    else initialized.success(())
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

  def this(appContextName: String, classLoader: ClassLoader) = this(AppContext.build(appContextName, Paths.get("")),
                                                                    FlowsRunner.defaultCompilerSettings(classLoader), classLoader)
  def this(classLoader: ClassLoader) = this("Runner", classLoader)

  /**
   * Binds the given object into the compiler instance, so its accessible as a global variable from the
   * flows
   */
  def bindObject(name: String, classDecl: String, obj: Any) {
    lazyCompiler.bind(name, classDecl, obj) match {
      case IR.Error      => throw new IllegalStateException("Could not bind " + name)
      case IR.Incomplete => throw new IllegalStateException("Definition incomplete for variable " + name)
      case _             =>
    }
  }

  @varargs def load(flows: Path*) = flows.map { path =>
    val h = new FlowHandler(lazyCompiler, appContext.actorSystem.log, path.toAbsolutePath().toString)
    h.load()() //attempt to initialize it synchronously
    h.startWatching(runnerFlows)
    h
  }.to[Array]

  /**
   * Disposes this FlowsRunner shutting down its compiler instance and its actor system, this in turn
   * causes the monitoring of flows to stop, but *not* the running flows. To stop them you must use
   * their respective ``FlowHandler``s
   */
  def dispose() {
    val s1 = Try(runnerFlows.appContext.actorSystem.shutdown())
    val s2 = Try(runnerFlows.stopAllFlows())
    val s3 = Try(compiler.close())
    //re throw the first exception encountered... Possibly not the best of logics?
    s1.get
    s2.get
    s3.get
  }
}
object FlowsRunner {
  private[FlowsRunner] val logger = LoggerFactory.getLogger(classOf[FlowsRunner])
  def defaultCompilerSettings(parentClassLoader: ClassLoader) = {
    val settings = new Settings

    settings.YmethodInfer.value = true
    //    settings.optimise.value = true
    //    settings.usejavacp.value = true
    //    settings.Yinferdebug.value = true
    //    settings.Xprint.value = List("typer")
    //    settings.debug.value = true
    //    settings.log.value = List("typer")
    //    settings.Ytyperdebug.value = true
    val cp = classpath(parentClassLoader)
    println("Using classpath:\n\t" + cp.mkString("\n\t"))
    settings.classpath.value = cp.mkString(java.io.File.pathSeparator)
    settings
  }

  private[this] def classpath(parentClassLoader: ClassLoader) = {
    import java.net.URLClassLoader
    def cp(cl: ClassLoader): Seq[java.io.File] = cl match {
      case null               => Seq.empty
      case cl: URLClassLoader => cl.getURLs().map(u => new java.io.File(u.toURI())) ++ cp(cl.getParent)
      case other              => cp(other.getParent())
    }
    val urlsFromClasspath = Seq(getClass.getClassLoader(), parentClassLoader, ClassLoader.getSystemClassLoader()).flatMap(cp).distinct

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
    allUrls
  }
}
