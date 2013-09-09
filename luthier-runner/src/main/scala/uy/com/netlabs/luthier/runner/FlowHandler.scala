/**
 * Copyright (c) 2013, Netlabs S.R.L. <contacto@netlabs.com.uy>
 * All rights reserved.
 *
 * This software is dual licensed as GPLv2: http://gnu.org/licenses/gpl-2.0.html,
 * and as the following 3-clause BSD license. In other words you must comply to
 * either of them to enjoy the permissions they grant over this software.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *     * Redistributions of source code must retain the above copyright
 *       notice, this list of conditions and the following disclaimer.
 *     * Redistributions in binary form must reproduce the above copyright
 *       notice, this list of conditions and the following disclaimer in the
 *       documentation and/or other materials provided with the distribution.
 *     * Neither the name "netlabs" nor the names of its contributors may be
 *       used to endorse or promote products derived from this software
 *       without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL NETLABS S.R.L.  BE LIABLE FOR ANY
 * DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package uy.com.netlabs.luthier
package runner

import java.nio.file._
import scala.tools.nsc.interpreter.{ IMain, IR }
import scala.util._
import scala.collection.JavaConversions._
import scala.concurrent.duration._
import akka.event.LoggingAdapter

import language._

class FlowHandler(compiler: => IMain, logger: LoggingAdapter, file: String, shareGlobalActorContext: Boolean = false) {
  private lazy val compilerLazy = compiler
  val filePath = Paths.get(file)
  @volatile var lastUpdate: Long = 0
  @volatile private[this] var _theFlows: Seq[Flows] = _

  def theFlows = Option(_theFlows)

  private[this] var _watcherFlow: Option[Flow] = None
  def watcherFlow = _watcherFlow
  private[runner] def startWatching(runnerFlows: Flows) {
    import runnerFlows._
    _watcherFlow = Some(new runnerFlows.Flow("Watch " + file.replace('/', '_'))(endpoint.logical.Metronome(100.millis)) {
        logic { m =>
          try {
            if (Files.exists(filePath)) {
              val attrs = Files.readAttributes(filePath, classOf[attribute.BasicFileAttributes])
              if (attrs.lastModifiedTime().toMillis() > lastUpdate) {
                logger.info(Console.MAGENTA + s"Reloading flow $file" + Console.RESET)
                if (tryCompile()) {
                  val instantiate = load(runnerFlows.appContext)
                  if (_theFlows != null) {
                    logger.info(Console.MAGENTA + s"Stoping previous incarnation" + Console.RESET)
                    _theFlows
                    _theFlows foreach (_.stopAllFlows)
                    if (!shareGlobalActorContext) {
                      _theFlows.head.appContext.actorSystem.shutdown() //all the flowss share the same appContext, so stopping the head is enough.
                    }
                    logger.info(Console.MAGENTA + s"Done" + Console.RESET)
                  }
                  instantiate()
                } else logger.info(Console.MAGENTA + s"Flow does not compile" + Console.RESET)
              }
            }
          } catch { case ex: Throwable => logger.error(ex, "Error while loading flow " + file) }
        }
      })
    _watcherFlow.get.start()
    logger.info("Started watching file " + file)
  }
  private[this] def tryCompile(): Boolean = {
    if (Files.exists(filePath)) {
      lastUpdate = System.currentTimeMillis()
      filePath match {
        case _ if (filePath.toString endsWith ".fflow") || (filePath.toString endsWith ".scala") =>
          val content = Files.readAllLines(filePath, java.nio.charset.Charset.forName("utf8")).toSeq
          compilerLazy.compileString(content.mkString("\n"))
        case _ if filePath.toString endsWith ".flow"  =>
          val script = "object script {\n" +
          "  val args: Seq[String] = null\n" +
          "  val interpreter: scala.tools.nsc.interpreter.IMain = null\n" +
          "  val config = com.typesafe.config.ConfigFactory.load()\n" + //have to simulate a valid config
          flowScript + "\n}"
          compilerLazy.compileString(script)
        case _                                        =>
          logger.warning(s"Unsupported file $filePath")
          false
      }
    } else {
      logger.warning(Console.RED + s" Flow $file does not exists")
      false
    }
  }
  def load(parentAppContext: AppContext): () => Unit = {
    if (Files.exists(filePath)) {
      lastUpdate = System.currentTimeMillis()
      compilerLazy.beQuietDuring {
        filePath match {
          case _ if (filePath.toString endsWith ".fflow") || (filePath.toString endsWith ".scala") => loadFullFlow(parentAppContext, filePath)
          case _ if filePath.toString endsWith ".flow"  => loadFlow(parentAppContext, filePath)
          case _                                        => throw new Exception(s"Unsupported file $filePath")
        }
      }
    } else {
      throw new java.io.FileNotFoundException(Console.RED + s" Flow $file does not exists")
    }
  }
  private[this] def loadFlow(parentAppContext: AppContext, filePath: Path): () => Unit = {
    val script = flowScript
    if (shareGlobalActorContext) {
      require(compilerLazy.bind("sharedAppContext", parentAppContext) == IR.Success, "Failed compiling flow " + file)
    } else {
      require(compilerLazy.bind("config", parentAppContext.actorSystem.settings.config) == IR.Success, "Failed compiling flow " + file)
    }
    require(compilerLazy.interpret(script) == IR.Success, "Failed compiling flow " + file)
    val flows = compilerLazy.lastRequest.getEvalTyped[Flows].getOrElse(throw new IllegalStateException("Could not load flow " + file))
    () => {
      logger.info("Starting App: " + flows.appContext.name)
      val appStartTime = System.nanoTime()
      flows.registeredFlows foreach { f =>
        f.start()
      }
      val totalTime = System.nanoTime() - appStartTime
      logger.info(Console.GREEN + f"  App fully initialized. Total time: ${totalTime / 1000000000d}%.3f" + Console.RESET)
      _theFlows = Seq(flows)
    }
  }
  private[this] def flowScript(): String = {
    val content = Files.readAllLines(filePath, java.nio.charset.Charset.forName("utf8")).toSeq
    val appName = {
      val r = file.stripSuffix(".flow").replace('/', '-')
      if (r.charAt(0) == '-') r.drop(1) else r
    }
    val script = s"""
      import uy.com.netlabs.luthier._
      import uy.com.netlabs.luthier.typelist._
      import scala.language._
      val app = ${if (shareGlobalActorContext) "sharedAppContext" else """AppContext.build("${appName}", java.nio.file.Paths.get("$file"), config)"""}
        val flow = new Flows {
          val appContext = app

          ${content.mkString("\n")}
        }
    """
    script
  }
  private[this] def loadFullFlow(parentAppContext: AppContext, filePath: Path): () => Unit = {
    try {
      val content = Files.readAllLines(filePath, java.nio.charset.Charset.forName("utf8")).toSeq
      //synchronize over the compiler instance, to make sure nobody messes with the classloader while we use it.
      compilerLazy.synchronized {
        compilerLazy.resetClassLoader()
        val theRun = compilerLazy.compileSourcesKeepingRun(new scala.reflect.internal.util.BatchSourceFile(filePath.toString, content mkString "\n"))
        if (!theRun._1) println(Console.RED + Console.BOLD + "Failed" + Console.RESET)
        require(theRun._1, "Failed compiling flow " + file)

        theRun._2.symSource.keys.toSeq match {
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
            val appContext = if (shareGlobalActorContext) parentAppContext else AppContext.build(appName, filePath, parentAppContext.actorSystem.settings.config)
            val flowss = possibleFlows map {
              case Right((flowsSymbol, argumentsSymbols)) =>
                def loadClass(c: String) = compilerLazy.getInterpreterClassLoader.loadClass(c)
                val argumentClasses = classOf[AppContext] +: argumentsSymbols.map(s => loadClass(s.typeSignature.typeSymbol.javaClassName)) toArray;
                val c = loadClass(flowsSymbol.javaClassName).getConstructor(argumentClasses: _*)
                logger.debug(s"Constructor $c")
                val arguments = argumentsSymbols map (s => compilerLazy.requestForName(s.asTerm.name).flatMap(_.getEval).getOrElse(throw new IllegalStateException(s"Could not obtain value $s from the compiler!")))
                c.newInstance((appContext +: arguments).toArray[Object]: _*).asInstanceOf[Flows]
            }

            () => {
              logger.info("Starting App: " + appContext.name)
              val appStartTime = System.nanoTime()
              for (flows <- flowss; flow <- flows.registeredFlows) flow.start()
              val totalTime = System.nanoTime() - appStartTime
              logger.info(Console.GREEN + f"  App fully initialized. Total time: ${totalTime / 1000000000d}%.3f" + Console.RESET)

              _theFlows = flowss
            }
        }
      }
    } catch {
      case e: java.lang.reflect.InvocationTargetException => throw new java.lang.reflect.InvocationTargetException(e.getTargetException(), s"Failed to instance one of the flows defined in $file")
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
