package activator
package cache

import sbt.IO
import java.io.File
/**
 * This class is able to read a cache properties file and
 *  give us the information stored within it, as well as save new info...
 *
 *  NOTE - This is not threadsafe or anything.  It should probably be hidden behind an actor.
 *
 *  TODO - Auto-Save + Auto-reload on file changes?
 */
class CacheProperties(location: java.io.File) {
  val props = {
    if (!location.exists) IO.touch(location)
    IO.readProperties(location)
  }

  def cacheIndexHash = props.getProperty(Constants.CACHE_HASH_PROPERTY)
  def cacheIndexHash_=(newId: String) = {
    props.setProperty(Constants.CACHE_HASH_PROPERTY, newId)
  }

  def cacheIndexBinaryMajorVersion =
    // default to 0 because the original binary version was 0 but we didn't write it to the properties file
    // at first
    Option(props.getProperty(Constants.CACHE_INDEX_MAJOR_VERSION_PROPERTY)) map (_.toInt) getOrElse 0
  def cacheIndexBinaryMajorVersion_=(version: Int) =
    props.setProperty(Constants.CACHE_INDEX_MAJOR_VERSION_PROPERTY, version.toString)

  def cacheIndexBinaryIncrementVersion =
    props.getProperty(Constants.CACHE_INDEX_INCREMENT_VERSION_PROPERTY).toInt
  def cacheIndexBinaryIncrementVersion_=(version: Int) =
    props.setProperty(Constants.CACHE_INDEX_INCREMENT_VERSION_PROPERTY, version.toString)

  // TODO - If we don't have a property here, we sohuld assume an index of 0, I think.
  def cacheIndexSerial: Long = Option(props.getProperty(Constants.CACHE_SERIAL_PROPERTY)) map (_.toLong) getOrElse 0L
  def cacheIndexSerial_=(newSerial: Long) = props.setProperty(Constants.CACHE_SERIAL_PROPERTY, newSerial.toString)

  // TODO - Binary compatibility version?

  def reload(): Unit = {
    props.clear()
    IO.readProperties(location)
  }
  def save(msg: String = "Automatically updated properties."): Unit =
    IO.writeProperties(location, props, msg)
}

object CacheProperties {
  def default(cacheDir: java.io.File) = new CacheProperties(new java.io.File(cacheDir, Constants.CACHE_PROPS_FILENAME))

  // TODO - Does this belong here?
  def write(file: File, serial: Long, hash: String): ProcessResult[File] =
    Validating.withMsg("Unable to create new cache properties.") {
      val props = new CacheProperties(file)
      props.cacheIndexBinaryMajorVersion = Constants.INDEX_BINARY_MAJOR_VERSION
      props.cacheIndexBinaryIncrementVersion = Constants.INDEX_BINARY_INCREMENT_VERSION
      props.cacheIndexHash = hash
      props.cacheIndexSerial = serial
      props.save(s"Update index to serial serial")
      file
    }
}
