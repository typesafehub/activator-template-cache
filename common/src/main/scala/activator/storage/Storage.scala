/**
 * Copyright (C) 2013 Typesafe <http://typesafe.com/>
 */
package activator.storage

import java.util.UUID
import java.net.URI
import scala.concurrent.Future
import scala.concurrent.ExecutionContext
import activator.cache.AuthorDefinedTemplateMetadata
import activator.cache.IndexStoredTemplateMetadata
import activator.ProcessResult

import KeyValueMapper.HasGetAs

// if it exists implicitly we have authenticated (server side)
// or provided the secret key (client side)
trait ProofOfAuthentication

// e.g. category=github, info=havocp
case class AccountInfo(category: String, info: String) {
  def toKey(): String = {
    category + ":" + info
  }
}

object AccountInfo {
  def apply(uri: URI): AccountInfo = {
    AccountInfo("uri", uri.toASCIIString())
  }
}

sealed trait InstanceStatus {
  def uuid: UUID

  def detail: String

  def discriminator: String
}
case class InstancePending(uuid: UUID, serial: Long) extends InstanceStatus {
  def detail = "This template is being processed."
  def discriminator = "pending"
}
case class InstanceValidated(uuid: UUID) extends InstanceStatus {
  def detail = "This template was published successfully! (It may take some time to show up on the site.)"
  def discriminator = "validated"
}
case class InstanceFailed(uuid: UUID, errors: Seq[String]) extends InstanceStatus {
  def detail = "This template failed to publish. Please try again (after correcting any issues)."
  def discriminator = "failed"
}

object InstanceStatus {
  // map an object that's always empty for now (i.e. the table is just a set of keys)
  // we do this to keep the option to add stuff to the value later, and to avoid
  // a special kind of data store.
  trait EmptyMapper[T] extends KeyValueMapper[T] {
    def fromKey(key: String): T
    override def toMap(t: T): Map[String, Any] = Map.empty
    override def fromMap(key: String, m: Map[String, Any]): Option[T] = Some(fromKey(key))
  }

  implicit object InstanceValidatedKeyValueMapper extends EmptyMapper[InstanceValidated] {
    override def fromKey(key: String) = InstanceValidated(UUID.fromString(key))
  }

  implicit object InstancePendingKeyValueMapper extends KeyValueMapper[InstancePending] {
    override def toMap(t: InstancePending): Map[String, Any] = {
      Map("serial" -> t.serial)
    }

    override def fromMap(key: String, m: Map[String, Any]): Option[InstancePending] = {
      for {
        serial <- m.getAs[Long]("serial")
      } yield InstancePending(UUID.fromString(key), serial)
    }
  }

  implicit object InstanceFailedKeyValueMapper extends KeyValueMapper[InstanceFailed] {
    override def toMap(t: InstanceFailed): Map[String, Any] = {
      Map("errors" -> t.errors)
    }

    override def fromMap(key: String, m: Map[String, Any]): Option[InstanceFailed] = {
      // "errors" field can be absent to mean empty
      val errors = m.getAs[Seq[String]]("errors").getOrElse(Seq.empty[String])
      Some(InstanceFailed(UUID.fromString(key), errors))
    }
  }

  implicit object InstanceStatusKeyValueWriter extends KeyValueWriter[InstanceStatus] {
    override def toMap(t: InstanceStatus): Map[String, Any] = {
      t match {
        case i: InstancePending => implicitly[KeyValueMapper[InstancePending]].toMap(i)
        case i: InstanceValidated => implicitly[KeyValueMapper[InstanceValidated]].toMap(i)
        case i: InstanceFailed => implicitly[KeyValueMapper[InstanceFailed]].toMap(i)
      }
    }
  }

  implicit object InstanceStatusKeyExtractor extends KeyExtractor[InstanceStatus] {
    override def getKey(t: InstanceStatus): String = t.uuid.toString
  }
}

sealed trait IndexJob
case object NothingToIndex extends IndexJob
case class NeedToIndex(serial: Long, everything: Seq[IndexStoredTemplateMetadata]) extends IndexJob

trait InstanceValidationDetails {
  def uuid: UUID
  def serial: Long
  def source: URI
}

object InstanceValidationDetails {
  implicit object InstanceValidationDetailsKeyValueMapper extends KeyValueMapper[InstanceValidationDetails] {
    override def toMap(t: InstanceValidationDetails): Map[String, Any] = {
      // note: don't put uuid in here, it's the key
      Map("serial" -> t.serial,
        "source" -> t.source.toASCIIString())
    }

    private case class ConcreteInstanceValidationDetails(uuid: UUID, serial: Long, source: URI) extends InstanceValidationDetails

    override def fromMap(key: String, m: Map[String, Any]): Option[InstanceValidationDetails] = {
      for {
        serial <- m.getAs[Long]("serial")
        source <- m.getAs[String]("source").map(new URI(_))
      } yield ConcreteInstanceValidationDetails(uuid = UUID.fromString(key),
        serial = serial, source = source)
    }
  }

  implicit object InstanceValidationDetailsKeyExtractor extends KeyExtractor[InstanceValidationDetails] {
    override def getKey(t: InstanceValidationDetails): String = t.uuid.toString
  }
}

// this class has operations on the key-value store which
// are also exported via the REST API.
// So it keeps the backend in sync with the REST client.
trait StorageRestOps {

  // start a new template instance, pulling from source, with a caller-provided UUID
  // this instance will be owned by the source URI (so can only be republished from there,
  // with no other auth)
  def createTemplateInstance(source: URI)(implicit auth: ProofOfAuthentication, ec: ExecutionContext): Future[UUID]

  // query instances that need to be downloaded, validated, bundled, S3-uploaded;
  // invoked by the batch job to see what needs doing
  def instancesToValidate()(implicit auth: ProofOfAuthentication, ec: ExecutionContext): Future[Seq[InstanceValidationDetails]]

  // set status of given instance to validated (means the final bundle should exist on S3 now),
  // failing if the requested name is not available to the creator of the instance
  def validate(uuid: UUID, timestamp: Long, unvalidatedAuthorDefined: AuthorDefinedTemplateMetadata)(implicit auth: ProofOfAuthentication, ec: ExecutionContext): Future[ProcessResult[Unit]]

  def markFailed(uuid: UUID, errors: Seq[String])(implicit auth: ProofOfAuthentication, ec: ExecutionContext): Future[Unit]

  // not authenticated - used to generate status page on website
  def status(uuid: UUID)(implicit ec: ExecutionContext): Future[InstanceStatus]

  // used to generate admin page on website; returns the templates belonging to the account
  def templatesForAccount(account: AccountInfo)(implicit auth: ProofOfAuthentication, ec: ExecutionContext): Future[Seq[String]]

  def findRepublishCookie(name: String)(implicit auth: ProofOfAuthentication, ec: ExecutionContext): Future[Option[String]]

  // called anonymously by github
  // look up template by cookie and reload from its git url if any
  // this throws errors, but on the outermost layer we should just eat them
  // and log them rather than return them to github
  def republishHook(cookie: String)(implicit ec: ExecutionContext): Future[Unit]

  // these are all the templates that should be indexed next time we regen the index.
  // Note that some may turn out to be instances of the same template; in that case,
  // the indexer is obligated to index them in ascending order of instance serial.
  // Note that the indexSerial is different from the instance serial.
  def allTemplatesToIndex(latestIndexed: Long)(implicit ec: ExecutionContext): Future[IndexJob]

  // this is compared to the serial of the index we're using, or that
  // we're considering downloading, to see if we need to download
  // a fresh index.
  def currentIndexSerial()(implicit ec: ExecutionContext): Future[Long]

  def setFeatured(name: String, featured: Boolean)(implicit auth: ProofOfAuthentication, ec: ExecutionContext): Future[Unit]

  def setIndexed(name: String, indexed: Boolean)(implicit auth: ProofOfAuthentication, ec: ExecutionContext): Future[Unit]

  // check-in from a worker (notifies that worker is alive)
  def workerCheckin(worker: String)(implicit auth: ProofOfAuthentication, ec: ExecutionContext): Future[Unit]

  // get the last checkin time for a worker (currentTimeMillis)
  def lastWorkerCheckin(worker: String)(implicit ec: ExecutionContext): Future[Long]
}

object StorageRestOps {
  val WORKER_CHECKIN_FREQUENCY_PATH = "activator.worker-checkin-frequency"
  val INDEXER_NAME = "indexer"
  val ANALYZER_NAME = "analyzer"
}
