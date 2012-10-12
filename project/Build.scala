// vim: set ts=2 sw=4 et:
import sbt._, Keys._

object CocoonBuild extends Build {

  val _scalaVersion = "2.10.0-M7"

  val defSettings = Seq(
    version := "2.0.0-SNAPSHOT",
    organization := "uy.com.netlabs",
    scalaVersion := _scalaVersion,
    fork := true,
    fork in test := true,
    exportJars := true,
    resolvers ++= Seq(
      "Local maven repo" at "file://" + Path.userHome + "/.m2/repository/"
    ),
    libraryDependencies ++= Seq(
      "org.scala-lang" % "scala-reflect" % _scalaVersion,
      "org.scalatest" % "scalatest_2.10.0-M7" % "1.9-2.10.0-M7-B1"
    ),
    scalacOptions ++= Seq(
      "-feature",
      //"-explaintypes",
      "-unchecked",
      "-deprecation",
      "-Yinfer-argument-types"
      //"-Xlog-implicits"
    ),
    scalacOptions in Compile in doc ++= Seq(
      "-implicits-show-all",
      "-expand-all-types" 
    ),
    initialCommands += "import uy.com.netlabs.esb._",

    resolvers += Resolver.sonatypeRepo("snapshots")
  ) ++ Dist.settings

  lazy val root = Project(id = "Luthier", base = file(".")).aggregate(
    core,
    logicalEndpoints,
    fileEndpoint,
    jmsEndpoint,
    jdbcEndpoint,
    httpEndpoint,
    cxfEndpoint,
    streamEndpoint,
    wsutil
  ).settings(defSettings:_*)
  lazy val core = Project(id = "luthier-core", base = file("core")).settings(defSettings:_*)
  val coreAsDep = core % "compile->compile;test->test"
  lazy val logicalEndpoints = Project(id = "logicalEndpoints", base = file("endpoints/logical")).dependsOn(coreAsDep).settings(defSettings:_*)
  lazy val fileEndpoint = Project(id = "fileEndpoint", base = file("endpoints/file")).dependsOn(coreAsDep, logicalEndpoints).settings(defSettings:_*)
  lazy val jmsEndpoint = Project(id = "jmsEndpoint", base = file("endpoints/jms")).dependsOn(coreAsDep, logicalEndpoints).settings(defSettings:_*)
  lazy val jdbcEndpoint = Project(id = "jdbcEndpoint", base = file("endpoints/jdbc")).dependsOn(coreAsDep, logicalEndpoints).settings(defSettings:_*)
  lazy val httpEndpoint = Project(id = "httpEndpoint", base = file("endpoints/http")).dependsOn(coreAsDep, logicalEndpoints).settings(defSettings:_*)
  lazy val cxfEndpoint = Project(id = "cxfEndpoint", base = file ("endpoints/cxf")).dependsOn(coreAsDep, logicalEndpoints).settings(defSettings:_*)
  lazy val streamEndpoint = Project(id = "streamEndpoint", base = file ("endpoints/stream")).dependsOn(coreAsDep, logicalEndpoints).settings(defSettings:_*)
  lazy val wsutil = Project(id = "wsutil", base = file("wsutil")).settings(defSettings:_*)

  lazy val luthierRunner = Project(id = "luthier-runner", base = file("luthier-runner")).settings(defSettings:_*).
    dependsOn(core, logicalEndpoints, fileEndpoint, jmsEndpoint, jdbcEndpoint, httpEndpoint, cxfEndpoint, streamEndpoint)
}
