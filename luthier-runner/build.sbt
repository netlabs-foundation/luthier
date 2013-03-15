mainClass := Some("uy.com.netlabs.esb.runner.Main")

libraryDependencies <+= scalaVersion {sv => "org.scala-lang" % "scala-compiler" % sv}

libraryDependencies ++= Seq(
  "net.sf.opencsv" % "opencsv" % "2.0"
)
