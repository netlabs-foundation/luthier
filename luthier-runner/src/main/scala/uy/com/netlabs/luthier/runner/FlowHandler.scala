package uy.com.netlabs.luthier
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
  @volatile private[this] var _theFlows: Flows = _

  def theFlows = Option(_theFlows)

  private[this] var _watcherFlow: Option[Flow] = None
  def watcherFlow = _watcherFlow
  def startWatching(runnerFlows: Flows) {
    import runnerFlows._
    _watcherFlow = Some(new runnerFlows.Flow("Watch " + file.replace('/', '_'))(endpoint.logical.Metronome(100.millis)) {
      logic { m =>
        try {
          if (Files.exists(filePath)) {
            val attrs = Files.readAttributes(filePath, classOf[attribute.BasicFileAttributes])
            if (attrs.lastModifiedTime().toMillis() > lastUpdate) {
              logger.info(Console.MAGENTA + s"Reloading flow $file" + Console.RESET)
              if (_theFlows != null) {
                logger.info(Console.MAGENTA + s"Stoping previous incarnation" + Console.RESET)
                _theFlows.registeredFlows foreach (_.dispose())
                _theFlows.appContext.actorSystem.shutdown()
                logger.info(Console.MAGENTA + s"Done" + Console.RESET)
              }
              load()
            }
          }
        } catch { case ex => logger.warn("Error while loading flow " + file, ex) }
      }
    })
    _watcherFlow.get.start()
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
        val script = s"""
      import uy.com.netlabs.luthier._
      import uy.com.netlabs.luthier.typelist._
      import scala.language._
      val app = new AppContext {
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
          f.start()
        }
        val totalTime = System.nanoTime() - appStartTime
        logger.info(Console.GREEN + f"  App fully initialized. Total time: ${totalTime / 1000000000d}%.3f" + Console.RESET)
        _theFlows = flows
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