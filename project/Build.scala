// vim: set ts=2 sw=4 et:
import sbt._, Keys._

object CocoonBuild extends Build {

  val _scalaVersion = "2.10.0"

  val defSettings = Seq(
    version := "2.0.0-SNAPSHOT",
    organization := "uy.com.netlabs",
    scalaVersion := _scalaVersion,
    fork := true,
    fork in test := true,
    exportJars := true,
    resolvers ++= Seq(
      "Local maven repo" at "file://" + Path.userHome + "/.m2/repository/",
      Resolver.sonatypeRepo("snapshots"), Resolver.sonatypeRepo("releases")
    ),
    libraryDependencies ++= Seq(
      "org.scala-lang" % "scala-reflect" % _scalaVersion,
      "org.scalatest" %% "scalatest" % "2.0.M5b" % "test"
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
      "-implicits-show-all"
      //"-expand-all-types" 
    ),
    initialCommands in console += "import uy.com.netlabs.luthier._",
    publishTo := Some(Resolver.file("file",  new File(Path.userHome.absolutePath+"/.m2/repository")))
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
    syslogEndpoint,
    xmppEndpoint,
    ircEndpoint,
    wsutil,
    luthierRunner,
    veditor
  ).settings(defSettings:_*)
  lazy val core = Project(id = "luthier-core", base = file("core")).settings(defSettings:_*)
  val coreAsDep = core % "compile->compile;test->test"
  lazy val logicalEndpoints = Project(id = "luthier-endpoint-logical", base = file("endpoints/logical")).dependsOn(coreAsDep).settings(defSettings:_*)
  lazy val fileEndpoint = Project(id = "luthier-endpoint-file", base = file("endpoints/file")).dependsOn(coreAsDep, logicalEndpoints).settings(defSettings:_*)
  lazy val jmsEndpoint = Project(id = "luthier-endpoint-jms", base = file("endpoints/jms")).dependsOn(coreAsDep, logicalEndpoints).settings(defSettings:_*)
  lazy val jdbcEndpoint = Project(id = "luthier-endpoint-jdbc", base = file("endpoints/jdbc")).dependsOn(coreAsDep, logicalEndpoints).settings(defSettings:_*)
  lazy val httpEndpoint = Project(id = "luthier-endpoint-http", base = file("endpoints/http")).dependsOn(coreAsDep, logicalEndpoints).settings(defSettings:_*)
  lazy val cxfEndpoint = Project(id = "luthier-endpoint-cxf", base = file ("endpoints/cxf")).dependsOn(coreAsDep, logicalEndpoints).settings(defSettings:_*)
  lazy val streamEndpoint = Project(id = "luthier-endpoint-stream", base = file ("endpoints/stream")).dependsOn(coreAsDep, logicalEndpoints).settings(defSettings:_*)
  lazy val syslogEndpoint = Project(id = "luthier-endpoint-syslog", base = file ("endpoints/syslog")).dependsOn(coreAsDep, logicalEndpoints).settings(defSettings:_*)
  lazy val xmppEndpoint = Project(id = "luthier-endpoint-xmpp", base = file ("endpoints/xmpp")).dependsOn(coreAsDep, logicalEndpoints).settings(defSettings:_*)
  lazy val ircEndpoint = Project(id = "luthier-endpoint-irc", base = file ("endpoints/irc")).dependsOn(coreAsDep, logicalEndpoints).settings(defSettings:_*)
  lazy val wsutil = Project(id = "luthier-wsutil", base = file("wsutil")).settings(defSettings:_*)

  lazy val luthierRunner = Project(id = "luthier-runner", base = file("luthier-runner")).settings(defSettings:_*).
    dependsOn(core, logicalEndpoints, fileEndpoint, jmsEndpoint, jdbcEndpoint, httpEndpoint, cxfEndpoint, streamEndpoint, syslogEndpoint, xmppEndpoint, ircEndpoint)

  lazy val veditor = Project(id = "luthier-visual-editor", base = file("veditor")).settings(defSettings:_*).dependsOn(core, luthierRunner)
}
