package com.clawmate.platform

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class LocalImageAccessTest {
    @Test fun boundedDigestMatchesKnownBytesAndDetectsChangedSnapshot() {
        val directory = java.nio.file.Files.createTempDirectory("clawmate-digest-").toFile()
        try {
            val file = File(directory, "snapshot.jpg").apply { writeText("abc") }
            val expected = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
            assertEquals(expected, LocalImageAccess.digest(file.toURI().toString(), listOf(directory)))
            assertEquals(expected, LocalImageAccess.sha256("abc".toByteArray()))
            file.writeText("changed")
            assertNotEquals(expected, LocalImageAccess.digest(file.toURI().toString(), listOf(directory)))
        } finally { directory.deleteRecursively() }
    }
    @Test fun digestRefusesEmptyOversizedAndNonPrivateInputs() {
        val directory = java.nio.file.Files.createTempDirectory("clawmate-digest-").toFile()
        try {
            val file = File(directory, "snapshot.jpg").apply { writeBytes(byteArrayOf()) }
            assertThrows(IllegalArgumentException::class.java) { LocalImageAccess.digest(file.toURI().toString(), listOf(directory)) }
            file.writeBytes(ByteArray(10))
            assertThrows(IllegalArgumentException::class.java) { LocalImageAccess.digest(file.toURI().toString(), listOf(directory), 9) }
            assertThrows(IllegalArgumentException::class.java) { LocalImageAccess.digest("content://picker/owned", listOf(directory)) }
            assertThrows(IllegalArgumentException::class.java) { LocalImageAccess.digest(file.toURI().toString(), listOf(File(directory, "child"))) }
            assertThrows(IllegalArgumentException::class.java) { LocalImageAccess.digest(file.toURI().toString(), listOf(directory), 0) }
        } finally { directory.deleteRecursively() }
    }
    @Test fun interruptedDigestDoesNotContinueReading() {
        val directory = java.nio.file.Files.createTempDirectory("clawmate-digest-").toFile()
        try {
            val file = File(directory, "snapshot.jpg").apply { writeText("fixture") }
            Thread.currentThread().interrupt()
            assertThrows(InterruptedException::class.java) { LocalImageAccess.digest(file.toURI().toString(), listOf(directory)) }
        } finally { Thread.interrupted(); directory.deleteRecursively() }
    }
    private val root = File(System.getProperty("java.io.tmpdir"), "clawmate-image-policy/files")
    @Test fun privateImageAndGrantedContentCanBeAttempted() {
        assertEquals(File(root, "照片.png").toURI().toString(), LocalImageAccess.validate(File(root, "照片.png").toURI().toString(), listOf(root)))
        assertEquals("content://media/picker/1", LocalImageAccess.validate("content://media/picker/1", listOf(root)))
    }
    @Test fun remoteAndSiblingDirectoriesAreRejected() {
        for (value in listOf("https://fixture.invalid/photo.png", "file:///etc/passwd", File(root.parentFile, "files-other/photo.png").toURI().toString(), File(root, "../database.db").toURI().toString())) {
            assertThrows(IllegalArgumentException::class.java) { LocalImageAccess.validate(value, listOf(root)) }
        }
    }
    @Test fun encodedTraversalAndAuthorityAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { LocalImageAccess.validate(root.toURI().toString().trimEnd('/')+"/%2e%2e/database.db", listOf(root)) }
        assertThrows(IllegalArgumentException::class.java) { LocalImageAccess.validate("file://fixture.invalid/photo.png", listOf(root)) }
        assertThrows(IllegalArgumentException::class.java) { LocalImageAccess.validate("content://user:secret@provider/photo", listOf(root)) }
    }
}
