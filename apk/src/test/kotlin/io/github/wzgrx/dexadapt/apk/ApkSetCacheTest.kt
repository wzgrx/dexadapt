package io.github.wzgrx.dexadapt.apk

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ApkSetCacheTest {
    @get:Rule val temp = TemporaryFolder()

    private fun bundle(file: File, vararg files: Pair<String, ByteArray>) {
        ZipOutputStream(file.outputStream()).use { zip ->
            for ((name, bytes) in files) {
                zip.putNextEntry(ZipEntry(name).apply {
                    method = ZipEntry.STORED
                    size = bytes.size.toLong()
                    crc = CRC32().apply { update(bytes) }.value
                })
                zip.write(bytes)
                zip.closeEntry()
            }
        }
    }

    @Test fun `same filename and length do not reuse old members`() {
        val dir = temp.newFolder()
        val cache = temp.newFolder()
        for (extension in listOf("apks", "xapk")) {
            val archive = File(dir, "host.$extension")
            val old = byteArrayOf(1, 2, 3)
            val new = byteArrayOf(3, 2, 1)
            bundle(archive, "base.apk" to old, "split.apk" to byteArrayOf(0))
            val size = archive.length()
            val before = ApkSet.open(archive, cache)
            val modified = archive.lastModified()
            bundle(archive, "base.apk" to new, "split.apk" to byteArrayOf(0))
            archive.setLastModified(modified)
            assertEquals(size, archive.length())
            val after = ApkSet.open(archive, cache)
            assertNotEquals(before.base, after.base)
            assertArrayEquals(old, before.base.readBytes())
            assertArrayEquals(new, after.base.readBytes())
        }
    }

    @Test fun `same size corruption and missing split are repaired`() {
        val cache = temp.newFolder()
        val archive = temp.newFile("test.xapk")
        val expected = byteArrayOf(1, 2, 3)
        val split = byteArrayOf(4, 5, 6)
        bundle(archive, "split.apk" to split, "base.apk" to expected)
        val opened = ApkSet.open(archive, cache)
        val damaged = opened.files.last()
        opened.base.writeBytes(byteArrayOf(3, 3, 3))
        damaged.delete()
        val again = ApkSet.open(archive, cache)
        assertEquals(opened.files, again.files)
        assertArrayEquals(expected, again.base.readBytes())
        assertArrayEquals(split, again.files.last().readBytes())
    }

    @Test fun `ambiguous or non portable member names are rejected`() {
        val archive = temp.newFile("test.apks")
        val cache = temp.newFolder()
        val payload = byteArrayOf(1)
        bundle(archive, "base.apk" to payload, "BASE.apk" to payload)
        assertThrows(IllegalArgumentException::class.java) { ApkSet.open(archive, cache) }
        bundle(archive, "base.apk" to payload, "..\\split.apk" to payload)
        assertThrows(IllegalArgumentException::class.java) { ApkSet.open(archive, cache) }
        assertEquals(emptyList<File>(), cache.listFiles().orEmpty().toList())
    }
}
