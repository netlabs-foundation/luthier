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

import scala.tools.nsc.interpreter.{ IMain, IR }
import scala.concurrent._
import scala.concurrent.duration._
import org.slf4j.LoggerFactory
import scala.collection.JavaConversions._

import java.nio.file._

object Main extends InterpretersComponent {

  import org.apache.log4j._
  Logger.getRootLogger.addAppender(new  ConsoleAppender(new SimpleLayout, "System.out"))
  val compilerSettings = FlowsRunner.defaultCompilerSettings(getClass.getClassLoader())
  val logger = LoggerFactory.getLogger(getClass)

  def main(args: Array[String]) {
    val (flows, restOfArgs) = args.span(_.matches(".*\\.(flow|fflow)"))

    Interpreters.bindObject("args", "Vector[String]", restOfArgs.to[Seq])
    Interpreters.addImports("uy.com.netlabs.luthier._",
                            "uy.com.netlabs.luthier.typelist._",
                            "scala.language._")

    val runner = AppContext.build("Runner")
    val runnerFlows = new Flows {
      val appContext = runner
    }

    //instantiate the flows:
    for (f <- flows) {
      val h = new FlowHandler(Interpreters.newInterpreter(f, true), runner.actorSystem.log, f)
      h.load(runnerFlows.appContext)() //attempt to initialize it synchronously
      h.startWatching(runnerFlows)
    }
    println("all apps initialized")
  }
}