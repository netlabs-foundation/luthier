resolvers += "Typesafe Repository" at "http://repo.typesafe.com/typesafe/releases/"


libraryDependencies ++= Seq(
  //"com.github.scala-incubator.io" % "scala-io-core_2.10.0-M4" % "0.4.1-seq",
  //"com.github.scala-incubator.io" % "scala-io-file_2.10.0-M4" % "0.4.1-seq"
  "com.typesafe.akka" %% "akka-actor" % "2.1.0",
  "com.typesafe.akka" %% "akka-cluster-experimental" % "2.1.0"
)
