/**
 * Copyright (C) 2013 Typesafe <http://typesafe.com/>
 */
package activator
package cache

import java.io.File
import com.typesafe.config.Config
import akka.event.LoggingAdapter

trait RemoteTemplateRepository {
  /**
   * Downloads a remote template into the given local directory.
   *
   * Throws on any error or if template ids do not exist.
   */
  def resolveTemplateTo(templateId: String, localDir: File): File

  /**
   * Downloads the current inex properties into
   */
  def resolveIndexProperties(localPropsFile: File): File
  /**
   * Checks to see if there's a new index in the remote repository
   * @param currentHash - The old identifier for the index file.
   */
  def hasNewIndex(currentHash: String): Boolean

  /**
   * Resolves the new remote index file to the local index directory.
   * @param indexDirOrFile - The directory or file location where the new index
   *   should be written.
   *
   * @return The new hash of the index.
   */
  def resolveIndexTo(indexDirOrFile: File): String
}
object RemoteTemplateRepository {
  def apply(config: Config, log: LoggingAdapter): RemoteTemplateRepository = {
    // TODO - Make sure this is the right way to do it from HAVOC.
    // TODO - Error handling of some form?
    new templates.repository.UriRemoteTemplateRepository(
      new java.net.URI(config.getString("activator.template.remote.url")), log)
  }
}
