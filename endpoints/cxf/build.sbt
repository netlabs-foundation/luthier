{ //cxf dependencies
val cxfVersion = "2.6.2"
libraryDependencies ++= Seq(
  "org.apache.cxf" % "cxf" % cxfVersion,
  "org.apache.cxf" % "cxf-api" % cxfVersion,
  "org.apache.cxf" % "cxf-rt-transports-http-jetty" % cxfVersion
)
}
