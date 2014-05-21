/**
 * Copyright (C) 2013 Typesafe <http://typesafe.com/>
 */
package activator
package cache

import sbt.{ PathFinder, IO }
import java.io.File
// This class contains methods that are responsible for pulling
// local caches out of our local zip file.
// We place it in its own object to denote the terribleness of the
// hackery used.
object ZipInstallHelper {
  // This logic is responsible for copying the local template cache (if available)
  // from the distribution exploded-zip directory into the user's local
  // cache.
  def copyLocalCacheIfNeeded(cacheDir: File, localStarterRepo: File): Unit = {
    // Ensure template cache exists.
    IO.createDirectory(cacheDir)
    // TODO - use SBT IO library when it's on scala 2.10
    if (localStarterRepo.isDirectory) {
      // Now loop over all the files in this repo and copy them into the local cache.
      for {
        file <- PathFinder(localStarterRepo).***.get
        relative <- IO.relativize(localStarterRepo, file)
        if !relative.isEmpty
        to = new java.io.File(cacheDir, relative)
        if !to.exists
      } if (file.isDirectory) IO.createDirectory(to)
      else IO.copyFile(file, to)
    }
  }
}
