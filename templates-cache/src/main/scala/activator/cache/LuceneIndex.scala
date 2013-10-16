/**
 * Copyright (C) 2013 Typesafe <http://typesafe.com/>
 */
package activator
package cache

import java.io.File
import org.apache.lucene
import lucene.store.Directory
import lucene.index._
import lucene.util.Version.LUCENE_43
import lucene.analysis.standard.StandardAnalyzer
import lucene.document._
import lucene.search.IndexSearcher
import lucene.search.TermQuery
import lucene.queryparser.classic.MultiFieldQueryParser

class LuceneIndexDbException(msg: String, cause: Throwable)
  extends Exception(msg, cause)

object LuceneIndexProvider extends IndexDbProvider {
  def open(localDirOrFile: File): IndexDb = withLuceneClassLoader {
    val directory = lucene.store.FSDirectory.open(localDirOrFile)
    new LuceneIndex(localDirOrFile, directory)
  }
  def write(localDirOrFile: File): IndexWriter = withLuceneClassLoader {
    val directory = lucene.store.FSDirectory.open(localDirOrFile)
    new LuceneIndexWriter(directory)
  }

  def withLuceneClassLoader[T](f: => T): T = {
    val cl = Thread.currentThread.getContextClassLoader
    val luceneCl = classOf[lucene.store.FSDirectory].getClassLoader
    Thread.currentThread.setContextClassLoader(luceneCl)
    try f
    finally Thread.currentThread.setContextClassLoader(cl)
  }
}

// We always assume this is creating a new index....
class LuceneIndexWriter(directory: Directory) extends IndexWriter {
  import LuceneIndex._
  private val writeConfig = {
    val tmp = new IndexWriterConfig(LuceneIndex.LUCENE_VERSION, analyzer)
    tmp.setOpenMode(lucene.index.IndexWriterConfig.OpenMode.CREATE_OR_APPEND)
    tmp
  }
  private val writer = new lucene.index.IndexWriter(directory, writeConfig)

  def insert(template: IndexStoredTemplateMetadata): Unit = {
    writer addDocument metadataToDocument(template)
  }
  def close(): Unit = {
    writer.close()
  }
}

object LuceneIndex {
  val FIELD_ID = "id"
  val FIELD_NAME = "name"
  val FIELD_TITLE = "title"
  val FIELD_DESC = "description"
  val FIELD_TS = "timestamp"
  val FIELD_TAGS = "tags"
  val FIELD_FEATURED = "featured"
  val FIELD_USAGE_COUNT = "usageCount"
  val FIELD_AUTHOR_NAME = "authorName"
  val FIELD_AUTHOR_LINK = "authorLink"
  val FIELD_TEMPLATE_TEMPLATE = "templateTemplate"
  val FIELD_SOURCE_LINK = "sourceLink"

  val FIELD_TRUE_VALUE = "true"
  val FIELD_FALSE_VALUE = "false"
  val FIELD_NONE_VALUE = "None"

  val LUCENE_VERSION = LUCENE_43

  val analyzer = new StandardAnalyzer(LUCENE_VERSION)

  def documentToMetadata(doc: Document): IndexStoredTemplateMetadata = {
    val id = doc get FIELD_ID
    val name = doc get FIELD_NAME
    val title = doc get FIELD_TITLE
    // If we get a failure pulling this as a long, then we have a bad index...
    // We need to figure out how to throw an error and what to do about it...
    // For now, let's just throw any old error and deal on the Actor-side of the fence.
    val ts = (doc get FIELD_TS).toLong
    val desc = doc get FIELD_DESC
    val authorName = doc get FIELD_AUTHOR_NAME
    val authorLink = doc get FIELD_AUTHOR_LINK
    val tags = (doc get FIELD_TAGS) split ","
    val usageCount = (doc get FIELD_USAGE_COUNT) match {
      case FIELD_NONE_VALUE => None
      case LongString(num) => Some(num)
      case _ => None // TODO - just issue a real error here!
    }
    val featured = (doc get FIELD_FEATURED) == FIELD_TRUE_VALUE
    val templateTemplate = (doc get FIELD_TEMPLATE_TEMPLATE) == FIELD_TRUE_VALUE
    // sourceLink may not be present in old indexes
    val sourceLink = Option(doc get FIELD_SOURCE_LINK)

    //val
    IndexStoredTemplateMetadata(
      id = id,
      name = name,
      title = title,
      description = desc,
      authorName = authorName,
      authorLink = authorLink,
      tags = tags,
      timeStamp = ts,
      featured = featured,
      usageCount = usageCount,
      templateTemplate = templateTemplate,
      // this getOrElse is just to deal with old records without exploding;
      // newly-published templates will always have the source link
      sourceLink = sourceLink.getOrElse("http://typesafe.com/"))
  }

  def metadataToDocument(metadata: IndexStoredTemplateMetadata): Document = {
    val doc = new Document()
    doc.add(new StringField(FIELD_ID, metadata.id, Field.Store.YES))
    doc.add(new TextField(FIELD_NAME, metadata.name, Field.Store.YES))
    doc.add(new TextField(FIELD_TITLE, metadata.title, Field.Store.YES))
    doc.add(new LongField(FIELD_TS, metadata.timeStamp, Field.Store.YES))
    doc.add(new TextField(FIELD_DESC, metadata.description, Field.Store.YES))
    doc.add(new TextField(FIELD_AUTHOR_NAME, metadata.authorName, Field.Store.YES))
    doc.add(new TextField(FIELD_AUTHOR_LINK, metadata.authorLink, Field.Store.YES))
    doc.add(new TextField(FIELD_TAGS, metadata.tags.mkString(","), Field.Store.YES))
    doc.add(new StringField(FIELD_FEATURED, booleanToString(metadata.featured), Field.Store.YES))
    doc.add(new StringField(FIELD_USAGE_COUNT, usageToString(metadata.usageCount), Field.Store.YES))
    doc.add(new StringField(FIELD_TEMPLATE_TEMPLATE, booleanToString(metadata.templateTemplate), Field.Store.YES))
    doc.add(new StringField(FIELD_SOURCE_LINK, metadata.sourceLink, Field.Store.YES))
    doc
  }

  private def booleanToString(featured: Boolean): String =
    if (featured) FIELD_TRUE_VALUE else FIELD_FALSE_VALUE
  private def usageToString(usage: Option[Long]): String =
    usage match {
      case (Some(num)) => num.toString
      case None => FIELD_NONE_VALUE
    }
}
class LuceneIndex(dirName: File, dir: Directory) extends IndexDb {
  import LuceneIndex._
  val reader = DirectoryReader.open(dir)
  val searcher = new IndexSearcher(reader)
  // TODO - Figure out which fields we care about...
  val parser = new MultiFieldQueryParser(LUCENE_VERSION,
    Array(FIELD_TITLE, FIELD_DESC, FIELD_TAGS, FIELD_NAME),
    analyzer);

  def template(id: String): Option[IndexStoredTemplateMetadata] =
    executeQuery(new TermQuery(new Term(FIELD_ID, id)), 1).headOption

  def templateByName(name: String): Option[IndexStoredTemplateMetadata] = {
    // NOTE: We need to bump binary compatibility at some point
    // and store RAW names (un-tokenzied) so we can do direct indexing on name.
    //  For now, we just do an in-memory additional filter.  We assume
    // search is powerful enough to find what we want.
    search(name).find(_.name == name)

  }

  def featured: Iterable[IndexStoredTemplateMetadata] =
    executeQuery(new TermQuery(new Term(FIELD_FEATURED, FIELD_TRUE_VALUE)), reader.maxDoc)

  def search(query: String, max: Int): Iterable[IndexStoredTemplateMetadata] =
    executeQuery(parser parse query, max)

  def metadata: Iterable[IndexStoredTemplateMetadata] =
    executeQuery(new lucene.search.MatchAllDocsQuery, reader.maxDoc)

  def maxTemplates: Long = reader.maxDoc

  // Helper which actually runs queries on the local index.
  private def executeQuery(query: lucene.search.Query, max: Int): Iterable[IndexStoredTemplateMetadata] = {
    val results = searcher.search(query, if (max == 0) reader.maxDoc else max)
    results.scoreDocs map { doc =>
      val document = reader.document(doc.doc)
      documentToMetadata(document)
    }
  }
  def close(): Unit = {
    dir.close()
  }
  override def toString(): String =
    "LuceneIndex(" + dirName + ")"
}

object LongString {
  def unapply(x: String) =
    util.Try(x.toLong).toOption
}
