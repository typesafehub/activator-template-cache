/**
 * Copyright (C) 2013 Typesafe <http://typesafe.com/>
 */
package activator
package cache

import java.io.File

/**
 * Represents a *non-thread-safe* access to our index database that
 * we will wrap inside an actor.
 * Also, these method may throw at any time, which requires the whole session
 * to be regenerated.  NO LOVIN.
 *
 * This should be embedded in an actor that can handle any failures that spew from
 * the maws of JDBC or sqlite.
 *
 * If this were async it should implement the IndexRestOps trait, but since it isn't,
 * just manually keep it in sync with that trait...
 */
trait IndexDb extends java.io.Closeable {
  /**
   * Finds a template by id.  Returns None if nothing is found.
   *  BLOWS CHUNKS on error.
   */
  def template(id: String): Option[IndexStoredTemplateMetadata]
  /**
   * Finds a template by name.  Returns None if nothing is found.
   *  BLOWS CHUNKS on error.
   */
  def templateByName(name: String): Option[IndexStoredTemplateMetadata]
  /**
   * Searchs for a template matching the query string, and returns all results.
   *  There may be a ton of results.
   *  SPEWS ON ERROR.
   */
  def search(query: String, max: Int = 0): Iterable[IndexStoredTemplateMetadata]
  /** Returns *ALL* metadata in this repository. */
  def metadata: Iterable[IndexStoredTemplateMetadata]
  /** Returns all metadata with the flag of "featured" set to "true" */
  def featured: Iterable[IndexStoredTemplateMetadata]
  /**
   * Return the maximum number of templates in this index.
   *  Note: Most likely this is the exact number of templates in the index,
   *  but there is no guarantee.
   */
  def maxTemplates: Long
  // Note: Have to call this to safely clean the resource!
  /** Cleans up resources and handles. MUST BE CALLED BEFORE RE-OPENING THE DB. */
  def close(): Unit
}
/**
 * represents a *non-thread-safe* way to write an index file.
 */
trait IndexWriter extends java.io.Closeable {
  /** Writes the template to our index db.  Blocks until done. SPEWS on error. */
  def insert(template: IndexStoredTemplateMetadata): Unit
  def close(): Unit
}

/**
 * Our wrapper around different index backends, so we can
 *  swap them if we encounter issues.
 */
trait IndexDbProvider {
  /**
   * Opens a new session on the index database.  This may throw.
   * not thread-safe either.
   *
   * Just hide it in an actor and you're magically safe.
   */
  def open(localDirOrFile: File): IndexDb
  /**
   * Opens a new session on the index database for writing to the index. This may throw.
   * Not thread-safe either.
   */
  def write(localDirorFile: File): IndexWriter
}
object IndexDbProvider {
  implicit def default: IndexDbProvider = LuceneIndexProvider
}
