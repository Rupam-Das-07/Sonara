package com.example.sonara.data.playlist

import android.content.Context
import android.content.ContextWrapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.UUID

class AndroidPlaylistImageStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val safeUuid = UUID.randomUUID().toString()

    private class FakeContext(private val baseDir: File) : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = baseDir
    }

    private fun createMockContext(filesDir: File): Context = FakeContext(filesDir)

    @Test
    fun constants_enforceRequiredLimits() {
        assertEquals("Max edge must be 1024px", 1024, AndroidPlaylistImageStore.MAX_EDGE)
        assertEquals("Max input bytes must be 20 MB", 20L * 1024 * 1024, AndroidPlaylistImageStore.MAX_INPUT_BYTES)
        assertEquals("JPEG quality must be 85", 85, AndroidPlaylistImageStore.JPEG_QUALITY)
    }

    @Test
    fun fileNamePattern_validUuidNames_match() {
        val validJpg = "cover_$safeUuid.jpg"
        val validJpeg = "cover_$safeUuid.jpeg"
        val validPng = "cover_$safeUuid.png"

        val regexField = AndroidPlaylistImageStore::class.java.getDeclaredField("FILE_NAME").apply {
            isAccessible = true
        }
        val regex = regexField.get(null) as Regex

        assertTrue(regex.matches(validJpg))
        assertTrue(regex.matches(validJpeg))
        assertTrue(regex.matches(validPng))
    }

    @Test
    fun fileNamePattern_rejectsDirectoryTraversalAndInvalidNames() {
        val regexField = AndroidPlaylistImageStore::class.java.getDeclaredField("FILE_NAME").apply {
            isAccessible = true
        }
        val regex = regexField.get(null) as Regex

        assertFalse(regex.matches("../cover_$safeUuid.jpg"))
        assertFalse(regex.matches("..\\cover_$safeUuid.jpg"))
        assertFalse(regex.matches("/etc/passwd"))
        assertFalse(regex.matches("cover_short.jpg"))
        assertFalse(regex.matches("photo.png"))
        assertFalse(regex.matches("cover_$safeUuid.gif"))
        assertFalse(regex.matches("cover_$safeUuid.bmp"))
        assertFalse(regex.matches("cover_.jpg"))
        assertFalse(regex.matches(""))
    }

    @Test
    fun resolve_nonExistentFile_returnsNull() = runTest {
        val context = createMockContext(tempFolder.root)
        val store = AndroidPlaylistImageStore(context, Dispatchers.Unconfined)

        assertNull(store.resolve(null))
        assertNull(store.resolve(""))
        assertNull(store.resolve("cover_$safeUuid.jpg"))
    }

    @Test
    fun resolve_validExistingFile_returnsAbsolutePath() = runTest {
        val filesDir = tempFolder.root
        val coversDir = File(filesDir, "playlist_covers").apply { mkdirs() }
        val testFile = File(coversDir, "cover_$safeUuid.jpg").apply {
            writeBytes(byteArrayOf(1, 2, 3, 4))
        }

        val context = createMockContext(filesDir)
        val store = AndroidPlaylistImageStore(context, Dispatchers.Unconfined)

        val resolved = store.resolve("cover_$safeUuid.jpg")
        assertNotNull(resolved)
        assertEquals(testFile.absolutePath, resolved)

        // Resolving using full path must also work
        val resolvedFromPath = store.resolve(testFile.absolutePath)
        assertEquals(testFile.absolutePath, resolvedFromPath)
    }

    @Test
    fun resolve_emptyFile_returnsNull() = runTest {
        val filesDir = tempFolder.root
        val coversDir = File(filesDir, "playlist_covers").apply { mkdirs() }
        File(coversDir, "cover_$safeUuid.jpg").apply {
            createNewFile() // 0 bytes
        }

        val context = createMockContext(filesDir)
        val store = AndroidPlaylistImageStore(context, Dispatchers.Unconfined)

        assertNull("Empty 0-byte file must resolve to null", store.resolve("cover_$safeUuid.jpg"))
    }

    @Test
    fun resolve_directoryTraversalAttempt_returnsNull() = runTest {
        val filesDir = tempFolder.root
        // Place a secret file outside coversDir
        val secretFile = File(filesDir, "secret.txt").apply { writeText("secret") }

        val context = createMockContext(filesDir)
        val store = AndroidPlaylistImageStore(context, Dispatchers.Unconfined)

        assertNull(store.resolve("../secret.txt"))
        assertNull(store.resolve(secretFile.absolutePath))
    }

    @Test
    fun delete_validFile_deletesFromDisk() = runTest {
        val filesDir = tempFolder.root
        val coversDir = File(filesDir, "playlist_covers").apply { mkdirs() }
        val testFile = File(coversDir, "cover_$safeUuid.jpg").apply {
            writeBytes(byteArrayOf(1, 2, 3, 4))
        }
        assertTrue(testFile.exists())

        val context = createMockContext(filesDir)
        val store = AndroidPlaylistImageStore(context, Dispatchers.Unconfined)

        store.delete("cover_$safeUuid.jpg")
        assertFalse("File must be deleted", testFile.exists())
    }

    @Test
    fun delete_foreignPath_doesNotDeleteArbitraryFiles() = runTest {
        val filesDir = tempFolder.root
        val secretFile = File(filesDir, "important.db").apply { writeText("critical data") }
        assertTrue(secretFile.exists())

        val context = createMockContext(filesDir)
        val store = AndroidPlaylistImageStore(context, Dispatchers.Unconfined)

        // Attempt to delete foreign file
        store.delete(secretFile.absolutePath)
        assertTrue("Arbitrary foreign file must never be deleted", secretFile.exists())

        store.delete("../important.db")
        assertTrue("Path traversal attempt must not delete files", secretFile.exists())
    }

    @Test
    fun importImage_rejectsNonContentUriScheme() = runTest {
        val context = createMockContext(tempFolder.root)
        val store = AndroidPlaylistImageStore(context, Dispatchers.Unconfined)

        try {
            store.importImage("file:///storage/emulated/0/DCIM/photo.jpg")
            fail("Must reject file:// URI scheme")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("system picker") == true)
        }

        try {
            store.importImage("https://example.com/photo.jpg")
            fail("Must reject https:// URI scheme")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("system picker") == true)
        }
    }

    @Test
    fun importImage_rejectsEmptyOrBlankUri() = runTest {
        val context = createMockContext(tempFolder.root)
        val store = AndroidPlaylistImageStore(context, Dispatchers.Unconfined)

        try {
            store.importImage("")
            fail("Must reject empty URI")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("system picker") == true)
        }

        try {
            store.importImage("   ")
            fail("Must reject blank URI")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("system picker") == true)
        }
    }
}
