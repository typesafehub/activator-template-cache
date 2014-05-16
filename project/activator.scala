/**
 * Copyright (C) 2013 Typesafe <http://typesafe.com/>
 */
import sbt._
import Keys._
import com.typesafe.sbt.SbtScalariform
import com.typesafe.sbt.SbtScalariform.ScalariformKeys
import com.typesafe.sbt.SbtGit

object ActivatorBuild {

	def baseVersions: Seq[Setting[_]] = SbtGit.versionWithGit

  // We give these different names than the deaults so we don't conflict....
  val typesafeIvyReleases = Resolver.url("typesafe-ivy-private-releases", new URL("http://private-repo.typesafe.com/typesafe/ivy-releases/"))(Resolver.ivyStylePatterns)
  // TODO - When SBT 0.13 is out we won't need this...
  val typesafeIvySnapshots = Resolver.url("typesafe-ivy-private-snapshots", new URL("http://private-repo.typesafe.com/typesafe/ivy-snapshots/"))(Resolver.ivyStylePatterns)


  // Scalariform Junk
  private val fixWhitespace = TaskKey[Seq[File]]("fix-whitespace")
  private def makeFixWhitespace(config: Configuration): Setting[_] = {
    fixWhitespace in config <<= (unmanagedSources in config, streams) map { (sources, streams) =>
      for (s <- sources) {
        Fixer.fixWhitespace(s, streams.log)
      }
      sources
    }
  }
  def formatPrefs = {
    import scalariform.formatter.preferences._
    FormattingPreferences()
      .setPreference(IndentSpaces, 2)
  }

  def activatorDefaultsNoFormatting: Seq[Setting[_]] =
    Seq(
      organization := "com.typesafe.activator",
      version <<= version in ThisBuild,
      crossPaths := false,
      resolvers += "typesafe-mvn-releases" at "http://repo.typesafe.com/typesafe/releases/",
      resolvers += Resolver.url("typesafe-ivy-releases", new URL("http://repo.typesafe.com/typesafe/releases/"))(Resolver.ivyStylePatterns),
      // TODO - This won't be needed when SBT 0.13 is released...
      resolvers += typesafeIvyReleases,
      resolvers += typesafeIvySnapshots,
      // TODO - Publish to ivy for sbt plugins, maven central otherwise?
      publishTo := Some(typesafeIvyReleases),
      publishMavenStyle := false,
      scalacOptions <<= (scalaVersion) map { sv =>
        Seq("-unchecked", "-deprecation") ++
          { if (sv.startsWith("2.9")) Seq.empty else Seq("-feature") }
      },
      javacOptions in Compile := Seq("-target", "1.6", "-source", "1.6"),
      javacOptions in (Compile, doc) := Seq("-source", "1.6"),
      scalaVersion := Dependencies.scalaVersion,
      scalaBinaryVersion := Dependencies.scalaBinaryVersion,
      libraryDependencies ++= Seq(Dependencies.junitInterface % "test", Dependencies.specs2 % "test"))

  def activatorDefaults: Seq[Setting[_]] =
    SbtScalariform.scalariformSettings ++
    activatorDefaultsNoFormatting ++
    Seq(
      ScalariformKeys.preferences in Compile := formatPrefs,
      ScalariformKeys.preferences in Test    := formatPrefs,
      makeFixWhitespace(Compile),
      makeFixWhitespace(Test),
      compileInputs in (Compile, compile) <<= (compileInputs in (Compile, compile)) dependsOn (fixWhitespace in Compile),
      compileInputs in (Test, compile) <<= (compileInputs in (Test, compile)) dependsOn (fixWhitespace in Test)
    )

  private def activatorProject(name: String, dir: File): Project =
    Project("activator-" + name, dir)

  def ActivatorProjectNoFormatting(name: String, dir: String): Project = (
    activatorProject(name, file(dir))
    settings(activatorDefaultsNoFormatting:_*)
  )

  def ActivatorProject(name: String): Project = (
    activatorProject(name, file(name))
    settings(activatorDefaults:_*)
  )
}
