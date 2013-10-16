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

  // Common utilities, like "ProcessResult" monad and hashing.
  lazy val common = (
    ActivatorProject("common")
    dependsOnRemote(
      sbtIo % "test"
    )
  )

  // basic template cache support, including the lucene index and general data structures.
  lazy val templatesCache = (
    ActivatorProject("templates-cache")
    dependsOnRemote(
      sbtIo,
      lucene, 
      luceneAnalyzerCommon, 
      luceneQueryParser,
      akkaActor,
      amazonWS 
    )
    dependsOn(common)
  )
}
