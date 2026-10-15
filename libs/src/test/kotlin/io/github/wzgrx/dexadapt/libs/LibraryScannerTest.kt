package io.github.wzgrx.dexadapt.libs

import io.github.wzgrx.dexadapt.dexlib.ClassInfo
import io.github.wzgrx.dexadapt.dexlib.DexIndex
import io.github.wzgrx.dexadapt.dexlib.MethodInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryScannerTest {
    private fun cls(name: String, vararg strings: String) = ClassInfo(name, "java.lang.Object", emptyList(), 1).apply {
        methods += MethodInfo(name, "m", emptyList(), "void", 1, strings.toSet(), emptySet(), emptySet())
    }

    private val v1 = listOf(
        cls("lib.Client", "Client closed!", "timeout == null", "unexpected url: "),
        cls("lib.Cache", "cache dir missing: ", "journal is corrupt"),
        cls("lib.Util", "UTF-8 BOM", "Expected leading [0-9a-fA-F] character but was 0x"),
    )
    private val v2 = listOf(
        cls("lib.Client", "Client closed!", "timeout == null", "unexpected url: ", "HTTP/2 only"),
        cls("lib.Cache", "cache dir missing: ", "journal is corrupt (v2)"),
        cls("lib.Util", "UTF-8 BOM", "Expected leading [0-9a-fA-F] character but was 0x"),
    )
    private val db = LibraryDb(libraries = listOf(LibraryDb.build("g:lib", "Lib", listOf("1.0" to v1, "2.0" to v2))))

    @Test
    fun `identifies the version and maps renamed classes back`() {
        val app = DexIndex.of(
            listOf(
                cls("a.b", "Client closed!", "timeout == null", "unexpected url: ", "HTTP/2 only"),
                cls("a.c", "cache dir missing: ", "journal is corrupt (v2)"),
                cls("a.d", "UTF-8 BOM", "Expected leading [0-9a-fA-F] character but was 0x"),
                cls("com.app.Main", "hello world!"),
            ),
        )
        val found = LibraryScanner(db, minHits = 3).scan(app)
        assertEquals(1, found.size)
        val d = found.single()
        assertEquals(listOf("2.0"), d.versions)
        assertEquals(0, d.against)
        assertEquals(mapOf("lib.Client" to "a.b", "lib.Cache" to "a.c", "lib.Util" to "a.d"), d.classes.associate { it.library to it.apk })
        assertEquals(mapOf("lib" to "a"), d.packages)
    }

    @Test
    fun `an app without the library finds nothing`() {
        val app = DexIndex.of(listOf(cls("com.app.Main", "UTF-8 BOM", "hello world!")))
        assertTrue(LibraryScanner(db, minHits = 3).scan(app).isEmpty())
    }

    @Test
    fun `db round trips through gzip json`() {
        val f = java.io.File.createTempFile("libs", ".json.gz")
        try {
            db.write(f)
            assertEquals(db, LibraryDb.read(f))
        } finally {
            f.delete()
        }
    }

    @Test
    fun `picks the last patch of each minor version`() {
        val e = KnownLibraries.Entry("g", "a", "A", "3.0.0")
        val published = listOf("2.9.0", "3.0.0", "3.0.1", "3.1.0", "3.1.2-RC1", "3.1.1", "3.2.0", "4.0.0-alpha01", "4.0.0", "4.0.3")
        assertEquals(listOf("3.0.1", "3.1.1", "3.2.0", "4.0.3"), KnownLibraries.pick(e, published))
        assertEquals(listOf("1.2.40", "1.2.83"), KnownLibraries.pick(KnownLibraries.Entry("g", "f", "F"), listOf("1.2.40", "1.2.83")))
    }
}
