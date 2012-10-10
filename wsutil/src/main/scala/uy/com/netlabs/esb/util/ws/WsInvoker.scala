package uy.com.netlabs.esb.util.ws

import scala.reflect.macros._
import scala.language._, experimental._
import scala.util._
import scala.sys.process._
import java.net.URL
import java.nio.file._

object WsInvoker {

  def runScript(classpath: Seq[URL])(script: String): () => Any = {
    import scala.tools.nsc.interpreter._
    val settings = new scala.tools.nsc.Settings
    settings.usejavacp.value = true
    settings.classpath.value = classpath.map(u => Paths.get(u.toURI)).mkString(java.io.File.pathSeparator)

    val main = new IMain(settings)
    main.initializeSynchronous()
    try {
      main.quietRun("() => {" + script + "}") match {
        case Results.Success => main.lastRequest.getEvalTyped[() => Any].get
        case other           => throw new Exception("Invalid script")
      }
    } finally {
      main.close()
    }
  }

  def callWsImpl(c: Context)(wsdlLocation: c.Expr[String])(script: c.Expr[String]): c.Expr[Any] = {
    val wsdl = c.universe.show(wsdlLocation.tree).drop(1).dropRight(1)
    val scriptTest = c.eval(c.Expr[String](c.resetAllAttrs(script.tree)))
    //    println("Script test: " + scriptTest)

    val url = new URL(wsdl)
    val tempDir = Paths.get(Properties.tmpDir, url.getHost + url.getPath.replace('/', '_'))
    Files.createDirectories(tempDir)
    val jarPath = Paths.get(s"$tempDir/ws.jar")
    if (!Files.exists(jarPath)) {
      val cmd = s"wsimport -clientjar $jarPath $wsdl"
      println(Console.YELLOW + "Running " + cmd + Console.RESET)
      if (cmd.! != 0) throw new Exception("wsimport failed")
    }

    val cp = c.libraryClassPath :+ jarPath.toUri.toURL
    val cpExpr = c.Expr[Seq[URL]](c.parse(cp.map(u => "new java.net.URL(\"" + u + "\")").mkString("Seq(", ", ", ")")))
    //    println(s"Using classpath ${cp.mkString("\n")}")
    Try(runScript(cp)(scriptTest)) match {
      case Success(res) => c.universe.reify(runScript(cpExpr.splice)(script.splice)())
      case Failure(err) => c.abort(c.enclosingPosition, err.toString)
    }
  }

  def callWs(wsdlLocation: String)(script: String): Any = macro callWsImpl
}