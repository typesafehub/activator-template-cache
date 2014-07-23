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
            if (ex.getCause ne null)
              log.warning(s"${ex.getMessage}: ${ex.getCause.getClass.getName}: ${ex.getCause.getMessage}")
            else
              log.warning(ex.getMessage)
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
      if (!location.isDirectory() && !location.mkdirs())
        log.warning(s"Could not create directory ${location}")
      props = new CacheProperties(cachePropertiesFile)
      val indexFile = new File(location, Constants.METADATA_INDEX_FILENAME)
      try {
        if (autoUpdate) {
          remote.ifNewIndexProperties(props.cacheIndexHash.getOrElse("")) { newProps =>
            val newHash = newProps.cacheIndexHash.getOrElse(throw RepositoryException("No index hash field in index.properties file", null))
            // download the new index
            remote.resolveIndexTo(indexFile, newHash)
            // if the download succeeds, update our local props
            props.cacheIndexHash = newHash
            props.save("Updating the local index properties.")
            log.debug(s"Saved new template index hash ${newHash} to ${props.location.getAbsolutePath}")
          }
        }

        // We may have the latest index properties but not have the actual index; this
        // happens in the seed generator, which requires an index properties to be provided.
        if (!indexFile.exists) {
          props.cacheIndexHash foreach { currentHash =>
            log.info(s"We have index hash ${currentHash} but haven't downloaded that index - attempting to download it now.")
            remote.resolveIndexTo(indexFile, currentHash)
          }
        }

        if (indexFile.exists) {
          props.cacheIndexHash map { currentHash =>
            log.debug(s"Updated to latest template catalog ${currentHash}, saved in ${location.getAbsolutePath}")
          } getOrElse {
            log.debug(s"We appear to have a template catalog ${indexFile.getAbsolutePath}, but we don't know its hash")
          }
        }
      } catch {
        case e: RepositoryException =>
          log.warning(s"Failed to update template catalog. (${e.getClass.getName}): ${e.getMessage})")
      }

      if (!indexFile.exists) {
        if (!cachePropertiesFile.exists || !props.cacheIndexHash.isDefined) {
          if (autoUpdate)
            log.error(s"We don't have ${cachePropertiesFile.getAbsolutePath} with an index hash in it, so we won't have a working template catalog.")
          else
            log.error(s"Template catalog updates are disabled, and ${cachePropertiesFile.getAbsolutePath} didn't already exist with an index hash in it, so we won't have a working template catalog.")
        } else {
          log.error(s"We don't have ${indexFile.getAbsolutePath} even though we have ${cachePropertiesFile.getAbsolutePath} with hash ${props.cacheIndexHash.get}, so we won't have a working template catalog.")
        }
      }

      // Now we open the index file.
      index = provider.open(indexFile)
      self ! InitializeNormal
    } catch {
      case e: Exception =>
        log.error(e, s"Could not load the template catalog. (${e.getClass.getName}: ${e.getMessage}")
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
