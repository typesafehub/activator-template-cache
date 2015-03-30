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
import java.util.TimeZone
import java.util.GregorianCalendar
import java.util.Calendar
import activator.ProcessFailure
import activator.Validating

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
case class InstanceValidated(uuid: UUID, name: String) extends InstanceStatus {
  def detail = s"This template was published successfully! (It may take some time to show up on the site.)"
  def discriminator = "validated"
}
case class InstanceFailed(uuid: UUID, errors: Seq[String]) extends InstanceStatus {
  def detail = "This template failed to publish. Please try again (after correcting any issues)."
  def discriminator = "failed"
}
// this is a legacy value in the database or API without a name stored
case class InstanceValidatedWithOptionalName(uuid: UUID, nameOption: Option[String]) {
  def withFallbackName(fallback: => String): InstanceValidated =
    InstanceValidated(uuid, nameOption.getOrElse(fallback))
}

object InstanceValidatedWithOptionalName {
  implicit object InstanceValidatedWithOptionalNameKeyValueMapper extends KeyValueMapper[InstanceValidatedWithOptionalName] {
    override def toMap(t: InstanceValidatedWithOptionalName): Map[String, Any] = {
      t.nameOption map { name => Map("name" -> name) } getOrElse Map.empty
    }

    override def fromMap(key: String, m: Map[String, Any]): Option[InstanceValidatedWithOptionalName] = {
      Some(InstanceValidatedWithOptionalName(UUID.fromString(key), m.getAs[String]("name")))
    }
  }

  implicit object InstanceValidatedWithOptionalNameKeyExtractor extends KeyExtractor[InstanceValidatedWithOptionalName] {
    override def getKey(t: InstanceValidatedWithOptionalName): String = t.uuid.toString
  }
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

  implicit object InstanceValidatedKeyValueMapper extends KeyValueMapper[InstanceValidated] {
    override def toMap(t: InstanceValidated): Map[String, Any] = {
      Map("name" -> t.name)
    }

    override def fromMap(key: String, m: Map[String, Any]): Option[InstanceValidated] = {
      for {
        name <- m.getAs[String]("name")
      } yield InstanceValidated(UUID.fromString(key), name = name)
    }
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

// lets us return http status code plus a string, without
// depending on an http library
case class HttpTextResult(code: Int, text: String)

object HttpTextResult {
  def ok(text: String) = HttpTextResult(code = 200, text = text)
  def badRequest(text: String) = HttpTextResult(code = 400, text = text)
  def internalServerError(text: String) = HttpTextResult(code = 500, text = text)
}

case class Day(year: Int, month: Int, day: Int) {
  require(month > 0)
  require(month < 13)
  require(day > 0)
  require(day < 32)

  override def toString(): String = f"${year}%04d${month}%02d${day}%02d"
}

object Day {
  private final val utc = TimeZone.getTimeZone("UTC")

  def apply(value: String): Day = {
    parse(value).fold(identity,
      { errors =>
        throw new RuntimeException(errors.map(e => e.msg).mkString)
      })
  }

  def today(): Day = {
    // is this too expensive? no idea
    val now = new GregorianCalendar(utc)
    Day(now.get(Calendar.YEAR), now.get(Calendar.MONTH) + 1, now.get(Calendar.DAY_OF_MONTH))
  }

  def parse(value: String): ProcessResult[Day] = {
    if (value.length != "yyyymmdd".length) {
      ProcessFailure("day not in yyyymmdd format")
    } else {
      Validating.withMsg(s"Invalid day value '$value'") {
        val year = Integer.parseInt(value.substring(0, 4))
        val month = Integer.parseInt(value.substring(4, 6))
        val day = Integer.parseInt(value.substring(6, 8))
        Day(year, month, day)
      }
    }
  }
}

// Long rather than Int used for stats due to our OPTIMISM
case class TemplateStats(name: String, clones: Long)

// this class has operations on the key-value store which
// are also exported via the REST API.
// So it keeps the backend in sync with the REST client.
trait StorageRestOps {

  // start a new template instance, pulling from source, with a caller-provided UUID
  // If creator is None, this instance will be owned by the source URI
  // (so can only be republished from there, with no other auth).
  // If creator is Some(something) then the creator can publish the template name
  // from any source, plus the original source can be republished by anyone.
  def createTemplateInstance(source: URI, creator: Option[AccountInfo] = None)(implicit auth: ProofOfAuthentication, ec: ExecutionContext): Future[UUID]

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

  def accountsForTemplate(name: String)(implicit auth: ProofOfAuthentication, ec: ExecutionContext): Future[Seq[AccountInfo]]
  def addAccountForTemplate(name: String, account: AccountInfo)(implicit auth: ProofOfAuthentication, ec: ExecutionContext): Future[Unit]
  def removeAccountForTemplate(name: String, account: AccountInfo)(implicit auth: ProofOfAuthentication, ec: ExecutionContext): Future[Unit]

  def findRepublishCookie(name: String)(implicit auth: ProofOfAuthentication, ec: ExecutionContext): Future[Option[String]]

  // called anonymously by github in theory in the future
  // when we need auth on the hook
  // look up template by cookie and reload from its git url if any
  // this throws errors, but on the outermost layer we should just eat them
  // and log them rather than return them to github
  def republishHook(cookie: String)(implicit ec: ExecutionContext): Future[Unit]

  // the github hook we're actually using right now, the above republishHook
  // would only be needed if we add auth other than "use same git repo as before"
  // the payload is the JSON we got from github, just passed along as-is.
  def githubHook(payload: String)(implicit ec: ExecutionContext): Future[HttpTextResult]

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

  /**
   * Get a list of all templates that are not indexed.
   */
  def notIndexed()(implicit auth: ProofOfAuthentication, ec: ExecutionContext): Future[Seq[String]]

  def setCategory(name: String, category: String)(implicit auth: ProofOfAuthentication, ec: ExecutionContext): Future[Unit]

  // check-in from a worker (notifies that worker is alive)
  def workerCheckin(worker: String)(implicit auth: ProofOfAuthentication, ec: ExecutionContext): Future[Unit]

  // get the last checkin time for a worker (currentTimeMillis)
  def lastWorkerCheckin(worker: String)(implicit ec: ExecutionContext): Future[Long]

  def recordTemplateCloned(name: String)(implicit auth: ProofOfAuthentication, ec: ExecutionContext): Future[Unit]

  def templateStatsForDay(day: Day)(implicit auth: ProofOfAuthentication, ec: ExecutionContext): Future[Seq[TemplateStats]]
}

object StorageRestOps {
  val WORKER_CHECKIN_FREQUENCY_PATH = "activator.worker-checkin-frequency"
  val INDEXER_NAME = "indexer"
  val ANALYZER_NAME = "analyzer"
}
