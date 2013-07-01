package activator
package cache

object Constants {
  val METADATA_FILENAME = "activator.properties"
  val CACHE_PROPS_FILENAME = "cache.properties"
  val METADATA_INDEX_FILENAME = "index.db"
  val TUTORIAL_DIR = "tutorial"

  val CACHE_HASH_PROPERTY = "cache.hash"
  val CACHE_INDEX_INCREMENT_VERSION_PROPERTY = "cache.binary.increment.version"
  val CACHE_SERIAL_PROPERTY = "cache.serial"

  // Property name that goes in <local-project>/build.properties upon a clone.
  val TEMPLATE_UUID_PROPERTY_NAME = "template.uuid"

  // This number represents the current binary version of the Index database.
  // Any index published with this version should be readable by previous releases of the software,
  // i.e. only binary additions, no removals.
  val INDEX_BINARY_VERSION = "1"
  // This number represents the current number of additions to the Index database.
  // When resolving new repositories, we must ensure that the version we're downloaded
  // has its increment version > than our software's so that we can find all the features we require.
  val INDEX_BINARY_INCREMENT_VERSION = 0
}
