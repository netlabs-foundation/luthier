//mainClass := Some("com.belfry.rolmanager.Main")

//packageOptions in (Compile, packageBin) += Package.MainClass("com.belfry.rolmanager.Main")

publishTo := Some(Resolver.file("file",  new File(Path.userHome.absolutePath+"/.m2/repository")))

libraryDependencies <++= scalaVersion {sv=>Seq(
  "org.jfxtras" % "jfxtras-labs" % "2.2-r4",
  "org.scala-lang" % "scala-compiler" % sv
  //"com.belfry.miscellaneous" %% "serialization" % "2.0-SNAPSHOT",
  //"com.belfry.miscellaneous" %% "resource" % "1.0-SNAPSHOT",
)}

unmanagedJars in Compile += Attributed.blank(file(util.Properties.javaHome + "/lib/jfxrt.jar"))
