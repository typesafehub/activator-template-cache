/**
 * Copyright (C) 2013 Typesafe <http://typesafe.com/>
 */
package activator
package cache

import java.io.{ File, FileInputStream, FilenameFilter }
import java.util.{ Date, Properties, UUID }

import activator.templates.repository.RepositoryException
import akka.actor.{ Actor, ActorLogging, Stash, Status }
import sbt.{ IO, PathFinder }

import scala.util.control.NonFatal

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

  def receiveFailure(e: Throwable): Receive = {
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

  private def indexNonIndexedTemplates(index: IndexDb): Boolean = {
    val isActivatorProperties = new FilenameFilter {
      override def accept(dir: File, name: String): Boolean =
        name.equals(Constants.METADATA_FILENAME)
    }
    val writer = provider.write(new File(location, Constants.METADATA_INDEX_FILENAME))

    try {
      val templates: Array[(File, IndexStoredTemplateMetadata)] =
        location.listFiles().flatMap { file =>
          if (file.isDirectory && index.template(file.getName).isEmpty) file.listFiles(isActivatorProperties) else Nil
        } map { activatorPropertiesFile =>
          val props = new Properties()
          props.load(new FileInputStream(activatorPropertiesFile))

          val authorDefinedTemplateMetadata = AuthorDefinedTemplateMetadata.fromProperties(props)
          val time: Long = new Date().getTime

          (
            activatorPropertiesFile.getParentFile,
            IndexStoredTemplateMetadata(
              id = UUID.randomUUID().toString,
              timeStamp = time,
              featured = false,
              usageCount = None,
              name = authorDefinedTemplateMetadata.name,
              title = authorDefinedTemplateMetadata.title,
              description = authorDefinedTemplateMetadata.description,
              authorName = authorDefinedTemplateMetadata.authorName.getOrElse("Anonymous"),
              authorLink = authorDefinedTemplateMetadata.authorLink.getOrElse(""),
              tags = authorDefinedTemplateMetadata.tags,
              templateTemplate = false,
              sourceLink = authorDefinedTemplateMetadata.sourceLink.getOrElse(""),
              authorLogo = authorDefinedTemplateMetadata.authorLogo,
              authorBio = authorDefinedTemplateMetadata.authorBio,
              authorTwitter = authorDefinedTemplateMetadata.authorTwitter,
              category = TemplateMetadata.Category.COMPANY,
              creationTime = time
            )
          )
        }

      val templateNeedsToBeReIndexed: Boolean =
        templates.exists {
          case (_, templateMetadata) =>
            index.templateByName(templateMetadata.name) exists { indexedTemplate =>
              val mergedTemplateInfo: IndexStoredTemplateMetadata =
                templateMetadata.copy(
                  id = indexedTemplate.id,
                  timeStamp = indexedTemplate.timeStamp,
                  creationTime = indexedTemplate.creationTime,
                  featured = indexedTemplate.featured,
                  usageCount = indexedTemplate.usageCount,
                  templateTemplate = indexedTemplate.templateTemplate,
                  category = indexedTemplate.category)
              val different = mergedTemplateInfo != indexedTemplate
              if (different) {
                log.debug(s"Template needs to be re indexed ${mergedTemplateInfo.name}")
                log.debug(s"Template $mergedTemplateInfo")
                log.debug(s"Indexed Template $indexedTemplate")
              }
              different
            }
        }

      if (!templateNeedsToBeReIndexed) {
        templates.filter(Function.tupled((_, templateMetadata) => index.templateByName(templateMetadata.name).isEmpty)) foreach {
          case (file, templateMetadata) =>
            IO.copyDirectory(file, new File(location, templateMetadata.id))
            writer.insert(templateMetadata)
            log.debug(s"Template ${templateMetadata.name}  indexed as ${templateMetadata.id}")
            log.debug(s"Template $templateMetadata")
            IO delete file
        }
        true
      } else {
        templates.flatMap { case (_, templateMetadata) => index.templateByName(templateMetadata.name) } foreach {
          case (templateMetadata) =>
            val file: File = new File(location, templateMetadata.id)
            if (file.exists()) IO.delete(file)
        }
        false
      }
    } finally {
      writer.close()
    }
  }

  // TODO it would be nice to make this a parameter to InitializeNormal
  // and then pass it around to all the methods
  var index: IndexDb = null

  override def preStart(): Unit = {
    def initIndex: InitializeMessage = {
      // Our index is underneath the cache location...
      val cachePropertiesFile = new File(location, Constants.CACHE_PROPS_FILENAME)
      if (!location.isDirectory() && !location.mkdirs())
        log.warning(s"Could not create directory ${location}")
      val props = new CacheProperties(cachePropertiesFile)
      val indexFile = new File(location, Constants.METADATA_INDEX_FILENAME)
      val fatalError = try {
        if (autoUpdate) {
          remote.ifNewIndexProperties(props.cacheIndexHash.getOrElse("")) { newProps =>
            val newHash = newProps.cacheIndexHash.getOrElse(throw RepositoryException("No index hash field in downloaded index.properties file", null))
            // download the new index
            remote.resolveIndexTo(indexFile, newHash)
            // if the download succeeds, update our local props
            props.cacheIndexHash = newHash
            props.save("Updating the local index properties.")
            log.debug(s"Saved new template index hash ${newHash} to ${props.location.getAbsolutePath}")
          }
        }

        // We may have the latest index properties but not have the actual index; this
        // may either happen in the seed generator, which requires an index properties to be provided or
        // because one of the local templates needed to be re-indexed.
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
            // this is a little weird but let's go with it
            log.debug(s"We appear to have a template catalog ${indexFile.getAbsolutePath}, but we don't know its hash")
          }
          // if indexFile exists, we can always proceed
          None
        } else {
          // We get here if things are weird: no exception was thrown but we didn't end up downloading an index.
          // If autoUpdate=true this is probably a bug in our code. If it's false, then it means we have no
          // cache and also aren't supposed to update to get one, so we are hosed.
          if (!cachePropertiesFile.exists || !props.cacheIndexHash.isDefined) {
            if (autoUpdate)
              Some(RepositoryException(s"We don't have ${cachePropertiesFile.getAbsolutePath} with an index hash in it, even though we should have downloaded one", null))
            else
              Some(RepositoryException(s"Template catalog updates are disabled, and ${cachePropertiesFile.getAbsolutePath} didn't already exist with an index hash in it", null))
          } else {
            Some(RepositoryException(s"We don't have ${indexFile.getAbsolutePath} even though we have ${cachePropertiesFile.getAbsolutePath} with hash ${props.cacheIndexHash.get}", null))
          }
        }
      } catch {
        case NonFatal(e) =>
          // We get here if downloading the properties file or the index itself threw an exception
          if (indexFile.exists) {
            log.warning(s"Failed to update template catalog so using the old one (${e.getClass.getName}): ${e.getMessage})")
            None
          } else {
            Some(e)
          }
      }
      val noteToSelf: InitializeMessage = fatalError map { e =>
        log.error(e, s"Could not find a template catalog. (${e.getClass.getName}: ${e.getMessage}")
        InitializeFailure(e)
      } getOrElse {
        val index: IndexDb = provider.open(indexFile)
        val reIndex: Boolean =
          try {
            !indexNonIndexedTemplates(index)
          } finally {
            index.close()
          }
        try {
          if (reIndex) {
            log.debug("re-indexing")
            IO delete indexFile
            initIndex
          } else {
            // try actually opening the index
            this.index = provider.open(indexFile)
            InitializeNormal
          }
        } catch {
          case NonFatal(e) =>
            log.error(e, s"Could not open the template catalog. (${e.getClass.getName}: ${e.getMessage}")
            InitializeFailure(e)
        }
      }
      noteToSelf
    }

    self ! initIndex
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

  sealed trait InitializeMessage
  case object InitializeNormal extends InitializeMessage
  case class InitializeFailure(e: Throwable) extends InitializeMessage
}
