/**
 * Copyright (C) 2013 Typesafe <http://typesafe.com/>
 */
package activator
package templates

import cache._
import java.io.File
import java.util.concurrent.TimeUnit
import scala.concurrent.{ Future, Await }
import java.net.URI

/** This is a class that can be used within an sbt build to seed an Activator installation with pre-fetched templates and index. */
object TemplateCacheSeedGenerator {

  case class Arguments(
    localDirectory: File = new File("cache-repo"),
    remoteUri: URI = new URI("http://downloads.typesafe.com/typesafe-activator"),
    templates: Seq[String] = Seq.empty[String])

  def parseUsage(args: Array[String]): Arguments = {
    def parseImpl(args: Arguments, remaining: List[String]): Arguments =
      remaining match {
        case "-remote" :: remoteUri :: rest => parseImpl(args.copy(remoteUri = new URI(remoteUri)), rest)
        case "-file" :: file :: rest => parseImpl(args.copy(localDirectory = new File(file)), rest)
        case "-templates" :: templateNames :: Nil =>
          if (templateNames != "") args.copy(templates = templateNames.split(",").map(_.trim()))
          else args
        case unknown =>
          sys.error(s"""Unknown argument: $unknown
            Usage:  TemplateCacheSeedGenerator -remote <uri> -file <cache directory> -templates <template names separated by ",">
          """)
      }
    parseImpl(Arguments(), args.toList)
  }

  def main(args: Array[String]): Unit = {
    val arg = parseUsage(args)
    // TODO - Read in config for this.
    val cacheDir = arg.localDirectory
    // TODO - Pull this from config?
    implicit val timeout = akka.util.Timeout(120, TimeUnit.SECONDS)
    val system = akka.actor.ActorSystem()
    val remoteRepo = new repository.UriRemoteTemplateRepository(arg.remoteUri, system.log)

    // For futures
    implicit val ctx = system.dispatcher
    implicit val duration = timeout.duration
    try {
      val cache =
        DefaultTemplateCache(
          actorFactory = system,
          location = cacheDir,
          remote = remoteRepo,
          autoUpdate = false)

      val templateList =
        if (arg.templates.isEmpty) cache.featured
        else {
          cache.metadata.map { it =>
            val filtered = it.filter { tpl =>
              arg.templates.contains(tpl.name)
            }
            if (filtered.toList.length != arg.templates.length) throw new RuntimeException(s"Could not find template(s) matching name(s): ${arg.templates.mkString(",")}")
            filtered
          }
        }

      val templates =
        for {
          templates <- templateList
          results <- Future.traverse(templates map (_.id))(cache.template)
        } yield results.toSeq.flatten
      Await.result(templates, duration)
    } finally system.shutdown()
  }
}
