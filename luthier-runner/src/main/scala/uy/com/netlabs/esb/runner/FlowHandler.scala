package uy.com.netlabs.esb
package runner

import java.nio.file._
import scala.tools.nsc.interpreter.{ IMain, IR }
import scala.util._
import scala.collection.JavaConversions._
import scala.concurrent.util.duration._

class FlowHandler(compiler: IMain, file: String) {
  val filePath = Paths.get(file)
  @volatile var lastUpdate: Long = 0
  @volatile var theFlows: Flows = _
  def startWatching(runnerFlows: Flows) {
    import runnerFlows._
    new runnerFlows.Flow("Watch " + file.replace('/', '_'))(endpoint.logical.Metronome(100.millis)) {
      logic {m =>
        try {
          if (Files.exists(filePath)) {
            val attrs = Files.readAttributes(filePath, classOf[attribute.BasicFileAttributes])
            if (attrs.lastModifiedTime().toMillis() > lastUpdate) {
              println(Console.MAGENTA + s"Reloading flow $file" + Console.RESET)
              if (theFlows != null) {
                println(Console.MAGENTA + s"Stoping previous incarnation" + Console.RESET)
                theFlows.registeredFlows foreach (_.stop)
                println(Console.MAGENTA + s"Done" + Console.RESET)
              }
              load()
            }
          }
        } catch { case ex => println("Error while loading flow " + file); ex.printStackTrace() }
      }
    }.start()
    println("Started watching file " + file)
  }
  def load() {
    if (Files.exists(filePath)) {
      lastUpdate = System.currentTimeMillis()
      try {
        val content = Files.readAllLines(filePath, java.nio.charset.Charset.forName("utf8")).toSeq
        val appName = {
          val r = file.stripSuffix(".flow").replace('/', '-')
          if (r.charAt(0) == '-') r.drop(1) else r
        }
        val script = s"""val app = new AppContext {
        val name = "${appName}"
        val rootLocation = java.nio.file.Paths.get("$file")
      }
      val flow = new Flows {
        val appContext = app
        
        ${content.mkString("\n")}
      }
      """
        require(compiler.interpret(script) == IR.Success, "Failed compiling flow " + file)
        val flows = compiler.lastRequest.getEvalTyped[Flows].getOrElse(throw new IllegalStateException("Could not load flow " + file))
        println("Starting App: " + flows.appContext.name)
        val appStartTime = System.nanoTime()
        flows.registeredFlows foreach { f =>
          print(s"  Starting flow: ${f.name}...")
          f.start()
          println(" started")
        }
        val totalTime = System.nanoTime() - appStartTime
        println(Console.GREEN + f"  App fully initialized. Total time: ${totalTime / 1000000000d}%.3f" + Console.RESET)
        theFlows = flows
      } catch { case ex: Exception => println(s"Error loading $file: $ex") }
    } else {
      println(Console.RED + s" Flow $file does not exists")
    }
  }
}