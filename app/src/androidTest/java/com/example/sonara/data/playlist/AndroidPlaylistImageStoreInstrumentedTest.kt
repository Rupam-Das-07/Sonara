package com.example.sonara.data.playlist

import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AndroidPlaylistImageStoreInstrumentedTest {
    private val target = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var sandbox: File
    private lateinit var context: Context
    private lateinit var store: AndroidPlaylistImageStore
    private val createdImages = mutableListOf<Uri>()

    @Before
    fun setup() {
        Assume.assumeTrue(Build.VERSION.SDK_INT >= 29)
        sandbox = File(target.cacheDir, "playlist-image-test-" + UUID.randomUUID()).apply { mkdirs() }
        context = object : ContextWrapper(target) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = sandbox
            override fun getCacheDir(): File = sandbox
        }
        store = AndroidPlaylistImageStore(context)
    }

    @After
    fun cleanup() {
        createdImages.forEach { target.contentResolver.delete(it, null, null) }
        if (::sandbox.isInitialized) {
            check(sandbox.parentFile == target.cacheDir && sandbox.name.startsWith("playlist-image-test-"))
            sandbox.deleteRecursively()
        }
    }

    private fun createJpegImage(width: Int = 3000, height: Int = 1500): Uri {
        val uri = target.contentResolver.insert(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "sonara-playlist-test-" + UUID.randomUUID() + ".jpg")
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/SonaraPlaylistTests")
            }
        )!!
        createdImages.add(uri)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(0xff4a90e2.toInt()) }
        target.contentResolver.openOutputStream(uri)!!.use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        bitmap.recycle()
        return uri
    }

    private fun createPngImage(width: Int = 1600, height: Int = 1200): Uri {
        val uri = target.contentResolver.insert(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "sonara-playlist-test-" + UUID.randomUUID() + ".png")
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/SonaraPlaylistTests")
            }
        )!!
        createdImages.add(uri)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(0xffe94e77.toInt()) }
        target.contentResolver.openOutputStream(uri)!!.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        return uri
    }

    @Test
    fun successfulJpegImport_downsamplesCappedAt1024_andPreservesAspectRatio() = runBlocking {
        val uri = createJpegImage(width = 3000, height = 1500) // 2:1 aspect ratio
        val name = store.importImage(uri.toString())
        assertFalse(name.contains("content:"))
        assertTrue(name.startsWith("cover_") && name.endsWith(".jpg"))

        val local = store.resolve(name)
        assertNotNull(local)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(local, bounds)

        // Capped at 1024px maximum edge
        assertTrue("Width must be <= 1024", bounds.outWidth <= 1024)
        assertTrue("Height must be <= 1024", bounds.outHeight <= 1024)
        // 2:1 ratio preserved
        assertEquals(1024, bounds.outWidth)
        assertEquals(512, bounds.outHeight)

        // Survives original URI deletion
        target.contentResolver.delete(uri, null, null)
        createdImages.remove(uri)
        assertTrue(File(local!!).exists() && File(local).length() > 0)

        store.delete(name)
        assertNull(store.resolve(name))
    }

    @Test
    fun successfulPngImport_downsamplesCappedAt1024_andPreservesAspectRatio() = runBlocking {
        val uri = createPngImage(width = 1600, height = 1200) // 4:3 aspect ratio
        val name = store.importImage(uri.toString())
        assertTrue(name.startsWith("cover_") && name.endsWith(".jpg"))

        val local = store.resolve(name)
        assertNotNull(local)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(local, bounds)

        assertEquals(1024, bounds.outWidth)
        assertEquals(768, bounds.outHeight)

        store.delete(name)
        assertNull(store.resolve(name))
    }

    @Test
    fun appliesExifOrientation_beforeStoringBoundedCopy() = runBlocking {
        val uri = createJpegImage(width = 2000, height = 1000)
        target.contentResolver.openFileDescriptor(uri, "rw")!!.use { descriptor ->
            val exif = ExifInterface(descriptor.fileDescriptor)
            exif.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            exif.saveAttributes()
        }

        val name = store.importImage(uri.toString())
        val local = store.resolve(name)
        assertNotNull(local)

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(local, bounds)
        // Original was 2000x1000. Rotated 90 degrees -> 1000x2000. Scaled to max 1024 -> 512x1024.
        assertEquals(512, bounds.outWidth)
        assertEquals(1024, bounds.outHeight)

        store.delete(name)
    }

    @Test
    fun corruptImage_isRejected_andCleansUpTempFiles() = runBlocking {
        val uri = createJpegImage(32, 32)
        target.contentResolver.openOutputStream(uri, "wt")!!.use { it.write("not a valid image content".toByteArray()) }

        try {
            store.importImage(uri.toString())
            fail("Expected decode failure for corrupt image")
        } catch (_: IOException) {
            // Expected
        }

        // Staging directory and covers directory must remain clean
        assertTrue(File(sandbox, "playlist_covers").listFiles().orEmpty().isEmpty())
        val stagingFiles = sandbox.listFiles { file -> file.name.startsWith("import_") || file.name.endsWith(".tmp") }
        assertTrue("Temporary files must be cleaned up on failure", stagingFiles.isNullOrEmpty())
    }

    @Test
    fun oversizedInput_isRejectedWithoutLoadingIntoMemory_andCleansUpTempFiles() = runBlocking {
        val uri = createJpegImage(32, 32)
        target.contentResolver.openOutputStream(uri, "wt")!!.use { stream ->
            val block = ByteArray(32 * 1024)
            repeat(641) { stream.write(block) } // 641 * 32 KB = 20.512 MB > 20 MB cap
        }

        try {
            store.importImage(uri.toString())
            fail("Expected size limit IOException")
        } catch (e: IOException) {
            assertTrue(e.message?.contains("exceeds 20 MB") == true)
        }

        assertTrue(File(sandbox, "playlist_covers").listFiles().orEmpty().isEmpty())
        val stagingFiles = sandbox.listFiles { file -> file.name.startsWith("import_") || file.name.endsWith(".tmp") }
        assertTrue("Temporary files must be cleaned up after oversized failure", stagingFiles.isNullOrEmpty())
    }

    @Test
    fun replacementCreatesIndependentFile_untilOldReferenceIsRemoved() = runBlocking {
        val first = store.importImage(createJpegImage(500, 500).toString())
        val second = store.importImage(createJpegImage(600, 600).toString())

        assertNotEquals(first, second)
        assertNotNull(store.resolve(first))
        assertNotNull(store.resolve(second))

        store.delete(first)
        assertNull(store.resolve(first))
        assertNotNull(store.resolve(second))

        store.delete(second)
        assertNull(store.resolve(second))
    }

    @Test
    fun nonOwnedReferences_cannotBeResolvedOrDeleted() = runBlocking {
        val outside = File(sandbox, "keep.png").apply { writeText("not a cover") }
        assertNull(store.resolve("../keep.png"))
        store.delete("../keep.png")
        assertTrue("Foreign file outside playlist_covers must be preserved", outside.exists())

        assertNull(store.resolve(UUID.randomUUID().toString() + ".png"))
    }
}
