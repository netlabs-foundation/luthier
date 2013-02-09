package uy.com.netlabs.luthier
package runner

import java.nio.file._
import scala.tools.nsc.interpreter.{ IMain, IR }
import scala.util._
import scala.collection.JavaConversions._
import scala.concurrent.duration._
import akka.event.LoggingAdapter

class FlowHandler(compiler: => IMain, logger: LoggingAdapter, file: String) {
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
        } catch { case ex => logger.warning("Error while loading flow " + file, ex) }
      }
    })
    _watcherFlow.get.start()
    logger.info("Started watching file " + file)
  }
  def load() {
    if (Files.exists(filePath)) {
      lastUpdate = System.currentTimeMillis()
      println("LOADING " + filePath)
      filePath match {
        case _ if filePath.toString endsWith ".fflow" => loadFullFlow(filePath)
        case _ if filePath.toString endsWith ".flow" => loadFlow(filePath)
        case _ => logger.warning(s"Unsupported file $filePath")
      }

    } else {
      logger.warning(Console.RED + s" Flow $file does not exists")
    }
  }
  def loadFlow(filePath: Path) {
    println("Loading flow " + filePath)
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
    } catch { case ex: Exception => logger.error(ex, s"Error loading $file") }
  }
  def loadFullFlow(filePath: Path) {
    try {
      val content = Files.readAllLines(filePath, java.nio.charset.Charset.forName("utf8")).toSeq
      require(compilerLazy.interpret(content mkString "\n") == IR.Success, "Failed compiling flow " + file)
      val lr = compilerLazy.lastRequest
      lr.definedSymbolList match {
        case Nil => throw new IllegalArgumentException("No class defined in Full Flow file")
        case symbols =>
          val flowsSymbol = compilerLazy.global.typeOf[Flows].typeSymbol
          val appContextType = compilerLazy.global.typeOf[AppContext]
          //find the symbols that extends Flows and that have a constructor that takes an AppContext
          val possibleFlows = for {
            sy <- symbols if sy.isClass && !sy.isAbstractClass
            cs = sy.asClass if cs.typeSignature.baseType(flowsSymbol) != compilerLazy.global.NoType
            pc = cs.primaryConstructor.asMethod if pc.isPublic
            List(List(singleArgSymbol)) = pc.paramss //must be a single param list with one symbol
            if singleArgSymbol.typeSignature <:< appContextType // the one param is an appContext, we hit the gold
          } yield cs

          //instantiate the selected flows.
          val appName = {
            val r = file.stripSuffix(".flow").replace('/', '-')
            if (r.charAt(0) == '-') r.drop(1) else r
          }
          val appContext = new AppContext {
            val name = appName
            val rootLocation = filePath
          }
          val runtimeMirror = scala.reflect.runtime.universe.runtimeMirror(compilerLazy.classLoader)
          val flowss = possibleFlows map { flowsSymbol =>
            val c = compilerLazy.getInterpreterClassLoader.loadClass(flowsSymbol.javaClassName).getConstructor(classOf[AppContext])
            logger.debug(s"Constructor $c")
            c.newInstance(appContext).asInstanceOf[Flows]
          }

          logger.info("Starting App: " + appContext.name)
          val appStartTime = System.nanoTime()
          for (flows <- flowss; flow <- flows.registeredFlows) flow.start()
          val totalTime = System.nanoTime() - appStartTime
          logger.info(Console.GREEN + f"  App fully initialized. Total time: ${totalTime / 1000000000d}%.3f" + Console.RESET)

          _theFlows = flowss.lastOption.orNull
      }
    } catch {
      case e: java.lang.reflect.InvocationTargetException => logger.error(e.getTargetException(), s"Failed to instance one of the flows defined in $file") 
      case ex: Exception => logger.error( ex, s"Error loading $file")
    }
  }
  //  def findPrecompiledVersion() {
  //    val compilerVersionPath = filePath.getParent.resolve("." + filePath.getFileName() + ".jar")
  //    if (Files exists compilerVersionPath) {
  //      
  //    } else None
  //  }
}
