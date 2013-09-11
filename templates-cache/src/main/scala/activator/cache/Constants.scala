package activator
package cache

object Constants {
  val METADATA_FILENAME = "activator.properties"
  val CACHE_PROPS_FILENAME = "cache.properties"
  val METADATA_INDEX_FILENAME = "index.db"
  val TUTORIAL_DIR = "tutorial"

  val CACHE_HASH_PROPERTY = "cache.hash"
  val CACHE_INDEX_MAJOR_VERSION_PROPERTY = "cache.binary.major.version"
  val CACHE_INDEX_INCREMENT_VERSION_PROPERTY = "cache.binary.increment.version"
  val CACHE_SERIAL_PROPERTY = "cache.serial"

  // Property name that goes in <local-project>/build.properties upon a clone.
  val TEMPLATE_UUID_PROPERTY_NAME = "template.uuid"

  // This number is included in repository filenames. i.e. it controls
  // where we look for and upload indexes.
  // If this is incremented, older versions of the software will not
  // see new indexes with the new version, they will only see old indexes.
  // We will only consume and generate new indexes with this new number.
  // This number does not necessarily change the index *format*, though.
  // We may bump this even when the format is unchanged if we want to
  // keep old clients from seeing new indexes, for example we are bumping
  // this in the sbt 0.12 -> 0.13 transition.
  // INDEX_BINARY_MAJOR_VERSION below represents a breaking change in *format*.
  val INDEX_REPOSITORY_GENERATION = "2"

  // this number is stored in the index itself and must MATCH EXACTLY
  // what the consumer of the index expects; that is, bumping it causes
  // new versions of the software to reject all previous indexes, and
  // causes previous versions of the software to reject all new indexes.
  // If you increment this you MUST also increment INDEX_REPOSITORY_GENERATION,
  // above, or we will put incompatible indexes where old clients will find
  // them.
  // If INDEX_REPOSITORY_GENERATION is incremented then in theory
  // mismatches in this version would never happen, but this is here
  // just as an extra check.
  // The first generation of indexes, by the way, did not store this
  // field, so if not present it should be assumed to be 0.
  val INDEX_BINARY_MAJOR_VERSION = 0

  // This number is stored in the index itself and indicates the MINIMUM
  // increment version we require. So incrementing this means that we will
  // consume only indexes generated with the new, higher number; but we will create
  // indexes that older versions can still consume. This number assumes that
  // all changes are backward-compatible (do not break old) but that new versions
  // require the latest format and cannot handle an older one. If new versions can
  // still use the old index format, there's no need to increment this.
  val INDEX_BINARY_INCREMENT_VERSION = 0
}
