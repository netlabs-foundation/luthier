{
val jettyVersion = "9.0.3.v20130506"
val dispatchVersion = "0.11.3"
val unfilteredVersion = "0.8.4"
libraryDependencies ++= Seq (
  "net.databinder.dispatch" %% "dispatch-core" % dispatchVersion,
  "net.databinder.dispatch" %% "dispatch-jsoup" % dispatchVersion,
  "net.databinder" %% "unfiltered-filter" % unfilteredVersion,
  "net.databinder" %% "unfiltered-filter-async" % unfilteredVersion,
  "net.databinder" %% "unfiltered-jetty" % unfilteredVersion
  //"org.eclipse.jetty.aggregate" % "jetty-all" % jettyVersion % "optional"
)}
