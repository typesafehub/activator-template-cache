/**
 * Copyright (C) 2013 Typesafe <http://typesafe.com/>
 */
package activator
package templates
package repository

import java.util.UUID
import java.net.URI
import com.amazonaws.services.s3.AmazonS3Client
import com.amazonaws.auth.BasicAWSCredentials
import com.amazonaws.ClientConfiguration
import com.amazonaws.Protocol
import activator.cache.CacheProperties
import com.amazonaws.services.s3.model.PutObjectRequest
import scala.util.control.NonFatal
import com.amazonaws.services.s3.model.GetObjectMetadataRequest
import com.amazonaws.services.s3.model.AmazonS3Exception

/**
 * This class can publish and and read from the S3 Repository.
 */
class S3PublishableTemplateRepository(log: akka.event.LoggingAdapter, baseUri: URI, username: String, passwd: String)
  extends UriRemoteTemplateRepository(baseUri, log)
  with PublishableTemplateRepository {

  protected override def makeClient(): AmazonS3Client = {
    new AmazonS3Client(
      new BasicAWSCredentials(username, passwd),
      new ClientConfiguration().withProtocol(Protocol.HTTPS))
  }
  def hasCurrentRemoteIndexSerial: ProcessResult[Boolean] =
    Validating.withMsg("Unable to access amazon S3") {
      val (bucket, key) = getBucketAndKey(layout.currentIndexTag)
      val request = new GetObjectMetadataRequest(bucket, key)
      try {
        makeClient().getObjectMetadata(bucket, key)
        true
      } catch {
        // TODO - Are there other ways we may get a 404 code?
        case e: AmazonS3Exception if e.getStatusCode == 404 =>
          false
      }
    }
  def currentRemoteIndexSerial: ProcessResult[Long] =
    for {
      file <- makeTmpFile
      _ <- download(layout.currentIndexTag, file)
      props = new CacheProperties(file)
    } yield props.cacheIndexSerial

  def publishTemplate(
    uuid: UUID,
    zipFile: java.io.File): ProcessResult[Unit] =
    for {
      location <- Validating.withMsg(s"Unable to publish template: $uuid") {
        layout.template(uuid.toString)
      }
      result <- publish(location, zipFile)
    } yield result

  def publishTemplateBundle(
    activatorVersion: String,
    uuid: UUID,
    templateName: String,
    zipFile: java.io.File): ProcessResult[Unit] =
    for {
      location <- Validating.withMsg(s"Unable to publish template bundle: $templateName $uuid") {
        layout.templateBundle(activatorVersion, uuid.toString, templateName)
      }
      result <- publish(location, zipFile)
    } yield result

  def publishIndex(indexZip: java.io.File, serial: Long): ProcessResult[Unit] =
    for {
      hash <- hashFile(indexZip)
      _ <- publish(layout.index(hash), indexZip)
      propsFile <- makeTmpFile
      props <- CacheProperties.write(propsFile, serial, hash)
      _ <- publish(layout.currentIndexTag, props)
    } yield ()

  private def cleanLocation(path: String): String =
    if (path startsWith "/") path drop 1
    else path

  private def hashFile(file: java.io.File): ProcessResult[String] =
    Validating.withMsg(s"Failed to hash index file: $file") {
      val h = hashing hash file
      log.info(s"index $file hashes to $h")
      h
    }
  private def download(uri: URI, file: java.io.File): ProcessResult[Unit] =
    Validating.withMsg(s"Failed to download $uri to $file") {
      downloadFromS3(uri, file)
    }
  private def makeTmpFile: ProcessResult[java.io.File] =
    Validating.withMsg(s"Unable to create temporary file") {
      val file = java.io.File.createTempFile("activator-cache", "properties")
      file.deleteOnExit()
      file
    }

  // This variant of publish will attempt to publish three times before
  // giving up all hope and bombing.
  private def publish(dest: URI, src: java.io.File): ProcessResult[Unit] =
    Validating.withMsg(s"Failed to publish $src to $dest") {
      val tries = 3
      def attemptToPublish(remainingTries: Int = tries): Unit =
        if (remainingTries > 0) {
          try publishUnsafe(dest, src)
          catch {
            case NonFatal(err) =>
              // TODO - Save the error?
              log.error(s"Failed to publish $src to $dest:  ${err.getClass.getName} - ${err.getMessage}")
              // We delay a bit before retrying
              // THAT'S RIGHT, block the world.
              Thread.sleep(10 * 1000L) // 10 seconds
              attemptToPublish(remainingTries - 1)
          }
        } else sys.error(s"Unable to resolve $src after $tries tries")

      attemptToPublish()
    }

  private def publishUnsafe(dest: URI, src: java.io.File): Unit = {
    log.info(s"publishing $src to $dest")
    val client = makeClient()
    val request = new PutObjectRequest(dest.getHost, cleanLocation(dest.getRawPath), src)
    client.putObject(request)
    log.info(s"publishing $src to $dest - DONE")
  }
}
