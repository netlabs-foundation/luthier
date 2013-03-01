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
package uy.com.netlabs.luthier.veditor

import java.nio.file._

object Test extends App {

  val analyzer = new FlowAnalyzer(classpath(getClass.getClassLoader).map(_.toString))
  analyzer.analyze(Paths.get(args(0)))



  private[this] def classpath(parentClassLoader: ClassLoader) = {
    import java.net.URLClassLoader
    def cp(cl: ClassLoader): Seq[java.io.File] = cl match {
      case null => Seq.empty
      case cl: URLClassLoader => cl.getURLs().map(u => new java.io.File(u.toURI())) ++ cp(cl.getParent)
      case other => cp(other.getParent())
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
