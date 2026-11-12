package ru.code4a.gradle.gitdependencies

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal class CheckoutFilesTest {
  @TempDir
  lateinit var temporaryDirectory: File

  @Test
  fun `deletes a directory tree`() {
    val tree = temporaryDirectory.resolve("tree")
    tree.resolve("nested/deeper").mkdirs()
    tree.resolve("nested/deeper/file.txt").writeText("content")
    tree.resolve("read-only.txt").apply { writeText("content") }.setWritable(false)

    deleteRecursivelyWithoutFollowingSymlinks(tree)

    assertFalse(tree.exists())
  }

  @Test
  fun `deletes a symlink in a tree and leaves its target`() {
    val target = temporaryDirectory.resolve("target").apply { mkdirs() }
    val fileInTarget = target.resolve("kept.txt").apply { writeText("kept") }
    val tree = temporaryDirectory.resolve("tree").apply { mkdirs() }
    Files.createSymbolicLink(tree.resolve("to-directory").toPath(), target.toPath())
    Files.createSymbolicLink(tree.resolve("to-file").toPath(), fileInTarget.toPath())
    Files.createSymbolicLink(tree.resolve("to-nothing").toPath(), temporaryDirectory.resolve("missing").toPath())

    deleteRecursivelyWithoutFollowingSymlinks(tree)

    assertFalse(tree.exists())
    assertEquals("kept", fileInTarget.readText())
  }

  @Test
  fun `deletes a symlink itself and leaves its target`() {
    val target = temporaryDirectory.resolve("target").apply { mkdirs() }
    val fileInTarget = target.resolve("kept.txt").apply { writeText("kept") }
    val link = temporaryDirectory.resolve("link")
    Files.createSymbolicLink(link.toPath(), target.toPath())

    deleteRecursivelyWithoutFollowingSymlinks(link)

    assertFalse(Files.exists(link.toPath(), LinkOption.NOFOLLOW_LINKS))
    assertEquals("kept", fileInTarget.readText())
  }

  @Test
  fun `does nothing when the path does not exist`() {
    deleteRecursivelyWithoutFollowingSymlinks(temporaryDirectory.resolve("missing"))

    assertTrue(temporaryDirectory.isDirectory)
  }

  @Test
  fun `names the temporary sibling after the path and the current process`() {
    val temporary = temporaryDirectory.resolve("library/v1").temporarySibling()

    assertEquals(temporaryDirectory.resolve("library/v1.tmp-${ProcessHandle.current().pid()}"), temporary)
  }

  @Test
  fun `tells the temporary directory of another live process from the other entries`() {
    val anotherLiveProcess = ProcessHandle.current().parent().orElseThrow().pid()
    val directory = temporaryDirectory.resolve("library")

    assertTrue(isTemporaryOfAnotherLiveProcess(directory.resolve("v1.tmp-$anotherLiveProcess")))
    assertFalse(isTemporaryOfAnotherLiveProcess(directory.resolve("v1.tmp-${ProcessHandle.current().pid()}")))
    assertFalse(isTemporaryOfAnotherLiveProcess(directory.resolve("v1.tmp-${Int.MAX_VALUE}")))
    assertFalse(isTemporaryOfAnotherLiveProcess(directory.resolve("v1")))
    assertFalse(isTemporaryOfAnotherLiveProcess(directory.resolve("v1.tmp-$anotherLiveProcess.txt")))
  }
}
