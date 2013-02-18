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
