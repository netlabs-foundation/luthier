mainClass := Some("uy.com.netlabs.esb.runner.Main")

libraryDependencies <+= scalaVersion {sv => "org.scala-lang" % "scala-compiler" % sv}
