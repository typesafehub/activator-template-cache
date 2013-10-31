package activator

import org.apache.commons.compress.archivers.zip._
import org.apache.commons.compress.compressors.{
  CompressorStreamFactory,
  CompressorOutputStream
}
import java.util.zip.Deflater
import org.apache.commons.compress.utils.IOUtils
import java.io.File

// this is copied from sbt-native-packager
object ZipHelper {
  case class FileMapping(file: File, name: String, unixMode: Option[Int] = None)

  // scala has no octal literals anymore so we do this weird thing
  val executablePerms = Integer.parseInt("755", 8)
  val notExecutablePerms = Integer.parseInt("644", 8)

  /**
   * Creates a zip file attempting to give files the appropriate unix permissions using Java 6 APIs.
   * @param sources   The files to include in the zip file.
   * @param outputZip The location of the output file.
   */
  def zip(sources: Traversable[(File, String)], outputZip: File): Unit = {
    val mappings =
      for {
        (file, name) <- sources.toSeq
        // TODO - Figure out if this is good enough....
        perm = if (file.isDirectory || file.canExecute) executablePerms else notExecutablePerms
      } yield FileMapping(file, name, Some(perm))
    archive(mappings, outputZip)
  }

  private def archive(sources: Seq[FileMapping], outputFile: File): Unit = {
    if (outputFile.isDirectory) sys.error("Specified output file " + outputFile + " is a directory.")
    else {
      val outputDir = outputFile.getParentFile
      outputDir.mkdirs
      withZipOutput(outputFile) { output =>
        for (FileMapping(file, name, mode) <- sources; if !file.isDirectory) {
          val entry = new ZipArchiveEntry(file, name)
          // Now check to see if we have permissions for this sucker.
          mode foreach (entry.setUnixMode)
          output putArchiveEntry entry
          // TODO - Write file into output?
          IOUtils.copy(new java.io.FileInputStream(file), output)
          output.closeArchiveEntry()
        }
      }
    }
  }

  private def withZipOutput(file: File)(f: ZipArchiveOutputStream => Unit): Unit = {
    val zipOut = new ZipArchiveOutputStream(file)
    zipOut setLevel Deflater.BEST_COMPRESSION
    try { f(zipOut) }
    finally {
      zipOut.close()
    }
  }
}
