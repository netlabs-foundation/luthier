libraryDependencies ++= Seq(
  "org.apache.geronimo.specs" % "geronimo-jms_1.1_spec" % "1.1.1", //see http://stackoverflow.com/questions/6334323/what-is-the-right-maven-dependency-for-javax-jms-classes
  "org.apache.activemq" % "activemq-core" % "5.7.0" % "optional"  exclude("log4j", "log4j"),
  "org.apache.activemq" % "activemq-pool" % "5.7.0" % "optional" exclude("log4j", "log4j"),
  "org.slf4j" % "slf4j-log4j12" % "1.7.5" % "optional" exclude("log4j", "log4j"),
  "log4j" % "log4j" % "1.2.16" % "optional"// attempt to fix the broken maven dependency
)
