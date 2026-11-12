package ru.code4a.gradle.gitdependencies

import java.io.File
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/**
 * A clone is complete when it holds this file: it is written after git has finished and before
 * the clone moves to its final path.
 */
internal const val CLONED_MARK_FILE_NAME = "gradle_dep_cloned_mark"

private val temporaryProcessId = Regex("""\.tmp-(\d+)$""")

/**
 * Temporary directory next to the path: a clone is downloaded into it, or a directory moves there
 * before it is deleted. The pid in the name tells the operation of a parallel build from the leftover
 * of an interrupted one.
 */
internal fun File.temporarySibling(): File = resolveSibling("$name.tmp-${ProcessHandle.current().pid()}")

/**
 * A temporary directory of the current process is the leftover of an interrupted earlier build
 * of the same daemon: the builds of one daemon run one after another.
 */
internal fun isTemporaryOfAnotherLiveProcess(entry: File): Boolean {
  val processId = temporaryProcessId.find(entry.name)?.groupValues?.get(1)?.toLongOrNull()
  return processId != null && processId != ProcessHandle.current().pid() && ProcessHandle.of(processId).isPresent
}

/**
 * Deletes a file or a directory tree. A symlink is deleted itself, its target stays: a symlink from
 * a clone to a directory outside would otherwise delete the files of somebody else.
 */
internal fun deleteRecursivelyWithoutFollowingSymlinks(file: File) {
  // Without FileVisitOption.FOLLOW_LINKS the walk reports a symlink to a directory as a file.
  Files.walkFileTree(
    file.toPath(),
    object : SimpleFileVisitor<Path>() {
      override fun visitFile(entry: Path, attributes: BasicFileAttributes): FileVisitResult {
        deleteEntry(entry)
        return FileVisitResult.CONTINUE
      }

      override fun visitFileFailed(entry: Path, failure: IOException): FileVisitResult {
        // Nothing to delete, or a parallel build has deleted the entry first.
        if (failure is NoSuchFileException) return FileVisitResult.CONTINUE
        throw failure
      }

      override fun postVisitDirectory(directory: Path, failure: IOException?): FileVisitResult {
        if (failure != null) throw failure
        deleteEntry(directory)
        return FileVisitResult.CONTINUE
      }
    },
  )
}

private fun deleteEntry(entry: Path) {
  try {
    Files.deleteIfExists(entry)
  } catch (denied: AccessDeniedException) {
    // Windows does not delete a read-only file, and git stores its objects read-only. A symlink
    // stays as it is: File.setWritable would change its target.
    if (Files.isSymbolicLink(entry) || !entry.toFile().setWritable(true)) throw denied
    Files.deleteIfExists(entry)
  }
}
