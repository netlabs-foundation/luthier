package uy.com.netlabs.esb
package runner

import java.nio.file._
import scala.tools.nsc.interpreter.{ IMain, IR }
import scala.util._
import scala.collection.JavaConversions._
import scala.concurrent.duration._

import org.slf4j._

class FlowHandler(compiler: => IMain, file: String) {
  import FlowHandler._
  private lazy val compilerLazy = compiler
  val filePath = Paths.get(file)
  @volatile var lastUpdate: Long = 0
  @volatile var theFlows: Flows = _

  var watcherFlow: Option[Flow] = None
  def startWatching(runnerFlows: Flows) {
    import runnerFlows._
    watcherFlow = Some(new runnerFlows.Flow("Watch " + file.replace('/', '_'))(endpoint.logical.Metronome(100.millis)) {
      logic { m =>
        try {
          if (Files.exists(filePath)) {
            val attrs = Files.readAttributes(filePath, classOf[attribute.BasicFileAttributes])
            if (attrs.lastModifiedTime().toMillis() > lastUpdate) {
              logger.info(Console.MAGENTA + s"Reloading flow $file" + Console.RESET)
              if (theFlows != null) {
                logger.info(Console.MAGENTA + s"Stoping previous incarnation" + Console.RESET)
                theFlows.registeredFlows foreach (_.dispose())
                theFlows.appContext.actorSystem.shutdown()
                logger.info(Console.MAGENTA + s"Done" + Console.RESET)
              }
              load()
            }
          }
        } catch { case ex => logger.warn("Error while loading flow " + file, ex) }
      }
    })
    watcherFlow.get.start()
    logger.info("Started watching file " + file)
  }
  def load() {
    if (Files.exists(filePath)) {
      //      findPrecompiledVersion()
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
        require(compilerLazy.interpret(script) == IR.Success, "Failed compiling flow " + file)
        val flows = compilerLazy.lastRequest.getEvalTyped[Flows].getOrElse(throw new IllegalStateException("Could not load flow " + file))
        logger.info("Starting App: " + flows.appContext.name)
        val appStartTime = System.nanoTime()
        flows.registeredFlows foreach { f =>
          print(s"  Starting flow: ${f.name}...")
          f.start()
          logger.info(" started")
        }
        val totalTime = System.nanoTime() - appStartTime
        logger.info(Console.GREEN + f"  App fully initialized. Total time: ${totalTime / 1000000000d}%.3f" + Console.RESET)
        theFlows = flows
      } catch { case ex: Exception => logger.warn(s"Error loading $file: $ex", ex) }
    } else {
      logger.warn(Console.RED + s" Flow $file does not exists")
    }
  }
  //  def findPrecompiledVersion() {
  //    val compilerVersionPath = filePath.getParent.resolve("." + filePath.getFileName() + ".jar")
  //    if (Files exists compilerVersionPath) {
  //      
  //    } else None
  //  }
}
object FlowHandler {
  private[FlowHandler] val logger = LoggerFactory.getLogger(classOf[FlowHandler])
}