import sbt._, Keys._
import java.net.URI
import java.nio.file._
import collection.JavaConversions._

object Dist {
  val dist = TaskKey[Path]("dist", "Generates a zip with the application and its dependencies")
  val distArtifactSetting = artifact in dist <<= name {n => Artifact(n, "dist", "zip")}
  val packageOptionsSetting = packageOptions in (Compile, packageBin) <+= (dependencyClasspath in Compile) map {fc =>
    val jars = fc filter (_.data.getName endsWith ".jar")
    Package.ManifestAttributes(java.util.jar.Attributes.Name.CLASS_PATH -> jars.map(j => "lib/" + j.data.getName).mkString(" "))
  }

  val distSetting = dist <<= (dependencyClasspath in Compile, target, artifact in dist, packageBin in Compile) map {(fc, target, artifact, jar) =>
    val destZip = target.toPath.resolve(artifact.name + "." + artifact.extension)
    if (Files exists destZip) Files delete destZip
    val zipFs = FileSystems.newFileSystem(URI.create("jar:file:" + destZip + "!/"), Map("create"->"true"))
    val root = zipFs.getPath("/", artifact.name)
    Files.createDirectories(root)
    val libDir = root.resolve("lib")
    Files.createDirectories(libDir)
    try {
      for (a <- fc; p = a.data.toPath if p.getFileName.toString endsWith ".jar") {
        val dest = libDir.resolve(p.getFileName.toString)
        println("Copying " + p + " to " + dest)
        Files.copy(p, dest)
      }
      println("Copying main artifact " + jar)
      Files.copy(jar.toPath, root.resolve(jar.getName))
    } finally zipFs.close
    destZip
  }

  def settings = Seq(distArtifactSetting, packageOptionsSetting, distSetting)
}