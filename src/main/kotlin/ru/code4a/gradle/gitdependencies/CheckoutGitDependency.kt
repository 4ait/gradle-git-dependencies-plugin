package ru.code4a.gradle.gitdependencies

import org.gradle.api.initialization.Settings
import java.io.File

/**
 * Clones [repository] at [tag] unless the clone already exists and returns the directory of the clone.
 *
 * Call it from a settings script that applies the `ru.code4a.git-dependencies` plugin:
 *
 * ```kotlin
 * import ru.code4a.gradle.gitdependencies.checkoutGitDependency
 *
 * includeBuild(checkoutGitDependency("https://github.com/acme/library.git", "v1.2.0"))
 * ```
 *
 * @see GitDependenciesExtension.checkout
 */
fun Settings.checkoutGitDependency(repository: String, tag: String): File =
  extensions.getByType(GitDependenciesExtension::class.java).checkout(repository, tag)
