libraryDependencies ++= Seq(
  "org.apache.geronimo.specs" % "geronimo-jms_1.1_spec" % "1.1.1", //see http://stackoverflow.com/questions/6334323/what-is-the-right-maven-dependency-for-javax-jms-classes
  "org.apache.activemq" % "activemq-core" % "5.5.1" % "optional",
  "org.apache.activemq" % "activemq-pool" % "5.5.1" % "optional",
  "org.slf4j" % "slf4j-log4j12" % "1.7.5"
)
