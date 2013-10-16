/**
 * Copyright (C) 2013 Typesafe <http://typesafe.com/>
 */
package activator
package hashing

import java.security.MessageDigest
import java.io.File

/** Typeclass that helps us decide how to hash things. */
trait Hash[T] {
  def apply(t: T): String
}
object Hash {
  /** The default hash used for files in our system, currently. */
  implicit val fileSha1Hasher: Hash[java.io.File] = files.sha1
  /** The default hash used for strings */
  implicit val stringSha1Hasher: Hash[String] = strings.sha1
}

/** Helper class to implement hashes via message digest. */
abstract class MessageDigestHasher[T](alg: String) extends Hash[T] {
  def apply(t: T): String = {
    val md = MessageDigest.getInstance(alg)
    updateDigest(t, md)
    digestToHexString(md.digest)
  }
  protected def updateDigest(t: T, md: MessageDigest): Unit
}

/** Helper to create file hashing digests. */
class FileDigestHasher(alg: String) extends MessageDigestHasher[File](alg) {
  protected def updateDigest(file: File, md: MessageDigest): Unit = {
    val in = new java.io.FileInputStream(file);
    val buffer = new Array[Byte](8192)
    try {
      def read(): Unit = in.read(buffer) match {
        case x if x <= 0 => ()
        case size => md.update(buffer, 0, size); read()
      }
      read()
    } finally in.close()
  }
}

class StringDigestHasher(alg: String) extends MessageDigestHasher[String](alg) {
  protected def updateDigest(s: String, md: MessageDigest): Unit = {
    val bytes = s.getBytes("UTF-8")
    md.update(bytes, 0, bytes.length)
  }
}
