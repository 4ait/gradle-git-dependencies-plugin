package ru.code4a.gradle.gitdependencies

import org.gradle.api.logging.Logging
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.ValueSource
import org.gradle.api.provider.ValueSourceParameters
import java.io.File
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.StandardCopyOption

/**
 * Deletes from the directory of clones everything that the build has not requested: the clones
 * of former tags and of repositories that the build no longer includes.
 *
 * It is a [ValueSource] although its value never changes. Gradle records the files that build
 * logic looks at during configuration as inputs of the configuration cache, so the directory
 * listings of cleanup would invalidate the cache entry after every deletion and whenever a parallel
 * build creates a temporary directory. File access inside [obtain] is not recorded.
 */
internal abstract class UnusedCheckoutsCleanupValueSource :
  ValueSource<Boolean, UnusedCheckoutsCleanupValueSource.Parameters> {
  interface Parameters : ValueSourceParameters {
    val directory: Property<String>
    val usedCheckouts: ListProperty<String>

    /** Deleted entries are reported relative to this directory. */
    val rootDirectory: Property<String>
  }

  override fun obtain(): Boolean {
    // A canonical path equals the paths that cleanup reads from the directory even when the case of
    // a repository name or of a tag differs from the directory on a case-insensitive file system.
    val usedCheckouts = parameters.usedCheckouts.get().mapTo(HashSet()) { File(it).canonicalFile }
    deleteUnusedEntries(File(parameters.directory.get()), usedCheckouts)
    return true
  }

  private fun deleteUnusedEntries(directory: File, usedCheckouts: Set<File>) {
    for (entry in directory.listFiles().orEmpty()) {
      val canonicalEntry = entry.canonicalFile
      when {
        // A clone of the current build.
        canonicalEntry in usedCheckouts -> Unit
        // The directory of a repository or a part of a tag with "/": a clone of the current build lies deeper.
        usedCheckouts.any { it.startsWith(canonicalEntry) } -> deleteUnusedEntries(entry, usedCheckouts)
        // A parallel build is still downloading the clone or deleting the directory.
        isTemporaryOfAnotherLiveProcess(entry) -> Unit
        else -> deleteEntry(entry)
      }
    }
  }

  // The directory is renamed atomically and only then deleted: if the deletion is interrupted, no
  // partial clone stays at the former path, where a checkout would take it for a complete one.
  // The next cleanup deletes the leftover.
  private fun deleteEntry(entry: File) {
    val deleting = entry.temporarySibling()
    val displayPath = entry.relativeToOrSelf(File(parameters.rootDirectory.get()))
    try {
      deleteRecursivelyWithoutFollowingSymlinks(deleting)
      Files.move(entry.toPath(), deleting.toPath(), StandardCopyOption.ATOMIC_MOVE)
      deleteRecursivelyWithoutFollowingSymlinks(deleting)
      logger.lifecycle("Git dependencies cleanup: deleted {}", displayPath)
    } catch (alreadyDeleted: NoSuchFileException) {
      // A parallel build is deleting the directory.
    } catch (failure: Exception) {
      // Cleanup must not break the build: the next one deletes the leftover.
      logger.warn("Git dependencies cleanup: could not delete {}: {}", displayPath, failure.toString())
    }
  }

  private companion object {
    val logger = Logging.getLogger(UnusedCheckoutsCleanupValueSource::class.java)
  }
}
