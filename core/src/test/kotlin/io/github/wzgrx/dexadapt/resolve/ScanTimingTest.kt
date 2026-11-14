package io.github.wzgrx.dexadapt.resolve

import io.github.wzgrx.dexadapt.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.zip.ZipOutputStream
import java.util.zip.ZipEntry
import java.security.MessageDigest

class ScanTimingTest {
    @get:Rule val temp = TemporaryFolder()
    @Test fun timedDigestPreservesTheOriginalOrderedDexBytesAndIgnoresResources() {
        val apk = temp.newFile("sample.apk")
        ZipOutputStream(apk.outputStream()).use { zip ->
            for ((name,body) in listOf("classes10.dex" to "c", "resource.txt" to "skip", "classes2.dex" to "b", "classes.dex" to "a")) {
                zip.putNextEntry(ZipEntry(name));zip.write(body.toByteArray());zip.closeEntry()
            }
        }
        val observed = ArrayList<ScanTiming.Sample>()
        val timing = ScanTiming(emit = observed::add)
        val timed = DexDigest.of(listOf(apk), timing)
        val wanted = Hex.of(MessageDigest.getInstance("SHA-256").digest("abc".toByteArray()))
        assertEquals(wanted,timed)
        assertEquals(DexDigest.of(apk),timed)
        assertEquals(setOf("zip_open","dex_entry_enumerate","zip_read_inflate","sha256_update"),observed.map { it.phase }.toSet())
        assertTrue(observed.all { it.elapsedNanos >= 0 })
    }
    @Test fun symbolsHaveInclusiveAndSelfTimesAndCachedResultsAreExplicit() {
        val cls = ClassSymbol("owner", ClassQuery(name="example.Owner"))
        val method = MethodSymbol("method", MethodQuery(owner=TypeRef.Of(cls),shape=MethodShape(name="run")))
        val source = FakeSource(listOf(FakeClass("example.Owner").method("run")))
        var clock = 0L
        val samples = ArrayList<ScanTiming.Sample>()
        val resolver = SymbolResolver(source,1).withTiming(ScanTiming(clock={ ++clock },emit=samples::add))
        assertTrue(resolver.resolve(method).isSuccess)
        assertTrue(resolver.resolve(method).isSuccess)
        val calls=samples.filter { it.phase=="symbol_resolve" }
        assertEquals(setOf("owner","method"),calls.map { it.symbol }.toSet())
        assertTrue(calls.last().cached)
        assertTrue(calls.all { it.selfNanos != null && it.selfNanos <= it.elapsedNanos })
        val expected=SymbolResolver(source,1).table(listOf(cls,method))
        assertEquals(expected,resolver.table(listOf(cls,method)))
    }
    @Test fun failingObserverCannotBreakResolution() {
        val cls=ClassSymbol("owner", ClassQuery(name="example.Owner"))
        val resolver=SymbolResolver(FakeSource(listOf(FakeClass("example.Owner"))),1)
            .withTiming(ScanTiming(emit={error("collector failed")}))
        assertTrue(resolver.resolve(cls).isSuccess)
    }
}
