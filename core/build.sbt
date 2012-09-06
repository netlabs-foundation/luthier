resolvers += "Typesafe Repository" at "http://repo.typesafe.com/typesafe/releases/"

libraryDependencies <++= scalaVersion {sv => Seq(
  //"com.github.scala-incubator.io" % "scala-io-core_2.10.0-M4" % "0.4.1-seq",
  //"com.github.scala-incubator.io" % "scala-io-file_2.10.0-M4" % "0.4.1-seq"
  "com.typesafe.akka" % "akka-actor_2.10.0-M7" % "2.1-M2"
  //"com.typesafe.akka" % "akka-cluster_2.10.0-M7" % "2.1-M2"
)}
