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

class OfflineDefaultTemplateCacheTest {

  var cacheDir: File = null
  var system: ActorSystem = null
  var cache: TemplateCache = null
  implicit val timeout = akka.util.Timeout(1000L)

  @Before
  def setup() {
    cacheDir = IO.createTemporaryDirectory
    // TODO - Create an cache...
    makeTestCache(cacheDir)
    system = ActorSystem()
    // TODO - stub out remote repo
    cache = DefaultTemplateCache(actorFactory = system, location = cacheDir)
  }

  @Test
  def resolveRemoteTemplateFails(): Unit = {
    val template =
      Await.result(cache.template(nonLocalTemplate.id), Duration(1, MINUTES))
    assertFalse("Should not be able to resolve offline tempalte.", template.isDefined)
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
      templateTemplate = true,
      sourceLink = "http://example.com/source",
      authorLogo = Some("http://example.com/logo.png"),
      authorBio = Some("Blah blah blah blah"),
      authorTwitter = Some("blah"),
      category = TemplateMetadata.Category.COMPANY,
      creationTime = TemplateMetadata.LEGACY_CREATION_TIME),
    locallyCached = false)
  def makeTestCache(dir: File): Unit = {
    val cacheProps = new CacheProperties(new File(dir, Constants.CACHE_PROPS_FILENAME))
    cacheProps.cacheIndexHash = "fakehash-offline-default-template-cache-test"
    cacheProps.save()
    val writer = LuceneIndexProvider.write(new File(dir, Constants.METADATA_INDEX_FILENAME))
    try {
      writer.insert(template1.persistentConfig)
      writer.insert(nonLocalTemplate.persistentConfig)
    } finally {
      writer.close()
    }
    // We have one local template
    val templateDir = new File(dir, "ID-1")
    IO createDirectory templateDir
    IO.write(new File(templateDir, "build.sbt"), """name := "Test" """)
    val tutorialDir = new File(templateDir, Constants.TUTORIAL_DIR)
    IO createDirectory tutorialDir
    IO.write(new File(tutorialDir, "index.html"), "<html></html>")
  }
}
