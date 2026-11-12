package ru.code4a.gradle.gitdependencies

import org.gradle.api.file.DirectoryProperty
import org.gradle.api.initialization.Settings
import org.gradle.api.logging.Logging
import org.gradle.api.model.ObjectFactory
import java.io.File
import javax.inject.Inject

/**
 * The `gitDependencies` extension of a settings script.
 *
 * Git dependencies are built from sources. The system git clones a repository by tag, so the
 * credential helpers and the SSH setup of the machine and of CI apply; the `sourceControl`
 * support of Gradle clones with the bundled JGit, which does not use the credential helpers of git.
 */
abstract class GitDependenciesExtension @Inject constructor(
  private val settings: Settings,
  objects: ObjectFactory,
) {
  /**
   * Directory that holds the clones, one per repository and tag: `<directory>/<repository>/<tag>`.
   *
   * The default is `.gradle/dependencies` in the directory of the settings file. A build deletes
   * unused clones only from this default directory: another directory may hold the clones of other
   * builds, for example when an included build points to the directory of the build that includes it.
   *
   * Set it before the first checkout: the first checkout makes the value final.
   */
  abstract val directory: DirectoryProperty

  private val defaultDirectory = settings.settingsDir.resolve(DEFAULT_DIRECTORY)

  // Checkouts that the build has requested. Each checkout registers itself, so a new git dependency
  // counts without a separate list, and cleanup deletes everything else from the directory.
  private val checkouts = LinkedHashSet<File>()

  init {
    directory.convention(objects.directoryProperty().fileValue(defaultDirectory))
    // The checkouts of a build and its cleanup have to agree on the directory.
    directory.finalizeValueOnRead()
  }

  /**
   * Clones [repository] at [tag] unless the clone already exists and returns the directory of the clone,
   * `<directory>/<name of the repository>/<tag>`.
   *
   * It does the same as [checkoutGitDependency] and needs no import.
   */
  fun checkout(repository: String, tag: String): File {
    require(repository.isNotBlank()) { "The repository of a git dependency must not be blank" }
    require(tag.isNotBlank()) { "The tag of the git dependency $repository must not be blank" }

    val repositoryName = repository.substringAfterLast('/').removeSuffix(".git")
    val checkout = directory.get().asFile.resolve(repositoryName).resolve(tag)
    settings.providers
      .of(GitCheckoutValueSource::class.java) {
        parameters.repository.set(repository)
        parameters.tag.set(tag)
        parameters.checkoutDirectory.set(checkout.path)
      }.get()
    checkouts += checkout
    return checkout
  }

  internal fun deleteUnusedCheckouts() {
    val directory = directory.get().asFile
    when {
      // The directory of an included build may be the directory of the build that includes it,
      // and the included build does not know the checkouts of that build.
      settings.gradle.parent != null ->
        logger.info("Git dependencies cleanup is skipped: {} is not the root build", settings.settingsDir)

      // A directory other than the default one may be shared with builds whose checkouts this
      // build does not know, and it may hold files that are not clones at all.
      directory.canonicalFile != defaultDirectory.canonicalFile ->
        logger.info("Git dependencies cleanup is skipped: {} is not the default directory", directory)

      else ->
        settings.providers
          .of(UnusedCheckoutsCleanupValueSource::class.java) {
            parameters.directory.set(directory.path)
            parameters.usedCheckouts.set(checkouts.map { it.path })
            parameters.rootDirectory.set(settings.settingsDir.path)
          }.get()
    }
  }

  internal companion object {
    const val NAME = "gitDependencies"

    /** Relative to the directory of the settings file. */
    const val DEFAULT_DIRECTORY = ".gradle/dependencies"

    private val logger = Logging.getLogger(GitDependenciesExtension::class.java)
  }
}
