package activator
package templates
package repository

import cache.RemoteTemplateRepository
import java.io.File
import com.typesafe.config.Config
import akka.event.LoggingAdapter
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
   * Attempts to publish a new template index with the given serial number.
   */
  def publishIndex(indexZip: java.io.File, serial: Long): ProcessResult[Unit]
}
object PublishableTemplateRepository {
  def apply(config: Config, log: akka.event.LoggingAdapter): PublishableTemplateRepository = {
    val uri = new java.net.URI(config.getString("activator.template.remote.url"))
    val username = config.getString("activator.template.remote.user")
    val passwd = config.getString("activator.template.remote.password")
    new S3PublishableTemplateRepository(log, uri, username, passwd)
  }
}
