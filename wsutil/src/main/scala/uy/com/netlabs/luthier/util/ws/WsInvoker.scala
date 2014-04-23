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
package uy.com.netlabs.luthier.util.ws

import scala.reflect.macros.blackbox._
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
      main.eval("() => {" + script + "}") match {
        case f: Function0[Any] => f
        case other           => throw new Exception("Invalid script")
      }
    } finally {
      main.close()
    }
  }

  def callWsImpl(c: Context)(wsdlLocation: c.Expr[String])(script: c.Expr[String]): c.Expr[Any] = {
    val wsdl = c.universe.show(wsdlLocation.tree).drop(1).dropRight(1)
    val scriptTest = c.eval(c.Expr[String](c.untypecheck(script.tree)))
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

    val cp = c.classPath :+ jarPath.toUri.toURL
    val cpExpr = c.Expr[Seq[URL]](c.parse(cp.map(u => "new java.net.URL(\"" + u + "\")").mkString("Seq(", ", ", ")")))
    //    println(s"Using classpath ${cp.mkString("\n")}")
    Try(runScript(cp)(scriptTest)) match {
      case Success(res) => c.universe.reify(runScript(cpExpr.splice)(script.splice)())
      case Failure(err) => c.abort(c.enclosingPosition, err.toString)
    }
  }

  def callWs(wsdlLocation: String)(script: String): Any = macro callWsImpl
}
