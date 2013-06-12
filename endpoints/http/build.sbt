{val jettyVersion = "8.1.11.v20130520"
libraryDependencies ++= Seq (
  "net.databinder.dispatch" %% "core" % "0.9.1",
  "net.databinder.dispatch" % "jsoup_2.9.2" % "0.9.1",
  "net.databinder" %% "unfiltered-filter" % "0.6.7",
  "net.databinder" %% "unfiltered-filter-async" % "0.6.7",
  "net.databinder" %% "unfiltered-jetty" % "0.6.7" exclude("javax.servlet", "servlet-api"),
  //"javax" % "javaee-api" % "6.0",
  "org.eclipse.jetty.aggregate" % "jetty-all-server" % jettyVersion % "optional"
)}
