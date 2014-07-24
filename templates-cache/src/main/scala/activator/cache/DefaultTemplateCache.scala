/**
 * Copyright (C) 2013 Typesafe <http://typesafe.com/>
 */
package activator
package cache

import java.io.File
import scala.concurrent.Future
import akka.actor.{ ActorRefFactory, Props }
import akka.pattern.ask
import akka.util.Timeout
import java.util.UUID
import java.net.URI
import scala.util.control.NonFatal

class DefaultTemplateCache(
  actorFactory: ActorRefFactory,
  provider: IndexDbProvider,
  location: File,
  remote: RemoteTemplateRepository,
  autoUpdate: Boolean)(
    implicit timeout: Timeout) extends TemplateCache {

  val handler = actorFactory.actorOf(Props(new TemplateCacheActor(provider, location, remote, autoUpdate = autoUpdate)), "template-cache")
  import actorFactory.dispatcher
  import TemplateCacheActor._

  def template(id: String): Future[Option[Template]] =
    (handler ? GetTemplate(id)).mapTo[TemplateResult].map(_.template)
  def tutorial(id: String): Future[Option[Tutorial]] =
    (handler ? GetTutorial(id)).mapTo[TutorialResult].map(_.tutorial)
  def search(query: String): Future[Iterable[TemplateMetadata]] =
    (handler ? SearchTemplates(query)).mapTo[TemplateQueryResult].map(_.templates)
  def searchByName(name: String): Future[Option[TemplateMetadata]] =
    (handler ? SearchTemplateByName(name)).mapTo[TemplateQueryResult].map(_.templates.headOption)
  def metadata: Future[Iterable[TemplateMetadata]] =
    (handler ? ListTemplates).mapTo[TemplateQueryResult].map(_.templates)
  def featured: Future[Iterable[TemplateMetadata]] =
    (handler ? ListFeaturedTemplates).mapTo[TemplateQueryResult].map(_.templates)

  override def close(): Unit = {
    actorFactory.stop(handler)
  }
}

object DefaultTemplateCache {
  private val defaultAutoUpdate = try {
    (System.getProperty("activator.checkForTemplateUpdates", "true")) == "true"
  } catch {
    case NonFatal(e) => true
  }

  /** Creates a default template cache for us. */
  def apply(actorFactory: ActorRefFactory,
    location: File,
    remote: RemoteTemplateRepository = defaultRemoteRepo,
    seedRepository: Option[File] = None,
    autoUpdate: Boolean = defaultAutoUpdate)(
      implicit timeout: Timeout): TemplateCache = {
    // If we have a seed repository, copy it over.
    seedRepository foreach (repo => ZipInstallHelper.copyLocalCacheIfNeeded(location, repo))
    val indexProvider = LuceneIndexProvider
    // TODO - Copy cache if needed?
    new DefaultTemplateCache(actorFactory, indexProvider, location, remote, autoUpdate)
  }

  /**
   * The default remote repository configured via builder properties.
   *  This is bascially just an offline repository.
   */
  object defaultRemoteRepo extends RemoteTemplateRepository {
    // TODO - Implement me!
    def resolveIndexProperties(localPropsFile: File): File = {
      sys.error(s"Offline mode! Cannot resolve index properties.")
    }
    def resolveTemplateTo(templateId: String, localDir: File): File = {
      if (!localDir.exists) sys.error(s"Offline mode! Cannot resolve template: $templateId")
      localDir
    }
    def hasNewIndexProperties(oldId: String): Boolean = false
    def resolveLatestIndexHash(): String = sys.error(s"Offline mode! Can't get latest index hash")
    def ifNewIndexProperties(currentHash: String)(onNewProperties: CacheProperties => Unit): Unit = ()

    def resolveIndexTo(indexDirOrFile: File, currentHash: String): Unit =
      sys.error("Offline mode! Cannot resolve new index.")

    def templateBundleURI(activatorVersion: String,
      uuid: UUID,
      templateName: String): URI =
      sys.error("Offline mode! can't get template bundle")

    def templateBundleExists(activatorVersion: String,
      uuid: UUID,
      templateName: String): Boolean =
      sys.error("Offline mode! can't get template bundle")

    def templateZipURI(uuid: UUID): URI =
      sys.error("Offline mode! can't get template zip")

    def authorLogoURI(uuid: UUID): URI =
      sys.error("Offline mode! can't get author logo")

    def resolveMinimalActivatorDist(toFile: File, activatorVersion: String): File =
      sys.error("Offline mode! Can't get minimal activator dist")
  }
}
