import AssemblyKeys._

assemblySettings

mainClass in assembly := Some("uy.com.netlabs.esb.runner.Main")

//elude evil old dispatch and geronimo-servlet
excludedJars in assembly <<= (fullClasspath in assembly) map { cp => 
  cp filter {a => 
    val file = a.data
    (file.getAbsolutePath contains "net.databinder.dispatch/core_2.9.2/jars/core_2.9.2-0.9.1.jar") ||
    file.getName == "geronimo-servlet_2.5_spec-1.1.2.jar"
  }
}

//aggregate cxf bus extensions
mergeStrategy in assembly <<= (mergeStrategy in assembly) { (old) =>
  {
    case PathList(xs @ _*) if xs.last == "bus-extensions.txt" => MergeStrategy.concat
    case PathList(xs @ _*) if xs.last == "wsdl.plugin.xml" => MergeStrategy.filterDistinctLines
    case PathList(xs @ _*) if xs.last == "spring.tooling" => MergeStrategy.filterDistinctLines
    case PathList(xs @ _*) if xs.last endsWith ".html" => MergeStrategy.filterDistinctLines
    case x => old(x)
  }
}

libraryDependencies <+= scalaVersion {sv => "org.scala-lang" % "scala-compiler" % sv}
