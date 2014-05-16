/**
 * Copyright (C) 2013 Typesafe <http://typesafe.com/>
 */
import sbt._
import Keys._

object ActivatorTemplatesBuild extends Build {
  import Dependencies._
  import ActivatorBuild._

  override val settings: Seq[Setting[_]] = ActivatorBuild.baseVersions ++ super.settings

  lazy val root = (
    Project("root", file("."))
    aggregate(common, templatesCache)
    settings(
      publish := {},
      publishLocal := {}
    )
  )

  // cut-and-paste of sbt.control used by cut-and-paste of sbt.io below
  lazy val sbtcontrol = (
    ActivatorProjectNoFormatting("sbtcontrol", "asbt/control")
  )

  // cut-and-paste of sbt.io so we can use a different scala version
  lazy val sbtio = (
    ActivatorProjectNoFormatting("sbtio", "asbt/io")
    dependsOnRemote(
      // when we drop this cut-and-paste we won't want this so
      // it's just here and not in dependencies.scala like specs
      "org.scalacheck" %% "scalacheck" % "1.11.1" % "test",
      "org.scala-lang" % "scala-compiler" % Dependencies.scalaVersion % "test"
    )
    dependsOn(sbtcontrol)
  )

  // Common utilities, like "ProcessResult" monad and hashing.
  lazy val common = (
    ActivatorProject("common")
    dependsOnRemote(
      commonsCompress
    )
    dependsOn(
      sbtio % "test"
    )
  )

  // basic template cache support, including the lucene index and general data structures.
  lazy val templatesCache = (
    ActivatorProject("templates-cache")
    dependsOnRemote(
      lucene, 
      luceneAnalyzerCommon, 
      luceneQueryParser,
      akkaActor,
      amazonWS 
    )
    dependsOn(common, sbtio)
  )
}
