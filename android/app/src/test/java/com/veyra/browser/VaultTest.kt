package com.veyra.browser

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.crypto.KeyGenerator

class VaultTest {
    @get:Rule val folder = TemporaryFolder()

    private fun vault(): PrivateVault {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        return PrivateVault(folder.newFolder("vault")) { key }
    }

    @Test fun noteRoundTripsAndIsNotStoredInPlaintext() {
        val v = vault()
        val entry = v.addNote("Secret title", "launch codes: 12345")
        assertEquals("launch codes: 12345", v.readNote(entry.id))
        val stored = folder.root.walkTopDown().filter { it.isFile }.joinToString("") { String(it.readBytes(), Charsets.ISO_8859_1) }
        assertFalse(stored.contains("launch codes"))
        assertFalse(stored.contains("Secret title"))
    }

    @Test fun indexListsItemsNewestFirst() {
        val v = vault()
        val a = v.addNote("a", "1")
        Thread.sleep(5)
        val b = v.addNote("b", "2")
        assertEquals(listOf(b.id, a.id), v.list().map { it.id })
    }

    @Test fun fileImportExportRoundTrip() {
        val v = vault()
        val data = ByteArray(200_000) { (it % 251).toByte() }
        val entry = v.importFile("photo.bin", ByteArrayInputStream(data), PrivateVault.MAX_FILE_BYTES)
        assertEquals(data.size.toLong(), entry.size)
        val out = ByteArrayOutputStream()
        v.exportTo(entry.id, out)
        assertArrayEquals(data, out.toByteArray())
    }

    @Test fun oversizedImportIsRejectedAndLeavesNothingBehind() {
        val v = vault()
        try {
            v.importFile("big", ByteArrayInputStream(ByteArray(1000)), 100)
            fail("expected TooLargeException")
        } catch (_: PrivateVault.TooLargeException) {
        }
        assertTrue(v.list().isEmpty())
    }

    @Test fun tamperedCiphertextIsDetected() {
        val v = vault()
        val entry = v.importFile("x", ByteArrayInputStream("hello world".toByteArray()), 1000)
        val file = folder.root.walkTopDown().first { it.name == "${entry.id}.enc" }
        val bytes = file.readBytes()
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 1).toByte()
        file.writeBytes(bytes)
        try {
            v.exportTo(entry.id, ByteArrayOutputStream())
            fail("expected authentication failure")
        } catch (_: Exception) {
        }
    }

    @Test fun wrongKeyCannotReadIndex() {
        val dir = folder.newFolder("shared")
        val v1 = PrivateVault(dir) { KeyGenerator.getInstance("AES").apply { init(256) }.generateKey() }
        v1.addNote("n", "t")
        val v2 = PrivateVault(dir) { KeyGenerator.getInstance("AES").apply { init(256) }.generateKey() }
        try {
            v2.list()
            fail("expected failure with a different key")
        } catch (_: Exception) {
        }
    }

    @Test fun deleteRemovesItem() {
        val v = vault()
        val e = v.addNote("n", "t")
        v.delete(e.id)
        assertTrue(v.list().isEmpty())
    }
}
