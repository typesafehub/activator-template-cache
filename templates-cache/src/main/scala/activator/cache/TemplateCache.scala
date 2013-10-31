/**
 * Copyright (C) 2013 Typesafe <http://typesafe.com/>
 */
package activator.cache

import scala.concurrent.Future

// TODO - This whole thing should use an abstraction file files, like "Source" or some such.

/**
 * A mapping of the files included in this tutorial.
 *
 * The map is relative-file-name to actual File.
 */
case class Tutorial(id: String, files: Map[String, java.io.File])
/**
 * All information about a template.
 * @files is a sequence of actual file -> relative location name.
 */
case class Template(metadata: TemplateMetadata,
  files: Seq[(java.io.File, String)]) // TODO - What do we need for help?

/**
 * This interface represents the template cache within SNAP.  it's your mechanisms to find things and
 *  create stuff.
 */
trait TemplateCache extends java.io.Closeable {
  /** Find a template within the cache. */
  def template(id: String): Future[Option[Template]]
  /** Find the tutorial for a given template. */
  // TODO - Different method, or against Template?
  def tutorial(id: String): Future[Option[Tutorial]]
  /** Search for a template within the cache. */
  def search(query: String): Future[Iterable[TemplateMetadata]]
  /** Find a template within the cache by exact name. */
  def searchByName(name: String): Future[Option[TemplateMetadata]]
  /** Returns all metadata we have for templates. */
  def metadata: Future[Iterable[TemplateMetadata]]
  /** Returns all the metadata meant to be featured in the initial page. */
  def featured: Future[Iterable[TemplateMetadata]]

  override def close(): Unit = {}
}
