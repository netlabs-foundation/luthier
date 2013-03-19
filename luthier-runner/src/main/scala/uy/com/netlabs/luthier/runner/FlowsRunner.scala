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
import scala.util._
import scala.concurrent._
import scala.concurrent.duration._
import scala.collection.JavaConversions._
import java.nio.file._
import org.slf4j._
import scala.annotation.varargs

class FlowsRunner(val appContext: AppContext,
                  protected val compilerSettings: Settings, classLoader: ClassLoader) extends InterpretersComponent {

  protected val logger = FlowsRunner.logger
  private[this] val initialized = Promise[Unit]()

  val runnerFlows = new Flows {
    val appContext = FlowsRunner.this.appContext
  }

  def this(appContextName: String, classLoader: ClassLoader) = this(AppContext.build(appContextName, Paths.get("")),
                                                                    FlowsRunner.defaultCompilerSettings(classLoader), classLoader)
  def this(classLoader: ClassLoader) = this("Runner", classLoader)

  @varargs def load(flows: Path*) = flows.map { path =>
    val h = new FlowHandler(Interpreters.newInterpreter(path.getFileName.toString), appContext.actorSystem.log, path.toAbsolutePath().toString)
    h.load(runnerFlows.appContext)() //attempt to initialize it synchronously
    h.startWatching(runnerFlows)
    h
  }.to[Array]

  /**
   * Disposes this FlowsRunner shutting down its compiler instance and its actor system, this in turn
   * causes the monitoring of flows to stop, but *not* the running flows. To stop them you must use
   * their respective ``FlowHandler``s
   */
  def dispose() {
    val s1 = Try(runnerFlows.appContext.actorSystem.shutdown())
    val s2 = Try(runnerFlows.stopAllFlows())
    Interpreters.disposeAll()
    //re throw the first exception encountered... Possibly not the best of logics?
    s1.get
    s2.get
  }
}
object FlowsRunner {
  private[FlowsRunner] val logger = LoggerFactory.getLogger(classOf[FlowsRunner])
  def defaultCompilerSettings(parentClassLoader: ClassLoader) = {
    val settings = new Settings

    settings.YmethodInfer.value = true
    val cp = classpath(parentClassLoader)
    logger.info("Using classpath:\n\t" + cp.mkString("\n\t"))
    settings.classpath.value = cp.mkString(java.io.File.pathSeparator)
    settings
  }

  private[this] def classpath(parentClassLoader: ClassLoader) = {
    import java.net.URLClassLoader
    def cp(cl: ClassLoader): Seq[java.io.File] = cl match {
      case null               => Seq.empty
      case cl: URLClassLoader => cl.getURLs().map(u => new java.io.File(u.toURI())) ++ cp(cl.getParent)
      case other              => cp(other.getParent())
    }
    val urlsFromClasspath = Seq(getClass.getClassLoader(), parentClassLoader, ClassLoader.getSystemClassLoader()).flatMap(cp).distinct

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

    val urlsFromManifest = cpInManifest.split(" ").map(j => j.split("/").foldLeft(basePathForLibs)((d, p) => d.resolve(p))).map(_.toFile).filter(_.exists)
    val allUrls = urlsFromClasspath ++ urlsFromManifest

    allUrls
  }
}
