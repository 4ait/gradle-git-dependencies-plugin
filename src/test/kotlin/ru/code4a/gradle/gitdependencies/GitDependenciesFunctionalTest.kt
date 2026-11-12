package ru.code4a.gradle.gitdependencies

import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Builds that apply the plugin, run by Gradle TestKit.
 *
 * Every test starts with the repository `greeting-lib` of [GitFixture.createLibraryRepository] and
 * with the build `consumer`, which includes the clone of the tag from its `gradle.properties` and
 * depends on the library by coordinates. The scripts take everything that differs between tests from
 * `gradle.properties`: Gradle compiles a script with the same text once for all tests.
 */
internal abstract class GitDependenciesFunctionalTest(
  /** Gradle version that runs the builds; `null` is the version that builds the plugin. */
  private val gradleVersion: String?,
) {
  @TempDir
  lateinit var temporaryDirectory: File

  private lateinit var git: GitFixture
  private lateinit var libraryRepository: File
  private lateinit var consumer: File

  private val clones: File get() = consumer.resolve(".gradle/dependencies")

  @BeforeEach
  fun createLibraryRepositoryAndConsumer() {
    git = GitFixture(temporaryDirectory)
    libraryRepository = git.createLibraryRepository("greeting-lib")
    consumer = temporaryDirectory.resolve("consumer").apply { mkdirs() }
    consumer.resolve("settings.gradle.kts").writeText(SETTINGS_INCLUDING_THE_LIBRARY)
    consumer.resolve("build.gradle.kts").writeText(BUILD_PRINTING_THE_RUNTIME_CLASSPATH)
    writeGradleProperties(consumer, libraryTag = "v1")
  }

  @Test
  fun `clones the tag and includes the clone as a build`() {
    // Gradle names an included build after its directory, which is the tag.
    val result = build(":v1:jar")

    assertEquals(TaskOutcome.SUCCESS, result.task(":v1:jar")?.outcome)
    val clone = cloneOf("v1")
    assertTrue(clone.isCompleteClone())
    assertContains(clone.resolve("build.gradle.kts").readText(), "version = \"1.0\"")
    assertFalse(clone.resolve(".git").exists(), "the clone keeps no git repository")
    assertTrue(clone.resolve("build/libs/greeting-lib-1.0.jar").isFile)
  }

  @Test
  fun `substitutes a dependency on the coordinates of the library with the clone`() {
    val result = build("printRuntimeClasspath")

    assertContains(result.output, RUNTIME_CLASSPATH_OF_THE_CLONE_V1)
  }

  @Test
  fun `does not clone again when the clone exists`() {
    build("help")
    val cloneMark = cloneOf("v1").resolve(CLONED_MARK_FILE_NAME)
    val clonedAt = Files.getLastModifiedTime(cloneMark.toPath())
    // Another clone would fail without the repository.
    libraryRepository.deleteRecursively()

    val result = build("help", "--info")

    assertFalse("Cloning " in result.output, "the second build does not clone")
    assertEquals(clonedAt, Files.getLastModifiedTime(cloneMark.toPath()))
  }

  @Test
  fun `keeps the clones of two tags of one repository side by side`() {
    consumer.resolve("settings.gradle.kts").writeText(SETTINGS_CHECKING_OUT_TWO_TAGS)

    build("help")
    // Cleanup of the second build finds both clones in the directory.
    build("help")

    assertContains(cloneOf("v1").resolve("build.gradle.kts").readText(), "version = \"1.0\"")
    assertContains(cloneOf("v2").resolve("build.gradle.kts").readText(), "version = \"2.0\"")
    assertTrue(cloneOf("v1").isCompleteClone())
    assertTrue(cloneOf("v2").isCompleteClone())
  }

  @Test
  fun `deletes the clones that the build no longer uses and keeps the used ones`() {
    build("help")
    val cloneOfFormerRepository = clones.resolve("former-lib/v3").apply { mkdirs() }
    cloneOfFormerRepository.resolve(CLONED_MARK_FILE_NAME).writeText("cloned")
    writeGradleProperties(consumer, libraryTag = "v2")

    val result = build("help")

    assertTrue(cloneOf("v2").isCompleteClone())
    assertFalse(cloneOf("v1").exists())
    assertFalse(clones.resolve("former-lib").exists())
    assertContains(result.output, "Git dependencies cleanup: deleted .gradle/dependencies/greeting-lib/v1")
    assertContains(result.output, "Git dependencies cleanup: deleted .gradle/dependencies/former-lib")
    assertEquals(listOf("v2"), clones.resolve("greeting-lib").list()?.toList())
  }

  @Test
  fun `includes two repositories at the same tag as builds with their own names`() {
    val toolkitRepository = git.createLibraryRepository("toolkit-lib")
    consumer.resolve("settings.gradle.kts").writeText(SETTINGS_INCLUDING_TWO_NAMED_LIBRARIES)
    consumer.resolve("gradle.properties").appendText("toolkitRepository=${toolkitRepository.toGitUrl()}\n")

    // Without the names both builds would be ":v1", which Gradle rejects.
    val result = build(":greeting-lib:jar", ":toolkit-lib:jar")

    assertEquals(TaskOutcome.SUCCESS, result.task(":greeting-lib:jar")?.outcome)
    assertEquals(TaskOutcome.SUCCESS, result.task(":toolkit-lib:jar")?.outcome)
    assertTrue(clones.resolve("toolkit-lib/v1").isCompleteClone())
  }

  @Test
  fun `deletes the former clone of a tag with a slash next to the used one`() {
    writeGradleProperties(consumer, libraryTag = "release/1.0")
    build("help")
    writeGradleProperties(consumer, libraryTag = "release/2.0")

    build("help")

    assertTrue(cloneOf("release/2.0").isCompleteClone())
    assertFalse(cloneOf("release/1.0").exists())
  }

  @Test
  fun `keeps the temporary directory of a live process and deletes the leftover of a finished one`() {
    // The build runs in a Gradle daemon, so the process of the test is another live process for it.
    val ofLiveProcess = clones.resolve("greeting-lib/v2.tmp-${ProcessHandle.current().pid()}").apply { mkdirs() }
    val ofFinishedProcess = clones.resolve("greeting-lib/v2.tmp-$PROCESS_ID_THAT_NO_SYSTEM_ASSIGNS").apply { mkdirs() }

    build("help")

    assertTrue(ofLiveProcess.isDirectory)
    assertFalse(ofFinishedProcess.exists())
  }

  @Test
  fun `deleting a clone leaves the files behind a symlink in it`() {
    val outside = temporaryDirectory.resolve("outside").apply { mkdirs() }
    val fileOutside = outside.resolve("kept.txt").apply { writeText("kept") }
    val formerClone = cloneOf("v0").apply { mkdirs() }
    formerClone.resolve(CLONED_MARK_FILE_NAME).writeText("cloned")
    Files.createSymbolicLink(formerClone.resolve("link-to-outside").toPath(), outside.toPath())

    build("help")

    assertFalse(formerClone.exists())
    assertEquals("kept", fileOutside.readText())
  }

  @Test
  fun `a clone that cannot be deleted does not fail the build`() {
    val lockedDirectory = cloneOf("v0").resolve("locked").apply { mkdirs() }
    lockedDirectory.resolve("file.txt").writeText("content")
    lockedDirectory.setWritable(false)
    assumeFalse(Files.isWritable(lockedDirectory.toPath()), "this user deletes from a read-only directory")

    try {
      val result = build("printRuntimeClasspath")

      assertContains(result.output, "Git dependencies cleanup: could not delete .gradle/dependencies/greeting-lib/v0")
      assertContains(result.output, RUNTIME_CLASSPATH_OF_THE_CLONE_V1)
    } finally {
      // Cleanup has renamed the clone, so the locked directory is looked up again.
      clones.walkTopDown().filter { it.isDirectory }.forEach { it.setWritable(true) }
    }
  }

  @Test
  fun `fails with the output of git when the tag does not exist`() {
    writeGradleProperties(consumer, libraryTag = "v404")

    val result = buildAndFail("help")

    assertContains(result.output, "Could not clone ${libraryRepository.toGitUrl()} at tag v404:")
    assertContains(result.output, "Remote branch v404 not found in upstream origin")
    assertFalse(cloneOf("v404").exists())
    assertEquals(emptyList(), clones.walk().filter { ".tmp-" in it.name }.toList(), "no temporary directory is left")
  }

  @Test
  fun `fails when the tag is blank`() {
    writeGradleProperties(consumer, libraryTag = "")

    val result = buildAndFail("help")

    assertContains(result.output, "The tag of the git dependency ${libraryRepository.toGitUrl()} must not be blank")
  }

  @Test
  fun `git applies the url rewriting of its configuration`() {
    consumer.resolve("gradle.properties").writeText(
      "libraryRepository=https://git.invalid/acme/greeting-lib.git\nlibraryTag=v1\n",
    )
    val rewriting =
      mapOf(
        "GIT_CONFIG_COUNT" to "1",
        "GIT_CONFIG_KEY_0" to "url.${libraryRepository.parentFile.toGitUrl()}/.insteadOf",
        "GIT_CONFIG_VALUE_0" to "https://git.invalid/acme/",
      )

    val result = build("printRuntimeClasspath", environment = rewriting)

    assertTrue(cloneOf("v1").isCompleteClone())
    assertContains(result.output, RUNTIME_CLASSPATH_OF_THE_CLONE_V1)
  }

  @Test
  fun `a Groovy settings script checks out with the extension`() {
    consumer.resolve("settings.gradle.kts").delete()
    consumer.resolve("settings.gradle").writeText(GROOVY_SETTINGS_INCLUDING_THE_LIBRARY)

    val result = build("printRuntimeClasspath")

    assertContains(result.output, RUNTIME_CLASSPATH_OF_THE_CLONE_V1)
  }

  @Test
  fun `a build applies the published plugin by id and version`() {
    consumer.resolve("settings.gradle.kts").writeText(SETTINGS_APPLYING_THE_PUBLISHED_PLUGIN)
    consumer.resolve("gradle.properties").appendText(
      "pluginRepository=${File(System.getProperty("pluginTestRepository")).toURI()}\n" +
        "pluginVersion=${System.getProperty("pluginVersion")}\n",
    )

    val result = runner(consumer, listOf("printRuntimeClasspath"), emptyMap(), pluginFromTheBuild = false).build()

    assertContains(result.output, RUNTIME_CLASSPATH_OF_THE_CLONE_V1)
  }

  @Test
  fun `stores and reuses a configuration cache entry`() {
    val storing = build("printRuntimeClasspath", "--configuration-cache")
    val reusing = build("printRuntimeClasspath", "--configuration-cache")

    assertContains(storing.output, "Configuration cache entry stored.")
    assertContains(reusing.output, "Configuration cache entry reused.")
    assertContains(reusing.output, RUNTIME_CLASSPATH_OF_THE_CLONE_V1)
  }

  @Test
  fun `clones again when the clone is gone and the configuration cache entry is reused`() {
    build("printRuntimeClasspath", "--configuration-cache")
    clones.deleteRecursively()

    val result = build("printRuntimeClasspath", "--configuration-cache")

    assertContains(result.output, "Configuration cache entry reused.")
    assertTrue(cloneOf("v1").isCompleteClone())
    assertContains(result.output, RUNTIME_CLASSPATH_OF_THE_CLONE_V1)
  }

  @Test
  fun `cleanup does not invalidate the configuration cache entry`() {
    build("printRuntimeClasspath", "--configuration-cache")
    writeGradleProperties(consumer, libraryTag = "v2")

    val afterTagChange = build("printRuntimeClasspath", "--configuration-cache")
    val afterCleanup = build("printRuntimeClasspath", "--configuration-cache")

    assertContains(afterTagChange.output, "Configuration cache entry stored.")
    assertContains(afterTagChange.output, "Git dependencies cleanup: deleted .gradle/dependencies/greeting-lib/v1")
    assertFalse(cloneOf("v1").exists())
    assertContains(afterCleanup.output, "Configuration cache entry reused.")
    assertContains(
      afterCleanup.output,
      "runtime classpath: .gradle/dependencies/greeting-lib/v2/build/libs/greeting-lib-2.0.jar",
    )
  }

  @Test
  fun `cleanup runs when the configuration cache entry is reused`() {
    build("help", "--configuration-cache")
    val formerClone = cloneOf("v0").apply { mkdirs() }

    val result = build("help", "--configuration-cache")

    assertContains(result.output, "Configuration cache entry reused.")
    assertFalse(formerClone.exists())
  }

  @Test
  fun `an included build that shares the directory does not delete the clones of the root build`() {
    val nested = createNestedBuild(NESTED_SETTINGS_SHARING_THE_DIRECTORY_OF_THE_ROOT_BUILD)

    val result = build("printRuntimeClasspath")

    assertContains(result.output, RUNTIME_CLASSPATH_OF_THE_CLONE_V1)
    assertTrue(cloneOf("v1").isCompleteClone(), "the clone of the root build stays")
    assertTrue(cloneOf("v2").isCompleteClone(), "the included build clones into the directory of the root build")
    assertFalse(nested.resolve(".gradle/dependencies").exists())
  }

  @Test
  fun `an included build with the configuration cache does not delete the clones of the root build`() {
    createNestedBuild(NESTED_SETTINGS_SHARING_THE_DIRECTORY_OF_THE_ROOT_BUILD)
    build("printRuntimeClasspath", "--configuration-cache")

    val result = build("printRuntimeClasspath", "--configuration-cache")

    assertContains(result.output, "Configuration cache entry reused.")
    assertContains(result.output, RUNTIME_CLASSPATH_OF_THE_CLONE_V1)
    assertTrue(cloneOf("v1").isCompleteClone())
    assertTrue(cloneOf("v2").isCompleteClone())
  }

  @Test
  fun `a build deletes from its own directory only when it is the root build`() {
    val nested = createNestedBuild(NESTED_SETTINGS_WITH_ITS_OWN_DIRECTORY)
    val formerCloneOfNested = nested.resolve(".gradle/dependencies/greeting-lib/v0").apply { mkdirs() }

    build("help")
    assertTrue(formerCloneOfNested.isDirectory, "an included build deletes nothing")
    assertTrue(nested.resolve(".gradle/dependencies/greeting-lib/v2").isCompleteClone())

    build("help", projectDirectory = nested)
    assertFalse(formerCloneOfNested.exists(), "the same build deletes when it runs on its own")
    assertTrue(nested.resolve(".gradle/dependencies/greeting-lib/v2").isCompleteClone())
  }

  @Test
  fun `clones into the configured directory and deletes nothing from it`() {
    consumer.resolve("settings.gradle.kts").writeText(SETTINGS_WITH_A_CONFIGURED_DIRECTORY)
    val configuredDirectory = consumer.resolve("shared-clones")
    val cloneOfAnotherBuild = configuredDirectory.resolve("greeting-lib/v0").apply { mkdirs() }
    val fileOfSomebodyElse = configuredDirectory.resolve("notes.txt").apply { writeText("notes") }

    val result = build("printRuntimeClasspath")

    assertContains(
      result.output,
      "runtime classpath: shared-clones/greeting-lib/v1/build/libs/greeting-lib-1.0.jar",
    )
    assertTrue(configuredDirectory.resolve("greeting-lib/v1").isCompleteClone())
    assertTrue(cloneOfAnotherBuild.isDirectory)
    assertTrue(fileOfSomebodyElse.isFile)
    assertFalse(clones.exists())
  }

  @Test
  fun `fails when the directory changes after a checkout`() {
    consumer.resolve("settings.gradle.kts").appendText("\ngitDependencies {\n  directory = file(\"elsewhere\")\n}\n")

    val result = buildAndFail("help")

    assertContains(result.output, "property 'directory' is final and cannot be changed any further")
  }

  private fun cloneOf(tag: String): File = clones.resolve("greeting-lib").resolve(tag)

  private fun File.isCompleteClone(): Boolean = resolve(CLONED_MARK_FILE_NAME).isFile

  private fun writeGradleProperties(build: File, libraryTag: String) {
    build.resolve("gradle.properties").writeText(
      "libraryRepository=${libraryRepository.toGitUrl()}\nlibraryTag=$libraryTag\n",
    )
  }

  /** Makes the root build include the build `nested`, which checks out the tag `v2` of the library. */
  private fun createNestedBuild(nestedSettings: String): File {
    consumer.resolve("settings.gradle.kts").appendText("\nincludeBuild(\"nested\")\n")
    val nested = consumer.resolve("nested").apply { mkdirs() }
    nested.resolve("settings.gradle.kts").writeText(nestedSettings)
    writeGradleProperties(nested, libraryTag = "v2")
    return nested
  }

  private fun build(
    vararg arguments: String,
    projectDirectory: File = consumer,
    environment: Map<String, String> = emptyMap(),
  ): BuildResult = runner(projectDirectory, arguments.toList(), environment).build()

  private fun buildAndFail(vararg arguments: String): BuildResult =
    runner(consumer, arguments.toList(), emptyMap()).buildAndFail()

  private fun runner(
    projectDirectory: File,
    arguments: List<String>,
    environment: Map<String, String>,
    pluginFromTheBuild: Boolean = true,
  ): GradleRunner {
    val runner =
      GradleRunner
        .create()
        .withProjectDir(projectDirectory)
        .withEnvironment(System.getenv() + git.environment + environment)
        .withArguments(arguments)
    if (pluginFromTheBuild) runner.withPluginClasspath()
    if (gradleVersion != null) runner.withGradleVersion(gradleVersion)
    return runner
  }

  private companion object {
    const val RUNTIME_CLASSPATH_OF_THE_CLONE_V1 =
      "runtime classpath: .gradle/dependencies/greeting-lib/v1/build/libs/greeting-lib-1.0.jar"

    /** Larger than the largest process id of Linux, macOS and Windows. */
    const val PROCESS_ID_THAT_NO_SYSTEM_ASSIGNS = Int.MAX_VALUE

    val SETTINGS_INCLUDING_THE_LIBRARY =
      """
      import ru.code4a.gradle.gitdependencies.checkoutGitDependency

      plugins {
        id("ru.code4a.git-dependencies")
      }

      rootProject.name = "consumer"

      includeBuild(
        checkoutGitDependency(
          providers.gradleProperty("libraryRepository").get(),
          providers.gradleProperty("libraryTag").get(),
        )
      )
      """.trimIndent() + "\n"

    val SETTINGS_APPLYING_THE_PUBLISHED_PLUGIN =
      """
      import ru.code4a.gradle.gitdependencies.checkoutGitDependency

      pluginManagement {
        repositories {
          maven {
            url = uri(providers.gradleProperty("pluginRepository").get())
          }
        }
        plugins {
          id("ru.code4a.git-dependencies") version providers.gradleProperty("pluginVersion").get()
        }
      }

      plugins {
        id("ru.code4a.git-dependencies")
      }

      rootProject.name = "consumer"

      includeBuild(
        checkoutGitDependency(
          providers.gradleProperty("libraryRepository").get(),
          providers.gradleProperty("libraryTag").get(),
        )
      )
      """.trimIndent() + "\n"

    val SETTINGS_INCLUDING_TWO_NAMED_LIBRARIES =
      """
      import ru.code4a.gradle.gitdependencies.checkoutGitDependency

      plugins {
        id("ru.code4a.git-dependencies")
      }

      rootProject.name = "consumer"

      includeBuild(checkoutGitDependency(providers.gradleProperty("libraryRepository").get(), "v1")) {
        name = "greeting-lib"
      }
      includeBuild(checkoutGitDependency(providers.gradleProperty("toolkitRepository").get(), "v1")) {
        name = "toolkit-lib"
      }
      """.trimIndent() + "\n"

    val SETTINGS_CHECKING_OUT_TWO_TAGS =
      """
      import ru.code4a.gradle.gitdependencies.checkoutGitDependency

      plugins {
        id("ru.code4a.git-dependencies")
      }

      rootProject.name = "consumer"

      val libraryRepository = providers.gradleProperty("libraryRepository").get()
      checkoutGitDependency(libraryRepository, "v1")
      checkoutGitDependency(libraryRepository, "v2")
      """.trimIndent() + "\n"

    val SETTINGS_WITH_A_CONFIGURED_DIRECTORY =
      """
      import ru.code4a.gradle.gitdependencies.checkoutGitDependency

      plugins {
        id("ru.code4a.git-dependencies")
      }

      rootProject.name = "consumer"

      gitDependencies {
        directory = file("shared-clones")
      }

      includeBuild(
        checkoutGitDependency(
          providers.gradleProperty("libraryRepository").get(),
          providers.gradleProperty("libraryTag").get(),
        )
      )
      """.trimIndent() + "\n"

    val GROOVY_SETTINGS_INCLUDING_THE_LIBRARY =
      """
      plugins {
        id 'ru.code4a.git-dependencies'
      }

      rootProject.name = 'consumer'

      includeBuild(
        gitDependencies.checkout(
          providers.gradleProperty('libraryRepository').get(),
          providers.gradleProperty('libraryTag').get(),
        )
      )
      """.trimIndent() + "\n"

    val NESTED_SETTINGS_SHARING_THE_DIRECTORY_OF_THE_ROOT_BUILD =
      """
      import ru.code4a.gradle.gitdependencies.checkoutGitDependency

      plugins {
        id("ru.code4a.git-dependencies")
      }

      rootProject.name = "nested"

      gitDependencies {
        directory = file("../.gradle/dependencies")
      }

      checkoutGitDependency(
        providers.gradleProperty("libraryRepository").get(),
        providers.gradleProperty("libraryTag").get(),
      )
      """.trimIndent() + "\n"

    val NESTED_SETTINGS_WITH_ITS_OWN_DIRECTORY =
      """
      import ru.code4a.gradle.gitdependencies.checkoutGitDependency

      plugins {
        id("ru.code4a.git-dependencies")
      }

      rootProject.name = "nested"

      checkoutGitDependency(
        providers.gradleProperty("libraryRepository").get(),
        providers.gradleProperty("libraryTag").get(),
      )
      """.trimIndent() + "\n"

    val BUILD_PRINTING_THE_RUNTIME_CLASSPATH =
      """
      plugins {
        `java-library`
      }

      dependencies {
        // No repository is declared: only the included clone can provide the library.
        implementation("com.example:greeting-lib:1.0")
      }

      tasks.register("printRuntimeClasspath") {
        val runtimeClasspath: FileCollection = configurations.runtimeClasspath.get()
        val rootDirectory = rootDir
        inputs.files(runtimeClasspath)
        doLast {
          runtimeClasspath.files.forEach {
            println("runtime classpath: " + it.relativeTo(rootDirectory).invariantSeparatorsPath)
          }
        }
      }
      """.trimIndent() + "\n"
  }
}

internal class CurrentGradleFunctionalTest : GitDependenciesFunctionalTest(gradleVersion = null)

internal class MinimumGradleFunctionalTest :
  GitDependenciesFunctionalTest(gradleVersion = System.getProperty("minimumGradleVersion"))
