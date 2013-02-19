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
  @volatile private[this] var _theFlows: Seq[Flows] = _

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
                  _theFlows foreach (_.registeredFlows foreach (_.dispose()))
                  _theFlows.head.appContext.actorSystem.shutdown() //they share appcontext, so stopping the head is enough
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
        case _ if (filePath.toString endsWith ".fflow") || (filePath.toString endsWith ".scala") => loadFullFlow(filePath)
        case _ if filePath.toString endsWith ".flow"  => loadFlow(filePath)
        case _                                        => logger.warning(s"Unsupported file $filePath")
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
      _theFlows = Seq(flows)
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
          //find the symbols that extends Flows and that have a constructor that takes an AppContext as first argument, then try to find matches
          //for the remaining types.
          //I'll type the variable possibleFlows myself, because its kinda creepy.
          val processedFlows: Seq[String Either (compilerLazy.global.ClassSymbol, Seq[compilerLazy.global.Symbol])] = for {
            sy <- symbols if sy.isClass && !sy.isAbstractClass
            cs = sy.asClass if cs.typeSignature.baseType(flowsSymbol) != compilerLazy.global.NoType
            constructors = cs.typeSignature.declaration(compilerLazy.global.nme.CONSTRUCTOR).asTerm.alternatives if constructors.size == 1
            pc = constructors.head.asMethod if pc.isPublic
            List(appContextParam :: params) = pc.paramss //must be a single param list
            if appContextParam.typeSignature <:< appContextType // the first param is an appContext, we hit the gold
          } yield {
            val matchedParams = params map (p => p -> compilerLazy.typeOfTerm(p.name.toTermName.toString))

            val unmatchedParams = matchedParams.filter(p => p._2 == compilerLazy.global.NoType || !p._2.<:<(p._1.typeSignature))
            if (unmatchedParams.isEmpty) Right(cs -> matchedParams.map(_._1))
            else {
              def describe(s: compilerLazy.global.Symbol) = s"${s.nameString}: ${s.typeSignature}"
              val unmatchedParamsDescr = unmatchedParams map {
                case (param, compilerLazy.global.NoType) => describe(param)
                case (param, fromCompiler) => describe(param) +
                  s"  (Note that a variable ${param.nameString} was found, but it is not applicable because its type $fromCompiler is not assignable from expected type ${param.typeSignature})"
              }
              Left(s"$cs correctly extends Flows, but its primary constructor cannot be called.\n" +
                   s"It is defined as:\n  ${pc.paramss.map(_.map(describe).mkString("(", ", ", ")")).mkString("")}\n" +
                   s"and I don't know how to provide\n  ${unmatchedParamsDescr.mkString("\n  ")}")
            }
          }
          val (failedFlows, possibleFlows) = processedFlows.partition(_.isLeft)

          failedFlows foreach { case Left(msg) => logger.warning(msg) }

          //instantiate the selected flows.
          val appName = {
            val r = file.stripSuffix(".fflow").stripSuffix(".scala").replace('/', '-')
            if (r.charAt(0) == '-') r.drop(1) else r
          }
          val appContext = new AppContext {
            val name = appName
            val rootLocation = filePath
          }
          val flowss = possibleFlows map {
            case Right((flowsSymbol, argumentsSymbols)) =>
              def loadClass(c: String) = compilerLazy.getInterpreterClassLoader.loadClass(c)
              val argumentClasses = classOf[AppContext] +: argumentsSymbols.map(s => loadClass(s.typeSignature.typeSymbol.javaClassName)) toArray;
              val c = loadClass(flowsSymbol.javaClassName).getConstructor(argumentClasses: _*)
              logger.debug(s"Constructor $c")
              val arguments = argumentsSymbols map (s => compilerLazy.requestForName(s.asTerm.name).flatMap(_.getEval).getOrElse(throw new IllegalStateException(s"Could not obtain value $s from the compiler!")))
              c.newInstance((appContext +: arguments).toArray[Object]: _*).asInstanceOf[Flows]
          }

          logger.info("Starting App: " + appContext.name)
          val appStartTime = System.nanoTime()
          for (flows <- flowss; flow <- flows.registeredFlows) flow.start()
          val totalTime = System.nanoTime() - appStartTime
          logger.info(Console.GREEN + f"  App fully initialized. Total time: ${totalTime / 1000000000d}%.3f" + Console.RESET)

          _theFlows = flowss
      }
    } catch {
      case e: java.lang.reflect.InvocationTargetException => logger.error(e.getTargetException(), s"Failed to instance one of the flows defined in $file")
      case ex: Exception                                  => logger.error(ex, s"Error loading $file")
    }
  }
  //  def findPrecompiledVersion() {
  //    val compilerVersionPath = filePath.getParent.resolve("." + filePath.getFileName() + ".jar")
  //    if (Files exists compilerVersionPath) {
  //
  //    } else None
  //  }
  def flowRef(flowName: String): Option[Flow] = {
    theFlows flatMap (_.flatMap(_.registeredFlows.find(_.name == flowName)).headOption)
  }
}
