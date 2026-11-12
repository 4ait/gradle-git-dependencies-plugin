package ru.code4a.gradle.gitdependencies

import java.io.File

/**
 * Git for the tests: it creates the repository that the builds under test clone, and it provides
 * the environment in which git ignores the configuration of the machine.
 */
internal class GitFixture(
  private val temporaryDirectory: File,
) {
  private val emptyGlobalConfiguration = temporaryDirectory.resolve("gitconfig").apply { writeText("") }

  val environment: Map<String, String> =
    mapOf(
      "GIT_CONFIG_GLOBAL" to emptyGlobalConfiguration.path,
      "GIT_CONFIG_NOSYSTEM" to "1",
      // The test directories lie inside the working tree of the plugin: git must never find that repository.
      "GIT_CEILING_DIRECTORIES" to temporaryDirectory.canonicalPath,
      "GIT_AUTHOR_NAME" to "Test",
      "GIT_AUTHOR_EMAIL" to "test@example.com",
      "GIT_COMMITTER_NAME" to "Test",
      "GIT_COMMITTER_EMAIL" to "test@example.com",
      // The tests read the messages of git.
      "LC_ALL" to "C",
    )

  /**
   * A repository with the Gradle build `com.example:<name>`. The first commit has the version 1.0
   * and the tags `v1` and `release/1.0`, the second one has the version 2.0 and the tags `v2` and
   * `release/2.0`.
   */
  fun createLibraryRepository(name: String): File {
    val repository = temporaryDirectory.resolve("remote/$name.git")
    repository.mkdirs()
    git(repository, "init", "--quiet", "--initial-branch=main")
    repository.resolve("settings.gradle.kts").writeText("rootProject.name = \"$name\"\n")

    for ((version, tags) in listOf("1.0" to listOf("v1", "release/1.0"), "2.0" to listOf("v2", "release/2.0"))) {
      repository.resolve("build.gradle.kts").writeText(
        """
        plugins {
          `java-library`
        }

        group = "com.example"
        version = "$version"
        """.trimIndent() + "\n",
      )
      git(repository, "add", "--all")
      git(repository, "commit", "--quiet", "--message", "Version $version")
      tags.forEach { git(repository, "tag", it) }
    }
    return repository
  }

  private fun git(directory: File, vararg arguments: String) {
    val process =
      ProcessBuilder(listOf("git", "-C", directory.path) + arguments)
        .redirectErrorStream(true)
        .apply { environment().putAll(environment) }
        .start()
    val output = process.inputStream.bufferedReader().use { it.readText() }
    check(process.waitFor() == 0) { "git ${arguments.joinToString(" ")} failed:\n$output" }
  }
}

/** The form of a local path that git clones over a transport: only then `--depth` applies. */
internal fun File.toGitUrl(): String = "file://" + toURI().rawPath.removeSuffix("/")
