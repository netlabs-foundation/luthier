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

import scala.tools.nsc.Settings
import scala.tools.nsc.interpreter.{ IMain, IR }
import scala.concurrent._
import scala.concurrent.duration._
import scala.collection.JavaConversions._

import java.nio.file._

object Main {

  def main(args: Array[String]) {
    val (flows, restOfArgs) = args.span(_.matches(".*\\.(flow|fflow)"))

    val settings = new Settings
    settings.YmethodInfer.value = true
    settings.usejavacp.value = true
//    settings.debug.value = true
//    settings.log.value = List("typer")
//    settings.Yinferdebug.value = true
    settings.classpath.value = classpath()


    val compiler = new IMain(settings)
    val initialized = Promise[Unit]()
    compiler.initialize {
      //insert restOfArgs into compiler
      require(compiler.bind("args", "Seq[String]", restOfArgs.to[Seq]) == IR.Success, "Could not bind args")

      require(compiler.bind("interpreter", compiler) == IR.Success, "Could not bind interpreter")
      //declare basic imports
      if (compiler.interpret("""import uy.com.netlabs.luthier._
                                import uy.com.netlabs.luthier.typelist._
                                import scala.language._""") != IR.Success) initialized.failure(new IllegalStateException("Could not add default imports"))
      else initialized.success(())
    }

    val runner = AppContext.build("Runner")
    val runnerFlows = new Flows {
      val appContext = runner
    }

    lazy val lazyCompiler = {
      if (!initialized.isCompleted) println("Waiting for compiler to finish initializing")
      Await.result(initialized.future, Duration.Inf)
      compiler
    }

    //instantiate the flows:
    for (f <- flows) {
      val h = new FlowHandler(lazyCompiler, runner.actorSystem.log, f)
      h.load(runnerFlows.appContext)() //attempt to initialize it synchronously
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

    val baseDir = Paths.get(".")
    val (basePathForLibs, baseUrl) = getClass.getResource(getClass.getSimpleName + ".class") match {
      case null => throw new IllegalStateException("Could not deduct where I'm running from!")
      case u =>
        val p = u.toString
        val pathToJar = p.substring(0, p.lastIndexOf('!'))
        Paths.get(pathToJar.stripPrefix("jar:file:")).getParent -> pathToJar
    }
    //having found myself in this universe
    val manifest = new java.util.jar.Manifest(new java.net.URL(baseUrl + "!/META-INF/MANIFEST.MF").openStream())
    val mainAttrs = manifest.getMainAttributes()
    val cpInManifest = mainAttrs.getValue(java.util.jar.Attributes.Name.CLASS_PATH)

    val urlsFromManifest = cpInManifest.split(" ").map(j => j.split("/").foldLeft(basePathForLibs)((d, p) => d.resolve(p)))
    val allUrls = urlsFromClasspath ++ urlsFromManifest

    println("Using classpath:")
    allUrls foreach println
    allUrls.map(_.toString).mkString(":")
  }
}
