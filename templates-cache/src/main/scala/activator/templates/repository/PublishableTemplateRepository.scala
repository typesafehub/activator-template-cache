/**
 * Copyright (C) 2013 Typesafe <http://typesafe.com/>
 */
package activator
package templates
package repository

import cache.RemoteTemplateRepository
import com.typesafe.config.Config
import java.util.UUID

/**
 * This interface represents a template repository which we can
 *  both publish and read from.
 */
trait PublishableTemplateRepository extends RemoteTemplateRepository {
  /**
   * Resolves the current index serial #.
   */
  def currentRemoteIndexSerial: ProcessResult[Long]
  /** Informs us if we have to bootstrap. */
  def hasCurrentRemoteIndexSerial: ProcessResult[Boolean]
  /**
   * Attempts to publish a template (by UUID + zip) to amazon S3.
   *
   * @return
   *      Either nothing (Unit) or the failures.
   */
  def publishTemplate(
    uuid: UUID,
    zipFile: java.io.File): ProcessResult[Unit]

  /**
   * Attempts to publish a template (by template UUID + logo file) to amazon S3.
   *
   * @return
   *      Either nothing (Unit) or the failures.
   */
  def publishAuthorLogo(
    uuid: UUID,
    logoFile: java.io.File,
    contentType: String): ProcessResult[Unit]

  /**
   * Attempts to publish a template with Activator launcher bundled.
   *
   * @return
   *      Either nothing (Unit) or the failures.
   */
  def publishTemplateBundle(
    activatorVersion: String,
    uuid: UUID,
    templateName: String,
    zipFile: java.io.File): ProcessResult[Unit]

  /**
   * Attempts to publish a new template index with the given serial number.
   */
  def publishIndex(indexZip: java.io.File, serial: Long): ProcessResult[Unit]
}
object PublishableTemplateRepository {
  def apply(config: Config, log: akka.event.LoggingAdapter): PublishableTemplateRepository = {
    val name = config.getString("activator.template.remote.name")
    val uri = new java.net.URI(config.getString("activator.template.remote.url"))
    val username = config.getString("activator.template.remote.user")
    val passwd = config.getString("activator.template.remote.password")
    new S3PublishableTemplateRepository(name, log, uri, username, passwd)
  }
}
