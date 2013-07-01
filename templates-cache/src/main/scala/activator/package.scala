import sbt.IO
import java.io.File
import java.util.Random
import java.io.IOException
import java.io.BufferedWriter
import java.util.Properties
import java.nio.charset.Charset
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.MalformedInputException

package object activator {

  // java.util.Random is supposed to have locks making it threadsafe
  private val tmpFileRandom = new Random()

  implicit class RichIO(val io: IO.type) extends AnyVal {
    // create a tmp file alongside where destFileOrDir will be, then
    // pass tmp file to body, then rename into place after body completes.
    // if body throws an exception, try to delete the tmp file.
    def createViaTemporary[T](destFileOrDir: File)(body: File => T): T = {
      // the random is not really a security measure since we don't expect to be in a shared
      // dir like /tmp anyway, but more to prevent headaches if we get a stale one of these
      // that might be in the way of a future call
      val tmpFile = new File(destFileOrDir.getPath() + "_" + java.lang.Long.toHexString(tmpFileRandom.nextLong()) + ".tmp")
      try {
        val t = body(tmpFile)
        io.move(tmpFile, destFileOrDir)
        t
      } finally {
        // recursively delete tmpFile if it was created and didn't get moved
        if (tmpFile.exists)
          io.delete(tmpFile)
      }
    }

    // this is for use in tests. Creates a target/tmp
    // This is more realistic than testing in /tmp because it
    // is more likely to find bugs such as trying to move files
    // from tmpfs to a homedir, and it also keeps junk out of
    // /tmp that really has no reason to be in there.
    // TODO: future enhancement, somehow get from sbt the
    // target dir instead of just hardcoding that it's the
    // toplevel target dir where sbt is run from
    def withTestTempDirectory[T](action: File => T): T = {
      val target = new File("target")
      val targetTmp = new File(target, "tmp")
      io.createDirectory(targetTmp)
      // we never delete target/tmp ("clean" would get it)
      // but we do delete the per-call random name here
      val randomName = "test_" + java.lang.Long.toHexString(tmpFileRandom.nextLong())
      val dir = new File(targetTmp, randomName)
      io.createDirectory(dir)

      try action(dir)
      finally io.delete(dir) // recursive delete
    }

    // it would be more consistent to name this just "writer" but then
    // the existing method on IO always gets chosen and so this one isn't found
    def fileWriter[T](file: File, charset: Charset = io.utf8, append: Boolean = false)(f: BufferedWriter => T): T =
      sbt.Using.fileWriter(charset, append)(file) { f }

    // IO.write writes a properties file but in ISO-8859-1 always so use this instead
    def writeProperties(file: File, props: Properties, comments: String, charset: Charset = io.utf8): Unit =
      fileWriter(file, charset)(props.store(_, comments))

    // this specifies the encoding, and pre-validates it (because the
    // Reader seems to be "lenient" - probably uses CodingErrorAction.REPLACE)
    def readProperties(file: File, charset: Charset = io.utf8): Properties = {
      // first validate the encoding and give a clear error on failure
      val bytes = io.readBytes(file)
      try {
        charset.newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(java.nio.ByteBuffer.wrap(bytes))
      } catch {
        // the message for bad encoding is unclear otherwise; it's something like
        // "MalformedInputException: Input length = 1"
        // so fix that up...
        case e: CharacterCodingException =>
          throw new IOException(s"Properties file '$file' should be in ${charset.displayName()} encoding", e)
      }
      // second if the encoding is OK, load the file
      val props = new Properties
      io.reader(file, charset)(props.load)
      props
    }
  }
}
