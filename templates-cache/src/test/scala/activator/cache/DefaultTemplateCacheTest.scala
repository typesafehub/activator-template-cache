/**
 * Copyright (C) 2013 Typesafe <http://typesafe.com/>
 */
package activator
package cache

import org.junit.Assert._
import org.junit._
import java.io.File
import akka.actor._
import concurrent.Await
import concurrent.duration._
import sbt.IO

class DefaultTemplateCacheTest {

  var cacheDir: File = null
  var system: ActorSystem = null
  var cache: TemplateCache = null
  implicit val timeout = akka.util.Timeout(1000L)

  @Before
  def setup() {
    cacheDir = IO.createTemporaryDirectory
    // TODO - Create an cache...
    makeTestCache(cacheDir, "default")
    system = ActorSystem()
    // TODO - stub out remote repo
    cache = DefaultTemplateCache(actorFactory = system, location = cacheDir)
  }

  @Test
  def resolveTemplate(): Unit = {
    val template =
      Await.result(cache.template("ID-1"), Duration(1, MINUTES))
    assertTrue(template.isDefined)
    assertEquals(template.get.metadata, template1)
    val hasBuildSbt = template.get.files exists {
      case (file, name) => name == "build.sbt"
    }
    assertTrue("Failed to find template files!", hasBuildSbt)
  }

  @Test
  def resolveTutorial(): Unit = {
    val tutorial =
      Await.result(cache.tutorial("ID-1"), Duration(1, MINUTES))
    assertTrue(tutorial.isDefined)
    val hasIndexHtml = tutorial.get.files exists {
      case (name, file) => name == "index.html"
    }
    assertTrue("Failed to find tutorial files!", hasIndexHtml)
  }
  @Test
  def getAllMetadata(): Unit = {
    val metadata =
      Await.result(cache.metadata, Duration(1, MINUTES))
    val hasMetadata = metadata exists { _ == template1 }
    assertTrue("Failed to find metadata!", hasMetadata)
    val hasRemote = metadata exists { _ == nonLocalTemplate }
    assertTrue("Failed to find non-local template!", hasRemote)
  }

  @Test
  def getFeaturedMetadata(): Unit = {
    val metadata =
      Await.result(cache.featured, Duration(1, MINUTES))
    val hasMetadata = metadata exists { _ == template1 }
    assertTrue("Failed to find metadata!", hasMetadata)
    assertFalse("Featured metadata has unfeatured template.", metadata.exists(_ == nonLocalTemplate))
  }

  @Test
  def search(): Unit = {
    val metadata =
      Await.result(cache.search("test"), Duration(1, MINUTES))
    val hasMetadata = metadata exists { _ == template1 }
    assertTrue("Failed to find metadata in seaarch!", hasMetadata)
  }

  @Test
  def badSearch(): Unit = {
    val metadata =
      Await.result(cache.search("Ralph"), Duration(1, MINUTES))
    val hasMetadata = metadata exists { _ == template1 }
    assertFalse("Failed to find metadata in seaarch!", hasMetadata)
  }

  @After
  def tearDown() {
    system.shutdown()
    system.awaitTermination()
    IO delete cacheDir
    cacheDir = null
  }

  val template1 = TemplateMetadata(
    IndexStoredTemplateMetadata(
      id = "ID-1",
      timeStamp = 1L,
      featured = true,
      usageCount = None,
      name = "test-template",
      title = "A Testing Template",
      description = "A template that tests template existance.",
      authorName = "Bob",
      authorLink = "http://example.com/bob",
      tags = Seq("test", "template"),
      templateTemplate = true,
      sourceLink = "http://example.com/source",
      authorLogo = Some("http://example.com/logo.png"),
      authorBio = Some("Blah blah blah blah"),
      authorTwitter = Some("blah"),
      category = TemplateMetadata.Category.COMPANY,
      creationTime = TemplateMetadata.LEGACY_CREATION_TIME),
    locallyCached = true)

  val nonLocalTemplate = TemplateMetadata(
    IndexStoredTemplateMetadata(
      id = "ID-2",
      timeStamp = 1L,
      featured = false,
      usageCount = None,
      name = "test-remote-template",
      title = "A Testing Template that is not dowloaded",
      description = "A template that tests template existentialism.",
      authorName = "Jim",
      authorLink = "http://example.com/jim",
      tags = Seq("test", "template"),
      templateTemplate = false,
      sourceLink = "http://example.com/source",
      authorLogo = Some("http://example.com/logo.png"),
      authorBio = Some("Blah blah blah blah"),
      authorTwitter = Some("blah"),
      category = TemplateMetadata.Category.COMPANY,
      creationTime = TemplateMetadata.LEGACY_CREATION_TIME),
    locallyCached = false)

  def makeTestCache(dir: File, repoName: String): Unit = {
    val cacheProps = new CacheProperties(new File(dir, Constants.CACHE_PROPS_FILENAME))
    cacheProps.cacheIndexHash = "fakehash-default-template-cache-test"
    cacheProps.save()
    val writer = LuceneIndexProvider.write(new File(dir, s"${Constants.METADATA_INDEX_FILENAME}.$repoName"))
    try {
      writer.insert(template1.persistentConfig)
      writer.insert(nonLocalTemplate.persistentConfig)
    } finally {
      writer.close()
    }
    // Now we create our files:
    val templateDir = new File(dir, "ID-1")
    IO createDirectory templateDir
    IO.write(new File(templateDir, "build.sbt"), """name := "Test" """)
    val tutorialDir = new File(templateDir, Constants.TUTORIAL_DIR)
    IO createDirectory tutorialDir
    IO.write(new File(tutorialDir, "index.html"), "<html></html>")
  }
}
