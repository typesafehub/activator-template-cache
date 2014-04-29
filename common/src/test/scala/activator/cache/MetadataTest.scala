/**
 * Copyright (C) 2013 Typesafe <http://typesafe.com/>
 */
package activator.cache

import org.junit.Assert._
import org.junit._
import activator._

class MetadataTest {
  @Test
  def cleanupAuthorDefined(): Unit = {
    val cleaned = AuthorDefinedTemplateMetadata(name = "foo", title = "bar", description = "baz\nblah",
      authorName = Some("blah"), authorLink = Some("http://example.com/"), tags = Seq("a", "b"), templateTemplate = false,
      sourceLink = Some("http://example.com/source"), authorLogo = Some("http://example.com/bar.png"),
      authorBio = Some("blah blah blah"), authorTwitter = Some("tweets"))
    val dirty = AuthorDefinedTemplateMetadata(name = cleaned.name + " ",
      title = "\n" + cleaned.title,
      description = cleaned.description.flatMap {
        case '\n' => "\r\n"
        case c => c.toString
      },
      authorName = cleaned.authorName.map(_ + " "),
      authorLink = cleaned.authorLink.map(" " + _),
      tags = cleaned.tags.map(" " + _), templateTemplate = cleaned.templateTemplate,
      sourceLink = cleaned.sourceLink.map(" " + _),
      authorLogo = cleaned.authorLogo.map(" " + _ + " "),
      authorBio = cleaned.authorBio.map(" " + _ + " "),
      authorTwitter = cleaned.authorTwitter.map(" @" + _))
    assertEquals(cleaned, dirty.cleanup())
  }

  @Test
  def validateAllNulls(): Unit = {
    // Some(null) and Seq(null) are just twisted and not really expected,
    // but the validate routine handles them anyway
    val broken = AuthorDefinedTemplateMetadata(name = null, title = null, description = null,
      authorName = Some(null), authorLink = Some(null),
      authorLogo = Some(null), authorBio = Some(null), authorTwitter = Some(null),
      tags = Seq(null), templateTemplate = false,
      sourceLink = Some(null))
    val result = broken.validate()
    assertEquals(
      ProcessFailure(Seq(ProcessError("Missing field 'name'", None), ProcessError("Missing field 'title'", None),
        ProcessError("Missing field 'description'", None), ProcessError("Missing field 'authorLink'", None),
        ProcessError("Missing field 'authorName'", None), ProcessError("Missing field 'authorLogo'", None),
        ProcessError("Missing field 'authorBio'", None), ProcessError("Missing field 'authorTwitter'", None),
        ProcessError("Missing field 'tags'", None),
        ProcessError("Missing field 'sourceLink'", None))),
      result)
  }

  @Test
  def validateVariousProblems(): Unit = {
    val broken = AuthorDefinedTemplateMetadata(name = "My name has a newline\nin it",
      title = (1 to 200).map(_.toString).mkString, // too long
      description = "   ", // just whitespace
      authorName = Some(""), // empty string
      authorLink = Some("a^%"), // not a valid link
      authorLogo = Some("b^%"), // not a valid link
      authorBio = Some(""), // empty string
      authorTwitter = Some(""), // empty string
      tags = Seq("a b", "^%@#", "foo_bar", ""), // invalid stuff in tags
      templateTemplate = false,
      sourceLink = Some("$@"))
    val result = broken.cleanup().validate()
    assertEquals(
      ProcessFailure(Seq(ProcessError("name contains invalid control characters (maybe a newline)", None),
        ProcessError("'name' should not require escaping to be in a URL, escaping now is 'My name has a newline\nin it' => 'My+name+has+a+newline%0Ain+it'", None),
        ProcessError("title is too long (limit 120 chars, 372 over the limit)", None),
        ProcessError("description is empty", None),
        ProcessError("authorLink not a valid URI: Illegal character in path at index 1: a^%", None),
        ProcessError("authorName is empty", None),
        ProcessError("authorLogo not a valid URI: Illegal character in path at index 1: b^%", None),
        ProcessError("authorBio is empty", None),
        ProcessError("authorTwitter is empty", None),
        ProcessError("tags must contain tags with only letters, digits, and hyphen (found 'a b')", None),
        ProcessError("tags must contain tags with only letters, digits, and hyphen (found '^%@#')", None),
        ProcessError("tags must contain tags with only letters, digits, and hyphen (found 'foo_bar')", None),
        ProcessError("an item in 'tags' is an empty string", None))),
      result)
  }

  @Test
  def validateUrlUnfriendlyNames(): Unit = {
    val base = AuthorDefinedTemplateMetadata(name = "foo", title = "bar", description = "baz\nblah",
      authorName = Some("blah"), authorLink = Some("http://example.com/"),
      authorLogo = Some("http://example.com/bar"), authorBio = Some("blah blah"),
      authorTwitter = Some("tweets"),
      tags = Seq("a", "b"), templateTemplate = false,
      sourceLink = Some("http://example.com/source/"))
    assertEquals("base is valid", ProcessSuccess(base), base.validate())

    for (c <- Seq('/', ' ', '%')) {
      val invalid = base.copy(name = (base.name + c))
      val errors = invalid.validate() match {
        case ProcessFailure(errors) => errors.map(_.msg)
        case whatever =>
          throw new AssertionError(s"expecting failure got $whatever")
      }
      assertTrue("got an error on invalid name", errors.nonEmpty)
      assertTrue("it was an url-invalid error", errors.head.contains("URL"))
    }
  }

  @Test
  def validateBadTwitter(): Unit = {
    val base = AuthorDefinedTemplateMetadata(name = "foo", title = "bar", description = "baz\nblah",
      authorName = Some("blah"), authorLink = Some("http://example.com/"),
      authorLogo = Some("http://example.com/bar"), authorBio = Some("blah blah"),
      authorTwitter = Some("tweets"),
      tags = Seq("a", "b"), templateTemplate = false,
      sourceLink = Some("http://example.com/source/"))
    assertEquals("base is valid", ProcessSuccess(base), base.validate())

    for (c <- Seq('/', ' ', '%')) {
      val invalid = base.copy(authorTwitter = base.authorTwitter.map(_ + c))
      val errors = invalid.validate() match {
        case ProcessFailure(errors) => errors.map(_.msg)
        case whatever =>
          throw new AssertionError(s"expecting failure got $whatever")
      }
      assertTrue("got an error on invalid twitter", errors.nonEmpty)
      assertTrue("it was an invalid-chars error", errors.head.contains("underscore"))
    }
  }

  @Test
  def validateTwitterTooLong(): Unit = {
    val base = AuthorDefinedTemplateMetadata(name = "foo", title = "bar", description = "baz\nblah",
      authorName = Some("blah"), authorLink = Some("http://example.com/"),
      authorLogo = Some("http://example.com/bar"), authorBio = Some("blah blah"),
      authorTwitter = Some("twitters"),
      tags = Seq("a", "b"), templateTemplate = false,
      sourceLink = Some("http://example.com/source/"))
    assertEquals("base is valid", ProcessSuccess(base), base.validate())

    val invalid = base.copy(authorTwitter = Some("mytwitterhandleistoooooloooong"))
    val errors = invalid.validate() match {
      case ProcessFailure(errors) => errors.map(_.msg)
      case whatever =>
        throw new AssertionError(s"expecting failure got $whatever")
    }
    assertTrue("got an error on invalid twitter", errors.nonEmpty)
    assertTrue("it was a twitter-related error", errors.head.contains("authorTwitter"))
    assertTrue("it was a too-long error", errors.head.contains("too long"))
  }

}
