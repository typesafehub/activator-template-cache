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
  // If you don't include the file type here (1 = reg, 4 = dir) then
  // unzipping on OS X using the GUI will drop the execute bits.
  // command line unzip on linux or OS X is fine.
  val executablePermsRegularFile = Integer.parseInt("100755", 8)
  val searchablePermsDirectory = Integer.parseInt("400755", 8)
  val notExecutablePermsRegularFile = Integer.parseInt("100644", 8)

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
        perm = if (file.isDirectory) searchablePermsDirectory
        else if (file.canExecute) executablePermsRegularFile
        else notExecutablePermsRegularFile
      } yield FileMapping(file, name, Some(perm))
    archive(mappings, outputZip)
  }
  /**
   * Replaces windows backslash file separator with a forward slash, this ensures the zip file entry is correct for
   * any system it is extracted on.
   * @param path  The path of the file in the zip file
   */
  private def normalizePath(path: String) = {
    val sep = java.io.File.separatorChar
    if (sep == '/')
      path
    else
      path.replace(sep, '/')
  }

  private def archive(sources: Seq[FileMapping], outputFile: File): Unit = {
    if (outputFile.isDirectory) sys.error("Specified output file " + outputFile + " is a directory.")
    else {
      val outputDir = outputFile.getParentFile
      outputDir.mkdirs
      withZipOutput(outputFile) { output =>
        for (FileMapping(file, name, mode) <- sources; if !file.isDirectory) {
          val entry = new ZipArchiveEntry(file, normalizePath(name))
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
