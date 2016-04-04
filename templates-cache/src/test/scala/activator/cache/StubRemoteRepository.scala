package activator.cache

import java.io.File
import java.net.URI
import java.util.UUID

import sbt.IO

object StubRemoteRepository extends RemoteTemplateRepository {
  val FIRST_INDEX_ID = "FIRST INDEX"
  val SECOND_INDEX_ID = "SECOND INDEX"

  val template1 = TemplateMetadata(
    IndexStoredTemplateMetadata(
      id = "ID-1",
      timeStamp = 1L,
      featured = true,
      usageCount = None,
      name = "test-template",
      title = "A Testing Template",
      description = "A template that tests template existance.",
      authorName = "Jim Bob",
      authorLink = "http://example.com/jimbob/",
      tags = Seq("test", "template"),
      templateTemplate = false,
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
      authorName = "Jim Bob",
      authorLink = "http://example.com/jimbob/",
      tags = Seq("test", "template"),
      templateTemplate = true,
      sourceLink = "http://example.com/source",
      authorLogo = Some("http://example.com/logo.png"),
      authorBio = Some("Blah blah blah blah"),
      authorTwitter = Some("blah"),
      category = TemplateMetadata.Category.COMPANY,
      creationTime = TemplateMetadata.LEGACY_CREATION_TIME),
    locallyCached = false)

  val newNonLocalTemplate = TemplateMetadata(
    IndexStoredTemplateMetadata(
      id = "ID-3",
      timeStamp = 1L,
      featured = true,
      usageCount = None,
      name = "test-updated-template",
      title = "A NEW FEATURED TEMPLATE",
      description = "A template that tests template deism.  MONADS.",
      authorName = "Jim Bob",
      authorLink = "http://example.com/jimbob/",
      tags = Seq("test", "template"),
      templateTemplate = false,
      sourceLink = "http://example.com/source",
      authorLogo = Some("http://example.com/logo.png"),
      authorBio = Some("Blah blah blah blah"),
      authorTwitter = Some("blah"),
      category = TemplateMetadata.Category.COMPANY,
      creationTime = TemplateMetadata.LEGACY_CREATION_TIME),
    locallyCached = false)

  def resolveIndexProperties(localPropsFile: File): File = {
    // TODO - implement?
    localPropsFile
  }
  def hasNewIndexProperties(currentHash: String): Boolean =
    SECOND_INDEX_ID != currentHash

  def resolveLatestIndexHash(): String =
    SECOND_INDEX_ID

  def ifNewIndexProperties(currentHash: String)(onNewProperties: CacheProperties => Unit): Unit =
    if (hasNewIndexProperties(currentHash)) {
      IO.withTemporaryDirectory { tmpDir =>
        val propsFile = new File(tmpDir, "index.properties")
        val props = new CacheProperties(propsFile)
        props.cacheIndexHash = SECOND_INDEX_ID
        props.save("saved SECOND_INDEX_ID")
        onNewProperties(props)
      }
    }

  // TODO - Actually alter the index and check to see if we have the new one.
  // Preferable with a new template, not in the existing index.
  def resolveIndexTo(indexDirOrFile: File, currentHash: String): Unit = {
    makeIndex(indexDirOrFile)(
      template1,
      nonLocalTemplate,
      newNonLocalTemplate)
  }

  def resolveTemplateTo(templateId: String, localDir: File): File = {
    if (nonLocalTemplate.id == templateId || newNonLocalTemplate.id == templateId) {
      // Fake Resolving a remote template
      if (!localDir.exists) IO.createDirectory(localDir)
      IO.write(new File(localDir, "build2.sbt"), """name := "Test2" """)
      val tutorialDir = new File(localDir, Constants.TUTORIAL_DIR)
      IO createDirectory tutorialDir
      IO.write(new File(tutorialDir, "index.html"), "<html></html>")
    }
    localDir
  }

  def templateBundleURI(activatorVersion: String,
    uuid: UUID,
    templateName: String): URI = ???

  def templateBundleExists(activatorVersion: String,
    uuid: UUID,
    templateName: String): Boolean = ???

  def templateZipURI(uuid: UUID): URI = ???

  def authorLogoURI(uuid: UUID): URI = ???

  def resolveMinimalActivatorDist(toFile: File, activatorVersion: String): File = ???

  def makeIndex(dir: File)(templates: TemplateMetadata*): Unit = {
    if (dir.exists) IO.delete(dir)
    val writer = LuceneIndexProvider.write(dir)
    try templates foreach { t => writer insert t.persistentConfig }
    finally writer.close()
  }
}
