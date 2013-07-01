package activator
package cache

import org.junit.Assert._
import org.junit._

trait IndexDbTest {
  def provider: IndexDbProvider
  def index = _db

  @Test
  def testFindById(): Unit = {
    for (metadata <- testData) {
      val found = index.template(metadata.id)
      assertEquals("Unable to find metadata in index.", Some(metadata), found)
    }
  }

  @Test
  def testFindByName(): Unit = {
    for (metadata <- testData) {
      val found = index.templateByName(metadata.name)
      assertEquals("Unable to find metadata by name in index.", Some(metadata), found)
    }
  }

  @Test
  def testMaxDocs(): Unit = {
    // Note: This may be to stringent, as we can't really guarantee exact in practice, but
    // we can at least check if write-once guarantees this.
    assertEquals("Unable to find metadata in index.", index.maxTemplates, testData.length)
  }
  @Test
  def testFindInList(): Unit = {
    val metadata = testData.head
    val lists = index.metadata
    val contained = lists.exists(_ == metadata)
    assertTrue("Unable to find metadata in index.", contained)
  }

  @Test
  def testFindFeatured(): Unit = {
    val featuredExpected = testData.filter(_.featured)
    val featured = index.featured
    assertEquals("Did not find all featured templates.", featuredExpected.toVector, featured.toVector)
  }

  @Test
  def testFindByQuery(): Unit = {
    val metadata = testData.head
    val found = index.search("human")
    val contained = found.exists(_ == metadata)
    assertTrue(s"Unable to find metadata in index.  Result = ${found mkString "\n"}", contained)
  }

  // Hackery to ensure the database is opened closed.
  private var _db: IndexDb = null
  val testData: Seq[IndexStoredTemplateMetadata] =
    Seq(IndexStoredTemplateMetadata(
      id = "ID",
      name = "url-friendly-name",
      title = "A human readable title.",
      description = "A very long description; DELETE TABLE TEMPLATES; with SQL injection.",
      authorName = "Jim Bob",
      authorLink = "http://example.com/jimbob/",
      Seq("Tag 1", "Tag 2", "tag3"),
      timeStamp = 1L,
      featured = true,
      usageCount = None,
      templateTemplate = true),
      IndexStoredTemplateMetadata(
        id = "ID-2",
        name = "url-friendly-name-2",
        title = "A human readable title.  AGAIN!",
        description = "A very long description\n WITH mutliple lines and stuff.  This is not featured.\n",
        authorName = "Elizabeth",
        authorLink = "http://example.com/elizabeth",
        tags = Seq("Tag 1", "Tag 2", "tag3"),
        timeStamp = 1L,
        featured = false,
        usageCount = None,
        templateTemplate = false))

  @Before
  def preStart(): Unit = {
    val tmp = java.io.File.createTempFile("indexdb", "test")
    tmp.delete()

    val writer = provider write tmp
    try testData foreach writer.insert
    finally writer.close()

    _db = provider.open(tmp)
  }
  @After
  def postStop(): Unit = {
    if (_db != null)
      _db.close()
    _db = null
  }
}

class LuceneIndexDbTest extends IndexDbTest {
  def provider = LuceneIndexProvider
}
