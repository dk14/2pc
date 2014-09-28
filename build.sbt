name := "My Project"
 
version := "1.0"
 
scalaVersion := "2.11.1"

resolvers += "Typesafe Repository" at "http://repo.typesafe.com/typesafe/releases/"
 
libraryDependencies += "com.typesafe.akka" % "akka-actor_2.11" % "2.3.6"

libraryDependencies += "org.scalatest" % "scalatest_2.11" % "2.2.1" % "test"
