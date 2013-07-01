package activator
package templates
package repository

import cache._
import java.net.URI
import java.net.URL
import java.io.File
import sbt.IO
import scala.util.control.NonFatal
import com.amazonaws.services.s3.AmazonS3Client
import com.amazonaws.auth.AnonymousAWSCredentials
import com.amazonaws.ClientConfiguration
import com.amazonaws.Protocol
import com.amazonaws.services.s3.model.GetObjectRequest
import akka.event.LoggingAdapter

/**
 *  This dude resolves files from a URI-based repo.  We use the sbt
 *  IO.download function to pull down URIs.  So, pretty much just simple HTTP
 *  downloads or local file copies are supported.
 */
class UriRemoteTemplateRepository(base: URI, log: LoggingAdapter) extends RemoteTemplateRepository {
  protected val layout = new Layout(base)

  // wrapper around IO.download that logs what's happening
  private def download(url: URL, dest: File): Unit = {
    // FIXME this should be somehow conditional or use a logger or something
    log.debug(s"Downloading url ${url} underneath base ${base}")
    try IO.download(url, dest)
    catch {
      case e: Exception =>
        log.error(s"Failed to download ${url}: ${e.getClass.getName}: ${e.getMessage}")
        throw e
    }
  }

  protected def makeClient(): AmazonS3Client = {
    new AmazonS3Client(
      new AnonymousAWSCredentials(),
      new ClientConfiguration().withProtocol(Protocol.HTTPS))
  }

  private def cleanLocation(path: String): String =
    if (path startsWith "/") path drop 1
    else path
  // Note: Thanks to CLOUD FRONT on AMazon S3, we'd like to
  // grab the more current index file using an anonymous S3 client.
  protected def downloadFromS3(url: URI, dest: File): Unit = {
    log.debug(s"Downloading S3 bucket ${url} underneath base ${base}")
    val client = makeClient()

    val (bucket, key) = getBucketAndKey(url)
    val request = new GetObjectRequest(bucket, key)
    client.getObject(request, dest)
  }

  protected def getBucketAndKey(url: URI): (String, String) =
    url.getHost -> cleanLocation(url.getRawPath)

  def resolveTemplateTo(templateId: String, localDir: File): File = {
    IO.withTemporaryDirectory { tmpDir =>
      val tmpFile = new File(tmpDir, "template.zip")
      download(layout.template(templateId).toURL, tmpFile)
      IO.createViaTemporary(localDir) { templateDir =>
        IO.unzip(tmpFile, templateDir)
      }
    }
    localDir
  }

  def hasNewIndex(currentHash: String): Boolean = {
    try {
      IO.withTemporaryDirectory { tmpDir =>
        val indexProps = new File(tmpDir, "index.properties")
        resolveIndexProperties(indexProps)
        val props = new CacheProperties(indexProps)
        def recentEnough = props.cacheIndexBinaryIncrementVersion >= Constants.INDEX_BINARY_INCREMENT_VERSION
        def differentCache = props.cacheIndexHash != currentHash
        differentCache && recentEnough
      }
    } catch {
      // In the event of download failure, just assume we don't have a newer index.
      case NonFatal(e) => false
    }
  }

  def resolveIndexTo(indexDirOrFile: File): String = {
    IO.withTemporaryDirectory { tmpDir =>
      val indexProps = new File(tmpDir, "index.properties")
      // TODO - Don't redownload this sucker...
      resolveIndexProperties(indexProps)
      val props = new CacheProperties(indexProps)
      val indexZip = new File(tmpDir, "index.zip")
      download(layout.index(props.cacheIndexHash).toURL, indexZip)
      val zipHash = activator.hashing.hash(indexZip)
      IO.createViaTemporary(indexDirOrFile) { indexExpanded =>
        IO.unzip(indexZip, indexExpanded)
      }
      zipHash
    }
  }
  /**
   * Downloads the current inex properties into
   */
  def resolveIndexProperties(localPropsFile: File): File = {
    // TODO - Should we be going directly to s3 here?
    // we are trying it to bypass CloudFront cache.
    try downloadFromS3(layout.currentIndexTag, localPropsFile)
    catch {
      // Our backup for local-file based testing...
      case NonFatal(err) =>
        log.info(s"Failed to grab s3 bucket, attempting to hit HTTP server. ${err.getClass.getName}: ${err.getMessage}")
        download(layout.currentIndexTag.toURL, localPropsFile)
    }
    localPropsFile
  }
}
