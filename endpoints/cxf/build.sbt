{ //cxf dependencies
val cxfRtVersion = "3.1.1"
libraryDependencies ++= Seq(
  //"org.apache.cxf" % "cxf" % cxfRtVersion,
  "org.apache.cxf" % "cxf-api" % "2.7.16" exclude("log4j", "log4j"),
  "org.apache.cxf" % "cxf-rt-frontend-jaxws" % cxfRtVersion,
  "org.apache.cxf" % "cxf-rt-transports-http" % cxfRtVersion,
  "org.apache.cxf" % "cxf-rt-transports-http-jetty" % cxfRtVersion,
  "log4j" % "log4j" % "1.2.17" // attempt to fix the broken maven dependency.
)
}
