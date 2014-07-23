/**
 * Copyright (C) 2013 Typesafe <http://typesafe.com/>
 */
package activator
package cache

import java.io.File
import akka.actor.{ Stash, Actor, ActorLogging, Status }
import sbt.{ IO, PathFinder }
import scala.util.control.NonFatal
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
class TemplateCacheActor(provider: IndexDbProvider, location: File, remote: RemoteTemplateRepository, autoUpdate: Boolean)
  extends Actor with ForwardingExceptions with ActorLogging with Stash {
  import TemplateCacheActor._

  def receive = {
    case InitializeNormal =>
      unstashAll()
      context become receiveNormal
    case InitializeFailure(e) =>
      unstashAll()
      context become receiveFailure(e)
    case _ =>
      // used to prevent race - if someone sends a message to the actor before it is properly initialized we just stash it
      stash()
  }

  def receiveNormal: Receive = forwardingExceptionsToFutures {
    case GetTemplate(id: String) => sender ! TemplateResult(getTemplate(id))
    case GetTutorial(id: String) => sender ! TutorialResult(getTutorial(id))
    case SearchTemplates(query, max) => sender ! TemplateQueryResult(searchTemplates(query, max))
    case SearchTemplateByName(name) => sender ! TemplateQueryResult(searchTemplateByName(name))
    case ListTemplates => sender ! TemplateQueryResult(listTemplates)
    case ListFeaturedTemplates => sender ! TemplateQueryResult(listFeaturedTemplates)
  }

  def receiveFailure(e: Exception): Receive = {
    case _ => sender ! Status.Failure(e)
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
        if (templateDir.isDirectory) sbt.IO delete templateDir
        // Also, we should probably wrap this in some sort of exception we can use later...
        throw ResolveTemplateException(s"Unable to download template: $id", ex)
    }
  }

  var index: IndexDb = null
  var props: CacheProperties = null

  override def preStart(): Unit = {
    try {
      // Our index is underneath the cache location...
      val cachePropertiesFile = new File(location, Constants.CACHE_PROPS_FILENAME)
      props = new CacheProperties(cachePropertiesFile)
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
          log.warning("Failed to check remote server for template catalog updates. (${e.getClass.getName}): ${e.getMessage})")
      }

      if (!cachePropertiesFile.exists) {
        if (autoUpdate)
          log.error(s"We don't have ${cachePropertiesFile.getAbsolutePath} so we won't have a working template catalog.")
        else
          log.error(s"Template catalog updates are disabled, and ${cachePropertiesFile.getAbsolutePath} didn't already exist, so we won't have a working template catalog.")
      } else if (!indexFile.exists) {
        log.error(s"We don't have ${indexFile.getAbsolutePath} even though we have ${cachePropertiesFile.getAbsolutePath}, so we won't have a working template catalog.")
      }

      // Now we open the index file.
      index = provider.open(indexFile)
      self ! InitializeNormal
    } catch {
      case e: Exception =>
        log.error("Could not load the template catalog. (${e.getClass.getName}: ${e.getMessage}", e)
        self ! InitializeFailure(e)
    }
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

  case object InitializeNormal
  case class InitializeFailure(e: Exception)
}
