name := "server"

version := "0.1-SNAPSHOT"

scalaVersion := "2.11.2"

scalacOptions ++= Seq(
    "-feature", "-deprecation", "-optimise", "-Xlint", "-Ywarn-dead-code", "-Ywarn-unused", "-Ywarn-unused-import",
    "-Ywarn-value-discard"
  )

libraryDependencies ++= Seq(
    "org.igniterealtime.smack" % "smack-core" % "4.0.4",
    "org.igniterealtime.smack" % "smack-tcp" % "4.0.4",
    "com.googlecode.json-simple" % "json-simple" % "1.1.1"
  )
