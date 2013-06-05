{ //cxf dependencies
val cxfVersion = "2.7.5"
libraryDependencies ++= Seq(
  //"org.apache.cxf" % "cxf" % cxfVersion,
  "org.apache.cxf" % "cxf-api" % cxfVersion exclude("log4j", "log4j"),
  "org.apache.cxf" % "cxf-rt-frontend-jaxws" % cxfVersion,
  "org.apache.cxf" % "cxf-rt-transports-http" % cxfVersion,
  "org.apache.cxf" % "cxf-rt-transports-http-jetty" % cxfVersion,
  "log4j" % "log4j" % "1.2.16" // attempt to fix the broken maven dependency.
)
}
