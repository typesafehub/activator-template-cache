/**
 * Copyright (C) 2013 Typesafe <http://typesafe.com/>
 */
package activator.storage

import scala.reflect.ClassTag

trait KeyValueWriter[-T] {
  def toMap(t: T): Map[String, Any]
}

trait KeyValueReader[+T] {
  def fromMap(key: String, m: Map[String, Any]): Option[T]
}

trait KeyValueMapper[T] extends KeyValueWriter[T] with KeyValueReader[T] {

}

object KeyValueMapper {

  implicit class HasGetAs(val o: Map[String, Any]) extends AnyVal {
    def getAs[A: ClassTag](key: String): Option[A] = {
      o.get(key) map CheckedCast[A]
    }
  }

  implicit object Identity extends KeyValueMapper[Map[String, Any]] {
    override def toMap(t: Map[String, Any]): Map[String, Any] = verifyJson(t)
    override def fromMap(key: String, m: Map[String, Any]): Option[Map[String, Any]] = Some(verifyJson(m))
  }

  def verifyJsonValue(v: Any): Any = {
    v match {
      case null =>
      case n: Number =>
      case b: Boolean =>
      case s: String =>
      case s: Seq[_] =>
        for (e <- s) {
          verifyJsonValue(e)
        }
      case o: Map[_, _] =>
        verifyJson(o)
      case whatever =>
        throw new RuntimeException(s"Non-JSON-allowable type ${whatever.getClass.getName} found in map that should conform to JSON schema, value $whatever")
    }
  }

  def verifyJson(m: Map[_, _]): Map[String, Any] = {
    for (entry <- m) {
      entry match {
        case (k: String, v) =>
          verifyJsonValue(v)
        case whatever =>
          throw new RuntimeException(s"Non-string key found in map that should conform to JSON schema, entry was: $whatever")
      }
    }
    m.asInstanceOf[Map[String, Any]]
  }
}

trait KeyExtractor[-T] {
  def getKey(t: T): String
}
