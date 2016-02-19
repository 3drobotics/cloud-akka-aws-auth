name := "akka-http-aws-auth"

version := "1.1"

scalaVersion := "2.11.7"

organization := "cloud.drdrdr"

Defaults.itSettings
lazy val `AkkaHttpAWSAuth` = project.in(file(".")).configs(IntegrationTest)

resolvers += "Artifactory" at "https://dronekit.artifactoryonline.com/dronekit/libs-snapshot-local/"

credentials += Credentials(Path.userHome / ".sbt" / ".credentials")
isSnapshot := true
publishTo := {
  val artifactory = "https://dronekit.artifactoryonline.com/"
  if (isSnapshot.value)
    Some("snapshots" at artifactory + s"dronekit/libs-snapshot-local;build.timestamp=${new java.util.Date().getTime}")
  else
    Some("snapshots" at artifactory + "dronekit/libs-release-local")
}


libraryDependencies ++= {
  val akkaV = "2.4.2"
  Seq(
    "com.typesafe.akka" %% "akka-actor" % akkaV,
    "com.typesafe.akka" %% "akka-stream" % akkaV,
    "com.typesafe.akka" %% "akka-http-core" % akkaV,
    "com.typesafe.akka" %% "akka-http-experimental" % akkaV,
    "com.typesafe.akka" %% "akka-http-spray-json-experimental" % akkaV,
    "com.typesafe.akka" %% "akka-http-testkit" % akkaV,
    "io.spray" %%  "spray-json" % "1.3.2",
    "commons-codec" % "commons-codec" % "1.6",
    "org.scalatest" %% "scalatest" % "2.2.4" % "it,test",
    "org.scalamock" %% "scalamock-scalatest-support" % "3.2" % "it,test",
    "org.specs2" %% "specs2-core" % "2.4.14" % "it,test",
    "ch.qos.logback" % "logback-classic" % "1.1.3",
    "com.typesafe.scala-logging" %% "scala-logging" % "3.1.0",
    "joda-time" % "joda-time" % "2.8.2"
  )
}