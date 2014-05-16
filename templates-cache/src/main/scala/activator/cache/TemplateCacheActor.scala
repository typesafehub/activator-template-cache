/**
 * Copyright (C) 2013 Typesafe <http://typesafe.com/>
 */
package activator
package cache

import java.io.File
import akka.actor.{ Actor, ActorLogging }
import asbt.{ IO, PathFinder }
import scala.util.control.NonFatal
import akka.actor.Status
import activator.templates.repository.RepositoryException

/** This class represents the inability to resolve a template from the internet, and not some other fatal error. */
case class ResolveTemplateException(msg: String, cause: Throwable) extends RuntimeException(msg, cause)
/**
 * This actor provides an implementation of the tutorial cache
 * that allows us to handle failures via actor-restart and threading
 * via single-access to the cache.
 *
 * Although, if we use lucene, we could have multi-threaded access, best
 * not to assume technology for now.
 *
 * TODO - Add a manager in front of this actor that knows how to update the lucene index and reboot this guy.
 */
class TemplateCacheActor(provider: IndexDbProvider, location: File, remote: RemoteTemplateRepository, autoUpdate: Boolean = true)
  extends Actor with ForwardingExceptions with ActorLogging {
  import TemplateCacheActor._

  def receive: Receive = forwardingExceptionsToFutures {
    // TODO - Make sure we send failures to sender as well, so futures
    // complete immediately.
    case GetTemplate(id: String) => sender ! TemplateResult(getTemplate(id))
    case GetTutorial(id: String) => sender ! TutorialResult(getTutorial(id))
    case SearchTemplates(query, max) => sender ! TemplateQueryResult(searchTemplates(query, max))
    case SearchTemplateByName(name) => sender ! TemplateQueryResult(searchTemplateByName(name))
    case ListTemplates => sender ! TemplateQueryResult(listTemplates)
    case ListFeaturedTemplates => sender ! TemplateQueryResult(listFeaturedTemplates)
  }

  def listTemplates = fillMetadata(index.metadata)
  def listFeaturedTemplates = fillMetadata(index.featured)

  def searchTemplates(query: String, max: Int): Iterable[TemplateMetadata] =
    fillMetadata(index.search(query, max))

  def searchTemplateByName(name: String): Iterable[TemplateMetadata] =
    fillMetadata(index.templateByName(name))

  private def allFilesIn(dir: File): Seq[File] = (PathFinder(dir).*** --- PathFinder(dir)).get
  def getTutorial(id: String): Option[Tutorial] = {
    val tutorialDir = new java.io.File(getTemplateDirAndEnsureLocal(id), Constants.TUTORIAL_DIR)
    if (tutorialDir.exists) {
      val fileMappings = for {
        file <- allFilesIn(tutorialDir)
        if !file.isDirectory
        relative <- IO.relativize(tutorialDir, file)
        if !relative.isEmpty
      } yield relative -> file
      Some(Tutorial(id, fileMappings.toMap))
    } else None
  }

  def getTemplate(id: String): Option[Template] = {
    index.template(id) match {
      case Some(metadata) =>
        try {
          val localDir = getTemplateDirAndEnsureLocal(id)
          val fileMappings = for {
            file <- allFilesIn(localDir)
            if (!file.isDirectory)
            relative <- IO.relativize(localDir, file)
            if !relative.isEmpty
            if !(relative startsWith Constants.TUTORIAL_DIR)
          } yield file -> relative
          val meta = TemplateMetadata(
            persistentConfig = metadata,
            locallyCached = true)
          Some(Template(meta, fileMappings))
        } catch {
          case ex: ResolveTemplateException =>
            log.error(s"Failed to resolve template: $id from remote repository.")
            None
        }
      case _ => None
    }
  }

  private def fillMetadata(metadata: Iterable[IndexStoredTemplateMetadata]): Iterable[TemplateMetadata] =
    metadata map { meta =>
      val locallyCached = isTemplateCached(meta.id)
      TemplateMetadata(persistentConfig = meta,
        locallyCached = locallyCached)
    }

  // TODO - return a file that is friendly for having tons of stuff in it,
  //i.e. maybe we take the first N of the id and use that as a directory first.
  private def templateLocation(id: String): File =
    new java.io.File(location, id)
  /**
   * Determines if we've cached a template.
   *  TODO - check other files?
   */
  private def isTemplateCached(id: String): Boolean =
    templateLocation(id).exists

  private def getTemplateDirAndEnsureLocal(id: String): File = {
    val templateDir = templateLocation(id)
    if (templateDir.exists) templateDir
    else try remote.resolveTemplateTo(id, templateLocation(id))
    catch {
      case NonFatal(ex) =>
        // We have a non-fatal exception, let's make sure the template directory is GONE, so the cache is consistent.
        if (templateDir.isDirectory) asbt.IO delete templateDir
        // Also, we should probably wrap this in some sort of exception we can use later...
        throw ResolveTemplateException(s"Unable to download template: $id", ex)
    }
  }

  var index: IndexDb = null
  var props: CacheProperties = null

  override def preStart(): Unit = {
    // Our index is underneath the cache location...
    props = new CacheProperties(new File(location, Constants.CACHE_PROPS_FILENAME))
    // Here we check to see if we need to update the local cache.
    val indexFile = new File(location, Constants.METADATA_INDEX_FILENAME)
    // Here we need to not throw...
    try {
      if (autoUpdate && remote.hasNewIndex(props.cacheIndexHash)) {
        val newHash = remote.resolveIndexTo(indexFile)
        props.cacheIndexHash = newHash
        props.save("Updating the local index.")
      }
    } catch {
      case e: RepositoryException => // Ignore, we're in offline mode.
        log.info("Unable to check remote server for template updates.")
    }
    // Now we open the index file.
    index = provider.open(indexFile)
  }
  override def postStop(): Unit = {
    if (index != null) {
      index.close()
    }
  }
}

object TemplateCacheActor {
  case class GetTemplate(id: String)
  case class GetTutorial(id: String)
  case class SearchTemplates(query: String, max: Int = 0)
  case class SearchTemplateByName(name: String)
  case object ListTemplates
  case object ListFeaturedTemplates

  case class TemplateResult(template: Option[Template])
  case class TemplateQueryResult(templates: Iterable[TemplateMetadata])
  case class TutorialResult(tutorial: Option[Tutorial])
}
