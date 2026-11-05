package io.github.wzgrx.dexadapt

import io.github.wzgrx.dexadapt.resolve.DexDigest
import org.junit.Test
import org.junit.Assert.*
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class HostIdentityTest {
    @Test fun `code splits change digest but resource splits preserve single apk compatibility`() {
        val dir = kotlin.io.path.createTempDirectory("host-set").toFile()
        fun apk(name: String, entry: String, bytes: ByteArray) = File(dir, name).apply {
            ZipOutputStream(outputStream()).use { it.putNextEntry(ZipEntry(entry)); it.write(bytes); it.closeEntry() }
        }
        try {
            val base = apk("base.apk", "classes.dex", byteArrayOf(1, 2))
            val resources = apk("config.apk", "res/raw/file", byteArrayOf(3))
            val feature = apk("feature.apk", "classes.dex", byteArrayOf(4, 5))
            assertEquals(DexDigest.of(base), DexDigest.of(listOf(base, resources)))
            val old = DexDigest.of(listOf(base, feature))
            assertEquals(DexDigest.combine(listOf(DexDigest.of(base), DexDigest.of(feature))), old)
            apk("feature.apk", "classes.dex", byteArrayOf(4, 6))
            assertNotEquals(old, DexDigest.of(listOf(base, feature)))
            assertNotEquals(DexDigest.of(listOf(base, feature)), DexDigest.of(listOf(feature, base)))
            assertNotEquals(HostIdentity.installation("cn.app", base, listOf(resources), 1), HostIdentity.installation("play.app", base, listOf(resources), 1))
            assertNotEquals(HostIdentity.installation("cn.app", base, listOf(resources), 1), HostIdentity.installation("cn.app", base, listOf(resources, feature), 1))
        } finally { dir.deleteRecursively() }
    }
}
