package uy.com.netlabs.esb
package runner

import scala.tools.nsc.Settings
import scala.tools.nsc.interpreter.{ IMain, IR }
import scala.concurrent._
import scala.concurrent.duration._
import scala.collection.JavaConversions._

import java.nio.file._

object Main {

  def main(args: Array[String]) {
    val (flows, restOfArgs) = args.span(_.endsWith(".flow"))
    
    val settings = new Settings
    settings.YmethodInfer.value = true
    settings.usejavacp.value = true
//    settings.debug.value = true
//    settings.log.value = List("typer")
//    settings.Yinferdebug.value = true
    settings.classpath.value = classpath()
    
    
    val compiler = new IMain(settings)
    val initialized = Promise[Unit]()
    compiler.initialize {
      //insert restOfArgs into compiler
      require(compiler.bind("args", "Array[String]", restOfArgs) == IR.Success, "Could not bind args")

      //declare basic imports
      if (compiler.addImports("uy.com.netlabs.esb._",
      "uy.com.netlabs.esb.typelist._",
      "scala.language._") != IR.Success) initialized.failure(new IllegalStateException("Could not add default imports"))
      else initialized.success(()) 
    }

    val runner = new AppContext {
      val name = "Runner"
      val rootLocation = Paths.get("")  
    }
    val runnerFlows = new Flows {
      val appContext = runner
    }
    
    lazy val lazyCompiler = {
      if (!initialized.isCompleted) println("Waiting for compiler to finish initializing")
      Await.result(initialized.future, Duration.Inf)
      compiler
    }
      
    //instantiate the flows:
    for (f <- flows) {
      val h = new FlowHandler(lazyCompiler, f)
      h.load() //attempt to initialize it synchronously
      h.startWatching(runnerFlows)
    }
    println("all apps initialized")
  }
  
  def classpath() = {
    import java.net.URLClassLoader
    def cp(cl: URLClassLoader) = {
      cl.getURLs().map(u => new java.io.File(u.toURI()))
    }
    val urlsFromClasspath = Seq(getClass.getClassLoader(), ClassLoader.getSystemClassLoader()).flatMap {
      case cl: URLClassLoader => cp(cl)
      case other => println("Weird classloader: " + other + ": " + other.getClass); Set.empty
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

    val urlsFromManifest = cpInManifest.split(" ").map(j => j.split("/").foldLeft(basePathForLibs)((d, p) => d.resolve(p)))
    val allUrls = urlsFromClasspath ++ urlsFromManifest
    
    println("Using classpath:")
    allUrls foreach println
    allUrls.map(_.toString).mkString(":")
  }
}