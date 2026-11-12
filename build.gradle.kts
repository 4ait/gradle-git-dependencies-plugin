import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jreleaser.model.Active

plugins {
  `kotlin-dsl`
  `maven-publish`
  id("org.jreleaser") version "1.26.0"
}

group = "ru.code4a"
version = file("version").readText().trim()

repositories {
  mavenCentral()
}

// The plugin is loaded by the JVM that runs Gradle in the consuming build, and Gradle rejects
// a plugin compiled for a newer JVM than that. Gradle 9 itself needs Java 17, so bytecode for
// Java 17 runs wherever a supported Gradle version runs. Without an explicit target the compiler
// would target the JDK of the release build.
val pluginJavaVersion = JavaVersion.VERSION_17

java {
  sourceCompatibility = pluginJavaVersion
  targetCompatibility = pluginJavaVersion
  withJavadocJar()
  withSourcesJar()
}

kotlin {
  compilerOptions {
    jvmTarget = JvmTarget.fromTarget(pluginJavaVersion.majorVersion)
    // The bytecode target alone still lets the code call a JDK API that Java 17 does not have.
    freeCompilerArgs.add("-Xjdk-release=${pluginJavaVersion.majorVersion}")
  }
}

gradlePlugin {
  plugins {
    create("gitDependencies") {
      id = "ru.code4a.git-dependencies"
      implementationClass = "ru.code4a.gradle.gitdependencies.GitDependenciesPlugin"
      displayName = "Git Dependencies Gradle Plugin"
      description = "Gradle settings plugin that clones git repositories by tag with the system git " +
        "so that a build can include their sources with includeBuild."
    }
  }
}

// The tests also apply the plugin the way a consuming build does: by id and version from a Maven
// repository. It is not the staging directory, which must hold nothing but the release.
val pluginTestRepository = layout.buildDirectory.dir("plugin-test-repository")

publishing {
  publications {
    // java-gradle-plugin fills this publication with the plugin jar and adds the marker publication
    // ru.code4a.git-dependencies:ru.code4a.git-dependencies.gradle.plugin next to it. Gradle looks
    // the marker up to resolve the plugin id in a plugins {} block.
    create<MavenPublication>("pluginMaven") {
      artifactId = "gradle-git-dependencies-plugin"
    }

    // Maven Central validates the POM of every publication, the marker included.
    withType<MavenPublication>().configureEach {
      pom {
        name = "Git Dependencies Gradle Plugin"
        description =
          "A Gradle settings plugin that builds dependencies from sources: it clones a git repository by tag " +
            "with the system git, so credential helpers and SSH settings of the machine and of CI apply, " +
            "and returns the clone for includeBuild. Clones are cached per repository and tag, and the " +
            "clones a build no longer uses are deleted."
        url = "https://github.com/4ait/gradle-git-dependencies-plugin"
        inceptionYear = "2026"
        licenses {
          license {
            name = "The Apache License, Version 2.0"
            url = "https://www.apache.org/licenses/LICENSE-2.0.txt"
          }
        }
        developers {
          developer {
            id = "tikara"
            name = "Evgeniy Simonenko"
            email = "tiikara93@gmail.com"
            organization.set("4A LLC")
            roles.set(
              listOf(
                "Software Developer",
                "Head of Development"
              )
            )
          }
        }
        organization {
          name = "4A LLC"
          url = "https://4ait.ru"
        }
        scm {
          connection = "scm:git:git://github.com:4ait/gradle-git-dependencies-plugin.git"
          developerConnection = "scm:git:ssh://github.com:4ait/gradle-git-dependencies-plugin.git"
          url = "https://github.com/4ait/gradle-git-dependencies-plugin"
        }
      }
    }
  }
  repositories {
    maven {
      url =
        layout.buildDirectory
          .dir("staging-deploy")
          .get()
          .asFile
          .toURI()
    }
    maven {
      name = "pluginTest"
      url = pluginTestRepository.get().asFile.toURI()
    }
  }
}

dependencies {
  val junitVersion = providers.gradleProperty("junitVersion").get()

  testImplementation(kotlin("test"))
  testImplementation("org.junit.jupiter:junit-jupiter:$junitVersion")
  testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
  useJUnitPlatform()
  dependsOn("publishAllPublicationsToPluginTestRepository")
  // The publication is an input of the tests: they run again when the POM or the marker changes.
  // maven-metadata.xml holds the time of publishing and would make them run every time.
  inputs
    .files(fileTree(pluginTestRepository) { exclude("**/maven-metadata.xml*") })
    .withPropertyName("pluginTestRepository")
    .withPathSensitivity(PathSensitivity.RELATIVE)
  systemProperty("pluginTestRepository", pluginTestRepository.get().asFile.path)
  systemProperty("pluginVersion", version.toString())
  systemProperty("minimumGradleVersion", providers.gradleProperty("minimumGradleVersion").get())
  testLogging {
    events("passed", "skipped", "failed")
    exceptionFormat = TestExceptionFormat.FULL
  }
}

jreleaser {
  project {
    copyright.set("4A LLC")
  }
  gitRootSearch.set(true)
  signing {
    active.set(Active.ALWAYS)
    armored.set(true)
  }
  release {
    github {
      overwrite.set(true)
      branch.set("main")
    }
  }
  deploy {
    maven {
      mavenCentral {
        create("maven-central") {
          active.set(Active.ALWAYS)
          url.set("https://central.sonatype.com/api/v1/publisher")
          stagingRepositories.add("build/staging-deploy")
          retryDelay.set(30)
        }
      }
    }
  }
}
