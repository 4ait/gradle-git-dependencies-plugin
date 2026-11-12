package ru.code4a.gradle.gitdependencies

import org.gradle.api.GradleException
import org.gradle.api.logging.Logging
import org.gradle.api.provider.Property
import org.gradle.api.provider.ValueSource
import org.gradle.api.provider.ValueSourceParameters
import java.io.File
import java.io.IOException
import java.nio.file.Files

/**
 * Makes sure that the clone of a repository at a tag exists and returns its path.
 *
 * Git runs while the settings script is evaluated. With the configuration cache Gradle allows
 * an external process at configuration time only inside a [ValueSource], and it calls [obtain]
 * again before it reuses a cache entry. A clone that was deleted after the entry had been stored
 * is cloned again there, and the unchanged path keeps the entry valid.
 */
internal abstract class GitCheckoutValueSource : ValueSource<String, GitCheckoutValueSource.Parameters> {
  interface Parameters : ValueSourceParameters {
    val repository: Property<String>
    val tag: Property<String>
    val checkoutDirectory: Property<String>
  }

  override fun obtain(): String {
    val checkout = File(parameters.checkoutDirectory.get())
    if (!checkout.resolve(CLONED_MARK_FILE_NAME).isFile) {
      cloneGitDependency(parameters.repository.get(), parameters.tag.get(), checkout)
    }
    return checkout.path
  }

  // The clone goes to a temporary directory that is renamed afterwards: a parallel build never
  // sees a half-downloaded repository.
  private fun cloneGitDependency(repository: String, tag: String, checkout: File) {
    val temporary = checkout.temporarySibling()
    deleteRecursivelyWithoutFollowingSymlinks(temporary)
    temporary.parentFile.mkdirs()

    logger.info("Cloning {} at tag {} into {}", repository, tag, checkout)
    runGitClone(repository, tag, temporary)
    deleteRecursivelyWithoutFollowingSymlinks(temporary.resolve(".git"))
    Files.writeString(temporary.resolve(CLONED_MARK_FILE_NAME).toPath(), "cloned")

    if (!temporary.renameTo(checkout)) {
      deleteRecursivelyWithoutFollowingSymlinks(temporary)
      // The rename fails when a parallel build has put the same clone in place first.
      if (!checkout.resolve(CLONED_MARK_FILE_NAME).isFile) {
        throw GradleException("Could not move the clone of $repository at tag $tag to $checkout")
      }
    }
  }

  private fun runGitClone(repository: String, tag: String, target: File) {
    val command =
      listOf(
        "git", "-c", "advice.detachedHead=false", "clone", "--quiet", "--depth", "1", "--branch", tag,
        "--", repository, target.absolutePath,
      )
    val git =
      try {
        ProcessBuilder(command).redirectErrorStream(true).start()
      } catch (failure: IOException) {
        throw GradleException(
          "Could not start git to clone $repository at tag $tag: the plugin runs the git executable from the PATH",
          failure,
        )
      }
    // Git gets no input: a question on the standard input has to fail instead of blocking the build.
    git.outputStream.close()
    val output = git.inputStream.bufferedReader().use { it.readText() }
    if (git.waitFor() != 0) {
      deleteRecursivelyWithoutFollowingSymlinks(target)
      throw GradleException("Could not clone $repository at tag $tag:\n${output.trim()}")
    }
  }

  private companion object {
    val logger = Logging.getLogger(GitCheckoutValueSource::class.java)
  }
}
