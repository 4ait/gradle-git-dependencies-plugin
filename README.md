# Git Dependencies Gradle Plugin

A Gradle settings plugin that builds dependencies from sources. It clones a git repository by tag with the system git
and returns the directory of the clone. The settings script passes it to `includeBuild`, and Gradle substitutes the
projects of the clone for dependencies on their coordinates.

[![Maven Central](https://img.shields.io/maven-central/v/ru.code4a/gradle-git-dependencies-plugin)](https://central.sonatype.com/artifact/ru.code4a/gradle-git-dependencies-plugin)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://www.apache.org/licenses/LICENSE-2.0)

## Why the system git

The system git is the tool that already works on a developer machine and on CI: it uses the configured credential
helpers, tokens, `insteadOf` rewrites and the SSH setup. Gradle's own source dependencies (`sourceControl`) clone with
the bundled JGit, which does not use the credential helpers of git.

## Requirements

- Gradle 9.3.1 or newer. The tests run every scenario on Gradle 9.3.1 and on the Gradle version of the wrapper (9.7.0).
- `git` on the `PATH` of the process that runs Gradle.

The plugin is compiled for Java 17. Gradle loads a settings plugin into the JVM that runs the build and refuses a
plugin that needs a newer JVM than that one. Gradle 9 itself runs on Java 17 or newer, so a plugin for Java 17 loads
wherever a supported Gradle version runs, whatever JDK the projects of the build compile with.

## Usage

`settings.gradle.kts`:

```kotlin
import ru.code4a.gradle.gitdependencies.checkoutGitDependency

pluginManagement {
  repositories {
    mavenCentral()
    gradlePluginPortal()
  }
}

plugins {
  id("ru.code4a.git-dependencies") version "<version>"
}

includeBuild(
  checkoutGitDependency(
    "https://github.com/acme/library.git",
    providers.gradleProperty("libraryVersion").get(),
  )
)
```

`gradle.properties`:

```properties
# Git tag of the library repository.
libraryVersion=v1.2.0
```

A project of the build then depends on the library by coordinates. Gradle replaces the module with the project of the
included clone, whatever version the coordinates name:

```kotlin
dependencies {
  implementation("com.acme:library:1.2.0")
}
```

`checkoutGitDependency(repository, tag)` clones `repository` at `tag` unless the clone exists and returns the directory
`.gradle/dependencies/<repository>/<tag>`. `<repository>` is the last segment of the URL without `.git`.

To move to another version of the library, change the tag. The next build clones the new tag and deletes the clone of
the former one.

The plugin also registers the extension `gitDependencies`. `gitDependencies.checkout(repository, tag)` does the same as
`checkoutGitDependency` and needs no import, which suits a Groovy settings script:

```groovy
plugins {
  id 'ru.code4a.git-dependencies' version '<version>'
}

includeBuild(gitDependencies.checkout('https://github.com/acme/library.git', 'v1.2.0'))
```

### Two dependencies with the same tag

Gradle names an included build after its directory, and the directory of a clone is the tag. Two repositories included
at the same tag would both be the build `:v1.2.0`, which Gradle rejects. Name the builds:

```kotlin
includeBuild(checkoutGitDependency("https://github.com/acme/library.git", "v1.2.0")) {
  name = "library"
}
includeBuild(checkoutGitDependency("https://github.com/acme/toolkit.git", "v1.2.0")) {
  name = "toolkit"
}
```

## Clones

A clone is a snapshot of the tag:

- Git runs as `git clone --depth 1 --branch <tag>`, and the `.git` directory of the clone is deleted afterwards.
- The clone goes to a temporary directory next to its final path, `<tag>.tmp-<pid>`, and is renamed when it is
  complete. A parallel build never sees a half-downloaded repository. The file `gradle_dep_cloned_mark` marks a
  complete clone.
- A complete clone is never fetched again. If a tag has been moved in the repository, delete the directory of the
  clone to get the new commit. For the same reason a branch name does not work as a tag: git would clone the branch
  once, and the clone would never follow it.

### Cleanup

After the settings script has been evaluated, the build deletes everything from `.gradle/dependencies` that it has not
checked out: the clones of former tags and of repositories that it no longer includes. The checkouts count wherever the
settings script requests them, so there is no separate list to maintain.

- A temporary directory of another live process stays: a parallel build is still cloning into it or deleting it. The
  leftover of an interrupted build is deleted.
- A symlink inside a deleted clone is deleted itself. The files behind it stay.
- Cleanup never fails the build. If an entry cannot be deleted, the build prints a warning and the next build tries
  again.

Only the root build deletes, and only from its own default directory:

- A build that is included in another build deletes nothing.
- A build with a configured `directory` deletes nothing from it.

### Directory of the clones

```kotlin
gitDependencies {
  directory = file("../.gradle/dependencies")
}
```

Set `directory` before the first checkout. An included build can point it to the directory of the build that includes
it, and both use the same clones.

The plugin does not delete from a configured directory, because it may belong to another build, and a build knows only
its own checkouts. The build that owns the directory still deletes whatever it has not checked out itself. An included
build that shares the directory of the root build should therefore check out only what the root build checks out too.
Otherwise the root build deletes that clone on every run, and the included build clones it again.

### Configuration cache

The plugin works with the configuration cache. Git runs inside a `ValueSource`, which Gradle evaluates again before it
reuses a cache entry:

- A clone that was deleted after the entry had been stored is cloned again, and the entry is reused.
- Cleanup runs on every build, with or without a cache hit, and does not invalidate the entry.

## Private repositories and CI

The plugin runs `git clone` as the user who runs Gradle. Whatever makes `git clone <repository>` work in a shell of
that user without a question makes the plugin work. A Gradle daemon has no terminal where git could ask for a password
or a passphrase, so authentication has to work without a prompt: a credential helper, a token or an SSH agent.

On CI the usual way is to rewrite the URL with `url.<base>.insteadOf`, so that the settings script keeps the plain
URL and the credentials stay in the CI configuration.

GitHub Actions, with a token that can read the repositories:

```yaml
- name: Let git clone the private dependencies
  run: git config --global url."https://x-access-token:${{ secrets.DEPENDENCIES_TOKEN }}@github.com/".insteadOf "https://github.com/"
```

GitLab CI, with the token of the job:

```yaml
before_script:
  - git config --global url."https://gitlab-ci-token:${CI_JOB_TOKEN}@gitlab.example.com/".insteadOf "https://gitlab.example.com/"
```

The same rewriting switches a machine to SSH:

```shell
git config --global url."git@github.com:".insteadOf "https://github.com/"
```

Git reads the rewriting from its global or system configuration, or from the environment variables
`GIT_CONFIG_COUNT`, `GIT_CONFIG_KEY_<n>` and `GIT_CONFIG_VALUE_<n>`. The directory of the clone is named after the URL
in the settings script, not after the rewritten one.

## Developing a library together with a project

To work on a library and on a project that uses it at the same time, include the working copy of the library instead
of the clone:

```kotlin
// includeBuild(checkoutGitDependency("https://github.com/acme/library.git", providers.gradleProperty("libraryVersion").get()))
includeBuild("../library")
```

The next build deletes the unused clone, and it is cloned again when the line is restored.

## Limitations

- **The plugin itself has to come from a binary repository.** Gradle resolves a settings plugin before it evaluates
  the settings script, so the plugin cannot be one of the repositories that it clones. Published versions are in Maven
  Central. To try a working copy of the plugin, include it as a plugin build:

  ```kotlin
  pluginManagement {
    includeBuild("../gradle-git-dependencies-plugin")
  }

  plugins {
    id("ru.code4a.git-dependencies")
  }
  ```

- **Call `checkoutGitDependency` from the settings script.** Cleanup runs right after the script and deletes the
  clone of a checkout that is requested later.
- **The plugin is tested on macOS and Linux.** It is not tested on Windows.

## Building

```shell
./gradlew test
```

The tests need `git` on the `PATH`. They clone only repositories that they create in temporary directories. The first
run downloads the distribution of the oldest supported Gradle version.

See [PUBLISH.md](PUBLISH.md) for releases.

## License

Apache 2.0
