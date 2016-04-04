/**
 * Copyright (C) 2013 Typesafe <http://typesafe.com/>
 */
package activator
package cache

import java.io.File
import java.util.concurrent.TimeUnit

import activator.cache.StubRemoteRepository._
import akka.actor._
import org.junit.Assert._
import org.junit._
import sbt.IO

import scala.concurrent.Await
import scala.concurrent.duration._

class RemoteTemplateStubTest {
  var cacheDir: File = null
  var system: ActorSystem = null
  var cache: TemplateCache = null
  implicit val timeout = akka.util.Timeout(60 * 1000L, TimeUnit.MILLISECONDS)
  @Before
  def setup() {
    cacheDir = IO.createTemporaryDirectory
    // TODO - Create an cache...
    makeTestCache(cacheDir)
    system = ActorSystem()
    // TODO - stub out remote repo
    cache = DefaultTemplateCache(
      actorFactory = system,
      location = cacheDir,
      remote = StubRemoteRepository)
  }

  @Test
  def resolveTemplate(): Unit = {
    val template =
      Await.result(cache.template("ID-1"), Duration(3, MINUTES))
    assertTrue(template.isDefined)
    assertEquals(template.get.metadata, template1)
    val hasBuildSbt = template.get.files exists {
      case (file, name) => name == "build.sbt"
    }
    assertTrue("Failed to find template files!", hasBuildSbt)
  }

  @Test
  def resolveRemoteTemplate(): Unit = {
    val template =
      Await.result(cache.template(nonLocalTemplate.id), Duration(3, MINUTES))
    assertTrue(template.isDefined)
    assertEquals(template.get.metadata, resolvedNonLocalTemplate)
    val hasBuildSbt = template.get.files exists {
      case (file, name) => name == "build2.sbt"
    }
    assertTrue("Failed to find template files!", hasBuildSbt)
  }

  @Test
  def resolveNewRemoteTemplate(): Unit = {
    val template =
      Await.result(cache.template(newNonLocalTemplate.id), Duration(3, MINUTES))
    assertTrue(template.isDefined)
    assertEquals(template.get.metadata, resolvedNewNonLocalTemplate)
    val hasBuildSbt = template.get.files exists {
      case (file, name) => name == "build2.sbt"
    }
    assertTrue("Failed to find template files!", hasBuildSbt)
  }

  @Test
  def resolveTutorial(): Unit = {
    val tutorial =
      Await.result(cache.tutorial(template1.id), Duration(3, MINUTES))
    assertTrue(tutorial.isDefined)
    val hasIndexHtml = tutorial.get.files exists {
      case (name, file) => name == "index.html"
    }
    assertTrue("Failed to find tutorial files!", hasIndexHtml)
  }
  @Test
  def getAllMetadata(): Unit = {
    val metadata =
      Await.result(cache.metadata, Duration(3, MINUTES))
    val hasMetadata = metadata exists { _ == template1 }
    assertTrue("Failed to find metadata!", hasMetadata)
    val hasRemote = metadata exists { _ == nonLocalTemplate }
    assertTrue("Failed to find non-local template!", hasRemote)

    val hasNewRemote = metadata exists { _ == newNonLocalTemplate }
    assertTrue("Failed to find new non-local template!", hasNewRemote)
  }

  @Test
  def getFeaturedMetadata(): Unit = {
    val metadata =
      Await.result(cache.featured, Duration(3, MINUTES))
    val hasMetadata = metadata exists { _ == template1 }
    assertTrue("Failed to find metadata!", hasMetadata)
    assertFalse("Featured metadata has unfeatured template.", metadata.exists(_ == nonLocalTemplate))
    val hasNewRemote = metadata exists { _ == newNonLocalTemplate }
    assertTrue("Failed to find new non-local template!", hasNewRemote)
  }

  @Test
  def search(): Unit = {
    val metadata =
      Await.result(cache.search("test"), Duration(3, MINUTES))
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
    // Here we always check to ensure the properties are right....
    val cacheProps = new CacheProperties(new File(cacheDir, Constants.CACHE_PROPS_FILENAME))
    assertEquals("Failed to download new metadata index!", Some(SECOND_INDEX_ID), cacheProps.cacheIndexHash)
    system.shutdown()
    IO delete cacheDir
    cacheDir = null
  }

  val resolvedNonLocalTemplate =
    nonLocalTemplate.copy(locallyCached = true)

  val resolvedNewNonLocalTemplate =
    newNonLocalTemplate.copy(locallyCached = true)

  def makeIndex(dir: File)(templates: TemplateMetadata*): Unit = {
    if (dir.exists) IO.delete(dir)
    val writer = LuceneIndexProvider.write(dir)
    try templates foreach { t => writer insert t.persistentConfig }
    finally writer.close()
  }

  def makeTestCache(dir: File): Unit = {
    makeIndex(new File(dir, Constants.METADATA_INDEX_FILENAME))(
      template1,
      nonLocalTemplate)
    // Now we create our files:
    val templateDir = new File(dir, "ID-1")
    IO createDirectory templateDir
    IO.write(new File(templateDir, "build.sbt"), """name := "Test" """)
    val tutorialDir = new File(templateDir, Constants.TUTORIAL_DIR)
    IO createDirectory tutorialDir
    IO.write(new File(tutorialDir, "index.html"), "<html></html>")
    val cacheProps = new CacheProperties(new File(dir, Constants.CACHE_PROPS_FILENAME))
    cacheProps.cacheIndexHash = FIRST_INDEX_ID
    cacheProps.save()
  }
}
