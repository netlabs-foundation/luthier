package uy.com.netlabs.esb
package runner

import scala.tools.nsc.Settings
import scala.tools.nsc.interpreter.{ IMain, IR }
import scala.concurrent._
import scala.concurrent.util.Duration
import scala.collection.JavaConversions._

import java.nio.file._

object Main {

  def main(args: Array[String]) {
    val settings = new Settings
    settings.YmethodInfer.value = true
    settings.usejavacp.value = true
//    settings.debug.value = true
//    settings.log.value = List("typer")
//    settings.Yinferdebug.value = true
    settings.classpath.value = classpath()
    
    val compiler = new IMain(settings)
    val initialized = Promise[Unit]()
    compiler.initialize(initialized.success(()))
    val (flows, restOfArgs) = args.span(_.endsWith(".flow"))

    //await compiler initialization
    if (!initialized.isCompleted) println("Waiting for compiler to finish initializing")
    Await.result(initialized.future, Duration.Inf)
    //insert restOfArgs into compiler
    require(compiler.bind("args", "Array[String]", restOfArgs) == IR.Success, "Could not bind args")

    //declare basic imports
    require(compiler.addImports("uy.com.netlabs.esb._",
      "uy.com.netlabs.esb.typelist._",
      "scala.language._") == IR.Success, "Could not add default imports")

    val runner = new AppContext {
      val name = "Runner"
      val rootLocation = Paths.get("")  
    }
    val runnerFlows = new Flows {
      val appContext = runner
    }
      
    //instantiate the flows:
    for (f <- flows) {
      val h = new FlowHandler(compiler, f)
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
    
    //now urls declared in the manifest
    val manifest = new java.util.jar.Manifest(getClass.getResourceAsStream("/META-INF/MANIFEST.MF"))
    val mainAttrs = manifest.getMainAttributes()
    val cpInManifest = mainAttrs.getValue(java.util.jar.Attributes.Name.CLASS_PATH)
    val baseDir = Paths.get(".")
    val basePathForLibs = getClass.getResource("Main.class") match {
      case null => throw new IllegalStateException("Could not deduct where I'm running from!")
      case u => 
        val p = u.toString.stripPrefix("jar:file:")
        Paths.get(p.substring(0, p.lastIndexOf('!'))).getParent
    }
    val urlsFromManifest = cpInManifest.split(" ").map(j => j.split("/").foldLeft(basePathForLibs)((d, p) => d.resolve(p)))
    val allUrls = urlsFromClasspath ++ urlsFromManifest
    
    println("Using classpath:")
    allUrls foreach println
    allUrls.map(_.toString).mkString(":")
  }
}