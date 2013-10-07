package activator.cache

import sbt.IO
import java.io._
import org.junit.Assert._
import org.junit._
import scala.concurrent.Future
import scala.concurrent.Await
import scala.concurrent.duration._
import activator._

class ActionsTest {
  import scala.concurrent.ExecutionContext.Implicits.global
  class Dummy(dir: File) {
    // Setup dummies for test.
    val templateFile = new java.io.File(dir, "template-file")
    templateFile.createNewFile()
    val id = "1"
    val buildSbtFile = new File(dir, "template-build.sbt")
    IO.write(buildSbtFile, "\nname \t:= \t\"Hello\"\n")

    val confDir = new File(dir, "conf")
    IO.createDirectory(confDir)
    val confFile = new File(confDir, "application.conf")
    IO.write(confFile, "application.secret=\"FOO\"")
    val indexStored = IndexStoredTemplateMetadata(
      id = id,
      name = "foo",
      title = "The Foo",
      description = "This is a foo",
      authorName = "bob",
      authorLink = "http://example.com/bob",
      tags = Seq.empty,
      timeStamp = 1L,
      featured = true,
      usageCount = None,
      templateTemplate = true,
      sourceLink = "http://example.com/source")

    val metadataFile = new java.io.File(dir, Constants.METADATA_FILENAME)

    // braces so "props" isn't a class member
    {
      val props = new java.util.Properties
      for (
        pair <- Seq("name" -> indexStored.name, "title" -> indexStored.title,
          "description" -> indexStored.description, "authorName" -> indexStored.authorName,
          "authorLink" -> indexStored.authorLink, "featured" -> indexStored.featured.toString,
          "templateTemplate" -> indexStored.templateTemplate.toString)
      ) {
        props.setProperty(pair._1, pair._2)
      }
      IO.writeProperties(metadataFile, props, "Testing")
    }

    val tutorialDir = new java.io.File(dir, Constants.TUTORIAL_DIR)
    val tutorialIndex = new java.io.File(tutorialDir, "index.html")
    val tutorialOtherFile = new java.io.File(tutorialDir, "foo.png")
    IO.createDirectory(tutorialDir)
    IO.touch(tutorialIndex)
    IO.touch(tutorialOtherFile)

    object DummyCache extends TemplateCache {
      val m = TemplateMetadata(indexStored,
        locallyCached = true)

      override val metadata = Future(Seq(m))
      override val featured: Future[Iterable[TemplateMetadata]] = metadata
      override def template(id: String) =
        Future(Some(Template(m, Seq(
          templateFile -> "installed-file",
          templateFile -> "a/b/c/d/e/f/g/inside-some-directories",
          templateFile -> "project/build.properties",
          confFile -> "conf/application.conf",
          metadataFile -> Constants.METADATA_FILENAME,
          buildSbtFile -> "build.sbt"))))
      override def tutorial(id: String) = Future.successful {
        Some(Tutorial(id, Map("index.html" -> tutorialIndex,
          "foo.png" -> tutorialOtherFile)))
      }
      override def search(query: String) = metadata
    }
    val installLocation = new java.io.File(dir, "template-install")

    assertTrue("install location doesn't exist yet", !installLocation.exists)
  }

  @Test
  def testCloneTemplate(): Unit = IO.withTemporaryDirectory { dir =>
    val dummy = new Dummy(dir)
    import dummy._

    // Run the command
    val result = Actions.cloneTemplate(DummyCache, id, installLocation, projectName = None, filterMetadata = true)

    Await.result(result, Duration(5, SECONDS)) match {
      case ProcessFailure(errors) =>
        throw new AssertionError("clone failed " + errors)
      case _ =>
    }

    // Now verify it worked!
    assertTrue("install location now exists", installLocation.exists && installLocation.isDirectory)
    val installedFile = new File(installLocation, "installed-file")
    assertTrue("installed-file was copied", installedFile.exists)
    // TODO - Check contents of the file, after we make the file.

    assertTrue("created parent directory for an installed file", new File(installLocation, "a/b/c/d/e/f/g").exists)
    assertTrue("created a file nested inside directories", new File(installLocation, "a/b/c/d/e/f/g/inside-some-directories").exists)

    // Check that template ID was successfully written out.
    val props =
      IO.readProperties(new File(installLocation, "project/build.properties"))
    assertTrue("template ID was written to project/build.properties", props.getProperty(Constants.TEMPLATE_UUID_PROPERTY_NAME) == id)

    // check that we did NOT copy the metadata
    assertTrue("did not copy activator.properties", !new File(installLocation, Constants.METADATA_FILENAME).exists)
    assertTrue("did not copy tutorial dir", !new File(installLocation, Constants.TUTORIAL_DIR).exists)
  }

  @Test
  def testAdditionalFilesInClone(): Unit = IO.withTemporaryDirectory { dir =>
    val dummy = new Dummy(dir)
    import dummy._

    val addFile = new File(dir, "test.txt")
    val addFileContents = "SAMPLE FILE"
    IO.write(addFile, addFileContents)
    val addFileName = "addfile.txt"

    // Run the command
    val result = Actions.cloneTemplate(
      DummyCache,
      id,
      installLocation,
      projectName = None,
      filterMetadata = true,
      additionalFiles = Seq(addFile -> addFileName))

    Await.result(result, Duration(5, SECONDS)) match {
      case ProcessFailure(errors) =>
        throw new AssertionError("clone failed " + errors)
      case _ =>
    }

    // Now verify it worked!
    assertTrue("install location now exists", installLocation.exists && installLocation.isDirectory)
    val installedFile = new File(installLocation, "installed-file")
    assertTrue("installed-file was copied", installedFile.exists)
    // TODO - Check contents of the file, after we make the file.

    assertTrue("created parent directory for an installed file", new File(installLocation, "a/b/c/d/e/f/g").exists)
    assertTrue("created a file nested inside directories", new File(installLocation, "a/b/c/d/e/f/g/inside-some-directories").exists)

    // Check that template ID was successfully written out.
    val props =
      IO.readProperties(new File(installLocation, "project/build.properties"))
    assertTrue("template ID was written to project/build.properties", props.getProperty(Constants.TEMPLATE_UUID_PROPERTY_NAME) == id)

    // check that we did NOT copy the metadata
    assertTrue("did not copy activator.properties", !new File(installLocation, Constants.METADATA_FILENAME).exists)
    assertTrue("did not copy tutorial dir", !new File(installLocation, Constants.TUTORIAL_DIR).exists)
    val installedAddFile = new File(installLocation, addFileName)
    assertTrue("did not copy additional file: " + installedAddFile, installedAddFile.exists)
    assertEquals("Did not corretly copy additional files: ", addFileContents, IO.read(installedAddFile))
  }

  @Test
  def testRenameProject(): Unit = IO.withTemporaryDirectory { dir =>
    val dummy = new Dummy(dir)
    import dummy._

    // this name needs escaping as regex, as string literal, etc.
    val newName = "test\"\"\" foo bar \"\"\" $1 $2 \n blah blah \\n \\ what"
    val result = Actions.cloneTemplate(DummyCache, id, installLocation, projectName = Some(newName), filterMetadata = true)

    Await.result(result, Duration(5, SECONDS)) match {
      case ProcessFailure(errors) =>
        throw new AssertionError("clone failed " + errors)
      case _ =>
    }

    val contents = IO.read(new File(installLocation, "build.sbt"))
    // see if this makes your head hurt
    assertEquals("\nname \t:= \t\"\"\"test\"\"\"+ \"\\\"\\\"\\\"\" + \"\"\" foo bar \"\"\"+ \"\\\"\\\"\\\"\" + \"\"\" $1 $2 \n blah blah \\n \\ what\"\"\"\n",
      contents)
  }

  @Test
  def testNewSecretInProject(): Unit = IO.withTemporaryDirectory { dir =>
    val dummy = new Dummy(dir)
    import dummy._

    val result = Actions.cloneTemplate(DummyCache, id, installLocation, projectName = Some("foo"), filterMetadata = true)

    Await.result(result, Duration(5, SECONDS)) match {
      case ProcessFailure(errors) =>
        throw new AssertionError("clone failed " + errors)
      case _ =>
    }

    val confDir = new File(installLocation, "conf")
    val props = new java.util.Properties
    IO.reader(new File(confDir, "application.conf"))(props.load)

    assertNotSame("foo", props.getProperty("application.secret"))
    assertNotSame("FOO", props.getProperty("application.secret"))
  }

  @Test
  def testCloneTemplateWithMetadata(): Unit = IO.withTemporaryDirectory { dir =>
    val dummy = new Dummy(dir)
    import dummy._

    val newName = "bar-baz"

    // Run the command
    val result = Actions.cloneTemplate(DummyCache, id, installLocation, projectName = Some(newName), filterMetadata = false)

    Await.result(result, Duration(5, SECONDS)) match {
      case ProcessFailure(errors) =>
        throw new AssertionError("clone failed " + errors)
      case _ =>
    }

    assertTrue("did copy install-file", new File(installLocation, "installed-file").exists)

    // check that we DID copy the metadata
    assertTrue("did copy activator.properties", new File(installLocation, Constants.METADATA_FILENAME).exists)
    assertTrue("did copy tutorial dir", new File(installLocation, Constants.TUTORIAL_DIR).exists)
    assertTrue("did copy tutorial index", new File(installLocation, Constants.TUTORIAL_DIR + "/index.html").exists)
    assertTrue("did copy other tutorial file", new File(installLocation, Constants.TUTORIAL_DIR + "/foo.png").exists)

    // check that we also UPDATED the metadata
    val props = IO.readProperties(new File(installLocation, Constants.METADATA_FILENAME))
    // should have changed the name
    assertEquals(newName, props.getProperty("name"))
    // should have removed "templateTemplate" if present
    assertNull(props.getProperty("templateTemplate"))
  }

}
