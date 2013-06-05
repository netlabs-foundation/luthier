{ //cxf dependencies
val cxfVersion = "2.7.5"
libraryDependencies ++= Seq(
  //"org.apache.cxf" % "cxf" % cxfVersion,
  "org.apache.cxf" % "cxf-api" % cxfVersion,
  "org.apache.cxf" % "cxf-rt-frontend-jaxws" % cxfVersion,
  "org.apache.cxf" % "cxf-rt-transports-http" % cxfVersion,
  "org.apache.cxf" % "cxf-rt-transports-http-jetty" % cxfVersion
)
}
