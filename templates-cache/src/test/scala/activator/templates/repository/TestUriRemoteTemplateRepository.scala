package activator
package templates
package repository

import cache._
import java.net.URI
import java.io.File
import sbt.{ IO, PathFinder }

import org.junit._
import Assert._

class TestUriRemoteTemplateRepository {
  // Helper to make a dummy zip file.
  def makeDummyZip(filenamesAndContents: Map[String, String], location: File): Unit = {
    IO.withTestTempDirectory { tmpDir =>
      for {
        (filename, contents) <- filenamesAndContents
        file = new File(tmpDir, filename)
      } IO.write(file, contents)

      // Now zip it all
      val zipFiles =
        for {
          file <- (PathFinder(tmpDir).*** --- PathFinder(tmpDir)).get
          name <- IO.relativize(tmpDir, file)
        } yield file -> name

      IO.zip(zipFiles, location)
    }
  }

  val template1Id = "ID-1"
  val template1 = Map(
    "build.sbt" -> """name := "hello" """)
  val index = Map(
    "dummy.db" -> "Dummy DB")
  var hash: String = ""

  def makeRepo(dir: File, binaryMajorVersion: Int = Constants.INDEX_BINARY_MAJOR_VERSION,
    binaryIncrementVersion: Int = Constants.INDEX_BINARY_INCREMENT_VERSION): Unit = {
    val paths = new Layout(dir.toURI)
    IO.withTestTempDirectory { tmpDir =>
      val zip = new File(tmpDir, "test.zip")
      makeDummyZip(index, zip)
      hash = hashing.hash(zip)
      val location = paths.index(hash)
      IO.move(zip, new File(location))
      // TODO - Create zip properties!
      val propsFile = new File(paths.currentIndexTag)
      IO.touch(propsFile)
      val cacheProps = new CacheProperties(propsFile)
      cacheProps.cacheIndexHash = hash
      cacheProps.cacheIndexBinaryMajorVersion = binaryMajorVersion
      cacheProps.cacheIndexBinaryIncrementVersion = binaryIncrementVersion
      cacheProps.save("Updated hash")
    }
    val template1Location = paths.template(template1Id)
    makeDummyZip(template1, new File(template1Location))
  }

  @Test
  def shouldResolveTemplatesFromRemote(): Unit = {
    IO.withTestTempDirectory { tmpDir =>
      // TODO - Make a zip
      val repo = new File(tmpDir, "remote-repo")
      IO.createDirectory(repo)
      makeRepo(repo)

      val remote = new UriRemoteTemplateRepository(repo.toURI, akka.event.NoLogging)
      // Now let's download and check stuff.
      assertFalse("Failed to find the repository index!", remote.hasNewIndex(hash))
      assertTrue("Failed to detect new repository index!", remote.hasNewIndex("RANDOM STUFF"))

      // Now le'ts test downloads...
      val indexDir = new File(tmpDir, "index")
      remote.resolveIndexTo(indexDir)
      val expectedDb = new File(indexDir, "dummy.db")
      assertTrue("Failed to download index file!", expectedDb.exists)

      // Now, let's resolve the template...
      val templateDir = new File(tmpDir, "template1")
      remote.resolveTemplateTo(template1Id, templateDir)
      val expectedBuildFile = new File(templateDir, "build.sbt")
      assertTrue("Failed to download remote template!", expectedBuildFile.exists)
    }
  }

  @Test
  def shouldNotResolveTooSmallAnIncrementVersion(): Unit = {
    IO.withTestTempDirectory { tmpDir =>
      // TODO - Make a zip
      val repo = new File(tmpDir, "remote-repo")
      IO.createDirectory(repo)
      makeRepo(repo, binaryIncrementVersion = Constants.INDEX_BINARY_INCREMENT_VERSION - 1)

      val remote = new UriRemoteTemplateRepository(repo.toURI, akka.event.NoLogging)
      // Now let's download and check stuff.
      assertFalse(
        "Cannot pull an index with older binary increment version!",
        remote.hasNewIndex("RANDOM-INDEX"))
    }
  }

  @Test
  def shouldResolveLargerIncrementVersion(): Unit = {
    IO.withTestTempDirectory { tmpDir =>
      // TODO - Make a zip
      val repo = new File(tmpDir, "remote-repo")
      IO.createDirectory(repo)
      makeRepo(repo, binaryIncrementVersion = Constants.INDEX_BINARY_INCREMENT_VERSION + 1)

      val remote = new UriRemoteTemplateRepository(repo.toURI, akka.event.NoLogging)
      // Now let's download and check stuff.
      assertTrue(
        "Failed to pull new index with higher increment version!",
        remote.hasNewIndex("RANDOM-INDEX"))
    }
  }

  @Test
  def shouldNotResolveTooSmallMajorVersion(): Unit = {
    IO.withTestTempDirectory { tmpDir =>
      // TODO - Make a zip
      val repo = new File(tmpDir, "remote-repo")
      IO.createDirectory(repo)
      makeRepo(repo, binaryMajorVersion = Constants.INDEX_BINARY_MAJOR_VERSION - 1)

      val remote = new UriRemoteTemplateRepository(repo.toURI, akka.event.NoLogging)
      // Now let's download and check stuff.
      assertFalse(
        "Cannot pull an index with older binary major version!",
        remote.hasNewIndex("RANDOM-INDEX"))
    }
  }

  @Test
  def shouldNotResolveTooLargeMajorVersion(): Unit = {
    IO.withTestTempDirectory { tmpDir =>
      // TODO - Make a zip
      val repo = new File(tmpDir, "remote-repo")
      IO.createDirectory(repo)
      makeRepo(repo, binaryMajorVersion = Constants.INDEX_BINARY_MAJOR_VERSION + 1)

      val remote = new UriRemoteTemplateRepository(repo.toURI, akka.event.NoLogging)
      // Now let's download and check stuff.
      assertFalse(
        "Cannot pull an index with newer binary major version!",
        remote.hasNewIndex("RANDOM-INDEX"))
    }
  }
}
