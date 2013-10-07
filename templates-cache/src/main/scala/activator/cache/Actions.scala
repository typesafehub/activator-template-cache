package activator
package cache

import sbt.IO
import java.io.File
import java.util.regex.Matcher
import java.io.FileNotFoundException
import scala.concurrent.Future
import scala.concurrent.ExecutionContext

/**
 * this class contains all template cache actions for both the UI and the console-based
 * methods.  We put it here for easier testing (at least we hope we can get easier testing).
 */
object Actions {
  private def stringLiteral(s: String): String = {
    // sorry!
    "\"\"\"" + s.replaceAllLiterally("\"\"\"", "\"\"\"" + """+ "\"\"\"" + """ + "\"\"\"") + "\"\"\""
  }

  // this does nothing if it can't figure out how to perform the rename,
  // but should throw if a rename looks possible but fails due to e.g. an IO error
  private def bestEffortRename(basedir: java.io.File, projectName: String): Unit = {
    for (
      file <- Seq(
        new File(basedir, "build.sbt"),
        new File(basedir, "project/Build.scala"))
        .filter(_.exists)
        .headOption
    ) {
      val contents = IO.read(file)

      val modified = contents.replaceFirst("(name[ \t]*:=[ \t]*)\"[^\"]+\"", "$1" + Matcher.quoteReplacement(stringLiteral(projectName)));
      if (modified != contents) {
        IO.write(file, modified)
      }
    }
  }

  def makeNewApplicationSecret(): String = {
    // Mime the current play application secret stuff.
    val random = new java.security.SecureRandom()
    val tmp = Stream.continually((random.nextInt(74) + 48).toChar).take(64).mkString
    tmp.replaceAll("\\\\+", "/")
  }

  private def bestEffortFixApplicationSecret(basedir: java.io.File): Unit = {
    // TODO - use BOM or user configurable charset?
    val charset = java.nio.charset.Charset.forName("UTF-8")
    // TODO - we should look for ANY conf directory with an application.conf in it...
    val applicationConf = new File(basedir, "conf/application.conf")
    // helper to replace the secret in a given file.
    def replaceSecret(file: File): Unit = {
      val contents = IO.read(file, charset)
      val modified = contents.replaceFirst("(application.secret[ \t]*=[ \t]*)\"[^\"]+\"", "$1" + Matcher.quoteReplacement(makeNewApplicationSecret()));
      if (modified != contents) {
        IO.write(file, modified, charset)
      }
    }
    for {
      file <- Seq(applicationConf)
      if file.exists
    } replaceSecret(file)
  }

  private def fixupMetadataFile(file: File, projectNameOption: Option[String]): Unit = {
    // now rename the url-friendly name in activator.properties if
    // this was copied as a template-template. We don't load
    // using MetadataReader since we don't want to do a bunch of
    // validation and what-have-you here.
    val props = IO.readProperties(file)
    // drop the templateTemplate flag (this will become a regular template by default)
    props.remove("templateTemplate")
    // if the projectName isn't url-friendly, the template rest server will reject it
    // and the person will have to fix it up.
    projectNameOption.foreach(props.setProperty("name", _))
    IO.writeProperties(file, props, "Activator properties file")
  }

  // This method will clone a template to a given location
  def cloneTemplate(
    cache: TemplateCache,
    id: String,
    location: java.io.File,
    projectName: Option[String],
    filterMetadata: Boolean,
    additionalProps: Map[String, String] = Map.empty,
    additionalFiles: Seq[(File, String)] = Seq.empty)(
      implicit ctx: ExecutionContext): Future[ProcessResult[Unit]] =
    for {
      // note: if these steps fail, we return a failed future, not a future with a ProcessFailure
      // sort of an easy problem to have with Future[ProcessResult] unfortunately.
      templateOpt <- cache.template(id)
      // don't look up tutorial if filtering metadata
      tutorialOpt <- if (filterMetadata) Future.successful(None) else cache.tutorial(id)
      tutorial = tutorialOpt.getOrElse(Tutorial(id = id, files = Map.empty))
      tutorialFiles = tutorial.files.map {
        case (pathUnderTutorialDir, file) =>
          (file, (new File(Constants.TUTORIAL_DIR, pathUnderTutorialDir)).getPath)
      }
    } yield {
      for {
        // TODO - Maybe the cache should use ProcessResult for better error propogation from the GET GO.
        // That way we know exactly what the failure is.
        template <- Validating(templateOpt getOrElse sys.error(s"Template ${id} not found, or unable to be downloaded."))
        _ <- Validating {
          if (location.exists && !location.list().isEmpty)
            throw new Exception(s"$location already has files in it")
        }
        _ <- Validating.withMsg(s"Failed to create $location") {
          if (!location.exists) IO createDirectory location
        }
        _ <- Validating.withMsg("Failed to copy template") {
          for {
            (file, path) <- template.files ++ tutorialFiles ++ additionalFiles
            if !(filterMetadata && (path == Constants.METADATA_FILENAME))
            to = new java.io.File(location, path)
          } if (file.isDirectory) IO.createDirectory(to)
          else {
            IO.copyFile(file, to)
            if (file.canExecute) to.setExecutable(true)

            if (path == Constants.METADATA_FILENAME)
              fixupMetadataFile(to, projectName)
          }
        }
        _ <- Validating.withMsg("Failed to update property file") {
          // Write necessary IDs to the properties file!
          val propsFile = new File(location, "project/build.properties")
          IO.createDirectory(propsFile.getParentFile)
          // TODO - Force sbt version?
          updateProperties(propsFile,
            Map(
              Constants.TEMPLATE_UUID_PROPERTY_NAME -> id) ++ additionalProps)
        }
        _ <- Validating.withMsg("Failed to rename project") {
          projectName.foreach(bestEffortRename(location, _))
        }
        _ <- Validating.withMsg("Failed to change application.secret") {
          bestEffortFixApplicationSecret(location)
        }
      } yield ()
    }

  private def updateProperties(propsFile: File, newProps: Map[String, String]): Unit = {
    val props = IO.readProperties(propsFile)
    // Updated props
    for {
      (key, value) <- newProps
    } props setProperty (key, value)
    // Write props
    IO.writeProperties(propsFile, props, "Activator-generated Properties")
  }
}
