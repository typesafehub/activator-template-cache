/**
 * Copyright (C) 2013 Typesafe <http://typesafe.com/>
 */
package activator
package cache

import java.io.File
import com.typesafe.config.Config
import akka.event.LoggingAdapter
import java.util.UUID
import java.net.URI

trait RemoteTemplateRepository {
  /**
   * Downloads a remote template into the given local directory.
   *
   * Throws on any error or if template ids do not exist.
   */
  def resolveTemplateTo(templateId: String, localDir: File): File

  /**
   * Downloads the current index properties into the given file
   */
  def resolveIndexProperties(localPropsFile: File): File

  /**
   * Checks to see if there's a new index in the remote repository
   * @param currentHash - The old identifier for the index file.
   */
  def hasNewIndexProperties(currentHash: String): Boolean

  /**
   * Downloads the latest index hash (throws an exception if
   *  it can't get one or it isn't compatible).
   */
  def resolveLatestIndexHash(): String

  /**
   * Checks to see if there's a new index in the remote repository,
   * calling the callback with the new index properties if there is one.
   * The callback runs synchronously while the index file still exists,
   * and the file is deleted afterwards.
   * @param currentHash - The old identifier for the index file.
   */
  def ifNewIndexProperties(currentHash: String)(onNewProperties: CacheProperties => Unit): Unit

  /**
   * Resolves a remote index file to the local index directory.
   * @param indexDirOrFile - The directory or file location where the new index
   *   should be written.
   * @param indexHash - Which index to get.
   *
   */
  def resolveIndexTo(indexDirOrFile: File, indexHash: String): Unit

  /**
   * Calculates the URI where we would find or publish a bundled
   * version of the template (bundled = includes activator launcher)
   */
  def templateBundleURI(activatorVersion: String,
    uuid: UUID,
    templateName: String): URI

  /**
   * Calculates the URI where we would find or publish the main
   * template zip ... normally you want to use this via the cache,
   * not directly, though.
   */
  def templateZipURI(uuid: UUID): URI

  /**
   * Calculates the URI where we would find the logo,
   * we would normally stick this in the cache as authorLogo
   */
  def authorLogoURI(uuid: UUID): URI

  /**
   * Checks whether the bundled version of the template exists.
   */
  def templateBundleExists(activatorVersion: String,
    uuid: UUID,
    templateName: String): Boolean

  def resolveMinimalActivatorDist(toFile: File, activatorVersion: String): File
}

object RemoteTemplateRepository {
  def apply(config: Config, log: LoggingAdapter): RemoteTemplateRepository = {
    // TODO - Make sure this is the right way to do it from HAVOC.
    // TODO - Error handling of some form?
    new templates.repository.UriRemoteTemplateRepository(
      new java.net.URI(config.getString("activator.template.remote.url")), log)
  }
}
