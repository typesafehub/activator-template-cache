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

class DefaultTemplateCache(
  actorFactory: ActorRefFactory,
  provider: IndexDbProvider,
  location: File,
  remote: RemoteTemplateRepository)(
    implicit timeout: Timeout) extends TemplateCache {

  val handler = actorFactory.actorOf(Props(new TemplateCacheActor(provider, location, remote)), "template-cache")
  import actorFactory.dispatcher
  import TemplateCacheActor._

  def template(id: String): Future[Option[Template]] =
    (handler ? GetTemplate(id)).mapTo[TemplateResult].map(_.template)
  def tutorial(id: String): Future[Option[Tutorial]] =
    (handler ? GetTutorial(id)).mapTo[TutorialResult].map(_.tutorial)
  def search(query: String): Future[Iterable[TemplateMetadata]] =
    (handler ? SearchTemplates(query)).mapTo[TemplateQueryResult].map(_.templates)
  def metadata: Future[Iterable[TemplateMetadata]] =
    (handler ? ListTemplates).mapTo[TemplateQueryResult].map(_.templates)
  def featured: Future[Iterable[TemplateMetadata]] =
    (handler ? ListFeaturedTemplates).mapTo[TemplateQueryResult].map(_.templates)

  override def close(): Unit = {
    actorFactory.stop(handler)
  }
}

object DefaultTemplateCache {
  /** Creates a default template cache for us. */
  def apply(actorFactory: ActorRefFactory,
    location: File,
    remote: RemoteTemplateRepository = defaultRemoteRepo,
    seedRepository: Option[File] = None)(
      implicit timeout: Timeout): TemplateCache = {
    // If we have a seed repository, copy it over.
    seedRepository foreach (repo => ZipInstallHelper.copyLocalCacheIfNeeded(location, repo))
    val indexProvider = LuceneIndexProvider
    // TODO - Copy cache if needed?
    new DefaultTemplateCache(actorFactory, indexProvider, location, remote)
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
    def hasNewIndex(oldId: String): Boolean = false
    def resolveIndexTo(indexDirOrFile: File): String =
      sys.error("Offline mode! Cannot resolve new index.")
  }
}
