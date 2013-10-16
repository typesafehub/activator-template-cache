/**
 * Copyright (C) 2013 Typesafe <http://typesafe.com/>
 */
import sbt._

object Dependencies {
  val scalaVersion = "2.10.2"
  val scalaBinaryVersion = "2.10"

  val sbtDependencyVersion = "0.13.0"
  val akkaVersion = "2.2.0"
  val luceneVersion = "4.3.0"


  val sbtIo = "org.scala-sbt" % "io" % sbtDependencyVersion

  val jgit = "org.eclipse.jgit" % "org.eclipse.jgit.pgm" % "2.2.0.201212191850-r"

  val akkaActor            = "com.typesafe.akka" % "akka-actor_2.10" % akkaVersion

  val lucene = "org.apache.lucene" % "lucene-core" % luceneVersion
  val luceneAnalyzerCommon = "org.apache.lucene" % "lucene-analyzers-common" % luceneVersion
  val luceneQueryParser = "org.apache.lucene" % "lucene-queryparser" % luceneVersion

  val amazonWS = "com.amazonaws" % "aws-java-sdk" % "1.3.29"

  val junitInterface       = "com.novocode" % "junit-interface" % "0.7"
  val specs2               = "org.specs2" % "specs2_2.10" % "1.13"

  // Mini DSL
  // DSL for adding remote deps like local deps.
  implicit def p2remote(p: Project): RemoteDepHelper = new RemoteDepHelper(p)
  final class RemoteDepHelper(p: Project) {
    def dependsOnRemote(ms: ModuleID*): Project = p.settings(Keys.libraryDependencies ++= ms)
  }
}
