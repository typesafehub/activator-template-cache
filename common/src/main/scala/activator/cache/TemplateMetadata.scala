/**
 * Copyright (C) 2013 Typesafe <http://typesafe.com/>
 */
package activator
package cache

import java.net.URISyntaxException
import scala.concurrent.Future
import java.net.URLEncoder
import scala.concurrent.ExecutionContext

/**
 * This represents the metadata information that AUTHORS of Templates will create for us
 */
case class AuthorDefinedTemplateMetadata(
  name: String, // Url/CLI-friendly name of the template
  title: String, // Verbose and fun name of the template, used in GUI.
  description: String, // A long-winded description about what this template does.
  authorName: Option[String], // author's name
  authorLink: Option[String], // link to go to when clicking on author's name
  authorLogo: Option[String], // logo URI for author
  authorBio: Option[String], // some text describing the author
  authorTwitter: Option[String], // author twitter handle
  tags: Seq[String], // A set of folksonomy tags describing what's in this template, used for searching.
  templateTemplate: Boolean, // this template is for making another template, so props/tutorial should be cloned
  sourceLink: Option[String]) {

  // we handle null because we assume untrusted fields
  private def cleanup(s: String): String = {
    if (s eq null)
      s
    else
      s.trim().replace("\r\n", "\n").replace("\r", "\n")
  }

  private def cleanupTwitter(s: String): String = {
    val trimmed = cleanup(s)
    if (trimmed.startsWith("@"))
      trimmed.substring(1)
    else
      trimmed
  }

  def cleanup(): AuthorDefinedTemplateMetadata = {
    copy(name = cleanup(name), title = cleanup(title), description = cleanup(description),
      authorName = authorName.map(cleanup(_)), authorLink = authorLink.map(cleanup(_)),
      authorLogo = authorLogo.map(cleanup(_)), authorBio = authorBio.map(cleanup(_)),
      authorTwitter = authorTwitter.map(cleanupTwitter(_)),
      tags = tags.map(cleanup(_)), sourceLink = sourceLink.map(cleanup(_)))
  }

  // this should assume the fields in the metadata are completely untrusted
  // (possibly even null). The goal is to return ALL the errors.
  def validate(): ProcessResult[AuthorDefinedTemplateMetadata] = {
    def noControlChars(s: String, fieldName: String): Seq[String] = {
      val found = s.filter(Character.isISOControl(_))
      if (found.isEmpty)
        Nil
      else
        Seq(s"$fieldName contains invalid control characters (maybe a newline)")
    }
    def noControlCharsExceptLineBreak(s: String, fieldName: String): Seq[String] = {
      val found = s.filter(Character.isISOControl(_)).filter(c => c != '\n' && c != '\r')
      if (found.isEmpty)
        Nil
      else
        Seq(s"$fieldName contains invalid control characters")
    }
    def notTooLong(s: String, fieldName: String, limit: Int): Seq[String] = {
      if (s.length() < limit)
        Nil
      else
        Seq(s"$fieldName is too long (limit $limit chars, ${s.length - limit} over the limit)")
    }
    def notTooLongLine(s: String, fieldName: String): Seq[String] =
      notTooLong(s, fieldName, 120)
    def notTooLongParagraph(s: String, fieldName: String): Seq[String] =
      notTooLong(s, fieldName, 1024)
    def nonEmpty(s: String, fieldName: String): Seq[String] =
      if (s.isEmpty) Seq(s"$fieldName is empty") else Nil
    def validLink(s: String, fieldName: String): Seq[String] = {
      try {
        new java.net.URI(s)
        Nil
      } catch {
        case e: URISyntaxException =>
          Seq(s"$fieldName not a valid URI: ${e.getMessage}")
      }
    }
    def validTag(s: String, fieldName: String): Seq[String] = {
      val valid = s.filter(c => Character.isLetterOrDigit(c) || c == '-')
      if (valid != s)
        Seq(s"$fieldName must contain tags with only letters, digits, and hyphen (found '$s')")
      else
        Nil
    }
    def validUrlFriendly(s: String, fieldName: String): Seq[String] = {
      val escaped = URLEncoder.encode(s, "UTF-8")
      if (escaped != s)
        Seq(s"'$fieldName' should not require escaping to be in a URL, escaping now is '$s' => '$escaped'")
      else
        Nil
    }
    def noneEmpty(ss: Seq[String], fieldName: String): Seq[String] = {
      ss.filter(s => (s ne null) && s.isEmpty).headOption.map(_ => s"an item in '$fieldName' is an empty string").toSeq
    }
    def make(validators: (String, String) => Seq[String]*): (String, String) => Seq[String] = { (s, fieldName) =>
      // null is a special case since all the other checks would throw if we hit null
      if (s eq null)
        Seq(s"Missing field '$fieldName'")
      else
        validators flatMap { v =>
          v(s, fieldName)
        }
    }
    val oneLineCheck = make(nonEmpty, noControlChars, notTooLongLine)
    val paragraphCheck = make(nonEmpty, noControlCharsExceptLineBreak, notTooLongParagraph)
    val linkCheck = make(paragraphCheck, validLink)
    val tagCheck = make(notTooLong(_, _, 50), validTag)
    val nameCheck = make(oneLineCheck, validUrlFriendly)

    val errors = nameCheck(name, "name") ++ oneLineCheck(title, "title") ++
      paragraphCheck(description, "description") ++ authorLink.map(linkCheck(_, "authorLink")).getOrElse(Nil) ++
      authorName.map(oneLineCheck(_, "authorName")).getOrElse(Nil) ++
      authorLogo.map(linkCheck(_, "authorLogo")).getOrElse(Nil) ++
      authorBio.map(paragraphCheck(_, "authorBio")).getOrElse(Nil) ++
      authorTwitter.map(oneLineCheck(_, "authorTwitter")).getOrElse(Nil) ++
      tags.flatMap(tagCheck(_, "tags")) ++ noneEmpty(tags, "tags") ++
      sourceLink.map(linkCheck(_, "sourceLink")).getOrElse(Nil)

    if (errors.isEmpty)
      ProcessSuccess(this)
    else
      ProcessFailure(errors.map(ProcessError(_)))
  }
}

object AuthorDefinedTemplateMetadata {

}

/**
 * This represents metadata information stored in the local template repository.  This includes all
 * static, searchable and identifying information for a template.
 */
case class IndexStoredTemplateMetadata(
  id: String,
  name: String, // Url/CLI-friendly name of the template
  title: String, // Verbose and fun name of the template, used in GUI.
  description: String, // A long-winded description about what this template does.
  authorName: String, // author's name
  authorLink: String, // link to go to when clicking on author's name
  authorLogo: Option[String], // author's logo URI
  authorBio: Option[String], // long description of author
  authorTwitter: Option[String], // twitter handle of author
  tags: Seq[String], // A set of folksonomy tags describing what's in this template, used for searching.
  timeStamp: Long, // last publish time in SECONDS
  creationTime: Long, // earliest publish time in SECONDS
  featured: Boolean, // Display on the home page.
  usageCount: Option[Long], // Usage counts pulled from website.
  templateTemplate: Boolean, // is it a meta-template for making templates
  sourceLink: String, // link to the source code online
  category: String) {

}

object IndexStoredTemplateMetadata {
  // calling this method 'apply' mangles Json.writes/Json.reads due to a Play bug
  // (it's kind of a lame method anyway, we might want to get rid of it)
  def fromAuthorDefined(id: String, userConfig: AuthorDefinedTemplateMetadata, timeStamp: Long, creationTime: Long,
    featured: Boolean, usageCount: Option[Long], fallbackAuthorName: String, fallbackAuthorLink: String,
    fallbackSourceLink: String, category: String): IndexStoredTemplateMetadata = {
    IndexStoredTemplateMetadata(id = id, name = userConfig.name, title = userConfig.title, description = userConfig.description,
      authorName = userConfig.authorName.getOrElse(fallbackAuthorName),
      authorLink = userConfig.authorLink.getOrElse(fallbackAuthorLink),
      authorLogo = userConfig.authorLogo, authorBio = userConfig.authorBio,
      authorTwitter = userConfig.authorTwitter,
      tags = userConfig.tags, timeStamp = timeStamp, creationTime = creationTime,
      featured = featured, usageCount = usageCount,
      templateTemplate = userConfig.templateTemplate, sourceLink = userConfig.sourceLink.getOrElse(fallbackSourceLink),
      category = category)
  }
}

/**
 * This represents the TempalteMetadata as returned by a local repostiory and useful for the GUI.
 */
case class TemplateMetadata(
  persistentConfig: IndexStoredTemplateMetadata,
  locallyCached: Boolean // Denotes whether or not the template has been fully downloaded, or if only the metadata is in the cache.
  ) {
  // TODO - update equality/hashcode to be based on *JUST* the  ID
  def id = persistentConfig.id
  def name = persistentConfig.name
  def title = persistentConfig.title
  def description = persistentConfig.description
  def tags = persistentConfig.tags
  def timeStamp = persistentConfig.timeStamp
  def featured = persistentConfig.featured
  def usageCount = persistentConfig.usageCount
  def templateTemplate = persistentConfig.templateTemplate
  def sourceLink = persistentConfig.sourceLink
}

object TemplateMetadata {
  object Category {
    val UNKNOWN = "unknown"
    val COMPANY = "company"
    val INDIVIDUAL = "individual"
    val TYPESAFE = "typesafe"
    val PARTNER = "partner"
  }

  // creation time we use for templates when we didn't record
  // a creation time. This is April 29th 2014 which is when we added
  // creation time.
  val LEGACY_CREATION_TIME = 1398787148L
}

// things that we can do to an index via the REST API,
// this trait is shared by the client API and the
// server-side backend behind that API to keep those
// two things in sync.
trait IndexRestOps {
  /**
   * Finds a template by id.  Returns None if nothing is found.
   *  BLOWS CHUNKS on error.
   */
  def template(id: String)(implicit ec: ExecutionContext): Future[Option[IndexStoredTemplateMetadata]]
  /**
   * Searchs for a template matching the query string, and returns all results.
   *  There may be a ton of results.
   *  SPEWS ON ERROR.
   */
  def search(query: String, max: Int = 0)(implicit ec: ExecutionContext): Future[Iterable[IndexStoredTemplateMetadata]]
  /** Returns *ALL* metadata in this repository. */
  def metadata(implicit ec: ExecutionContext): Future[Iterable[IndexStoredTemplateMetadata]]
  /** Returns all metadata with the flag of "featured" set to "true" */
  def featured(implicit ec: ExecutionContext): Future[Iterable[IndexStoredTemplateMetadata]]
  /**
   * Return the maximum number of templates in this index.
   *  Note: Most likely this is the exact number of templates in the index,
   *  but there is no guarantee.
   */
  def maxTemplates(implicit ec: ExecutionContext): Future[Long]
}
