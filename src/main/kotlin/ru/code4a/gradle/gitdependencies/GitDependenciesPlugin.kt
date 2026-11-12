package ru.code4a.gradle.gitdependencies

import org.gradle.api.Plugin
import org.gradle.api.initialization.Settings

/**
 * Settings plugin that lets a build include the sources of a git repository at a tag.
 *
 * It registers [GitDependenciesExtension] as `gitDependencies`. A settings script calls
 * [checkoutGitDependency] and passes the returned directory to `includeBuild`.
 */
class GitDependenciesPlugin : Plugin<Settings> {
  override fun apply(settings: Settings) {
    val gitDependencies =
      settings.extensions.create(GitDependenciesExtension.NAME, GitDependenciesExtension::class.java, settings)

    // Cleanup has to know every checkout of the build. settingsEvaluated runs after the whole
    // settings script, so the checkouts count wherever the script requests them.
    settings.gradle.settingsEvaluated { gitDependencies.deleteUnusedCheckouts() }
  }
}
