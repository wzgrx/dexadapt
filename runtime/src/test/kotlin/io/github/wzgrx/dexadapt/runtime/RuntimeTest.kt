package io.github.wzgrx.dexadapt.runtime

import io.github.wzgrx.dexadapt.ClassQuery
import io.github.wzgrx.dexadapt.ClassSymbol
import io.github.wzgrx.dexadapt.FieldQuery
import io.github.wzgrx.dexadapt.FieldSymbol
import io.github.wzgrx.dexadapt.MethodQuery
import io.github.wzgrx.dexadapt.MethodSymbol
import io.github.wzgrx.dexadapt.Resolved
import io.github.wzgrx.dexadapt.SymbolTable
import io.github.wzgrx.dexadapt.TypeRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeTest {
    private fun table(catalog: String = "", apk: String = "", sha: String = "d") = SymbolTable(1, sha, emptyMap(), catalog = catalog, apk = apk)

    @Test
    fun `host process picks local, bundled, stale local, then nothing`() {
        val bundled = table()
        val local = table(catalog = "c1", apk = "a")
        assertEquals(AdaptPolicy.Source.LOCAL, AdaptPolicy.choose(local, bundled, "c1", "a").source)
        // 本机表是旧目录生成的：有内置表用内置表；没有时用旧表并请求重扫
        assertEquals(AdaptPolicy.Source.BUNDLED, AdaptPolicy.choose(local, bundled, "c2", "a").source)
        AdaptPolicy.choose(local, null, "c2", "a").let { assertEquals(AdaptPolicy.Source.STALE_LOCAL, it.source); assertTrue(it.needsAdaptation) }
        // 安装包对不上的本机表不用
        assertEquals(AdaptPolicy.Source.NONE, AdaptPolicy.choose(local, null, "c1", "b").source)
        // 读不到安装包身份时不核对
        assertEquals(AdaptPolicy.Source.LOCAL, AdaptPolicy.choose(local, null, "c1", null).source)
    }

    @Test
    fun `app decides when to scan and only computes the digest when needed`() {
        var computed = 0
        val digest = { computed++; "d" }
        assertEquals(AdaptPolicy.Decision.Skip(clearLocal = true), AdaptPolicy.decide("X 1.0", table(sha = "d"), null, "c", "a", digest))
        assertTrue(AdaptPolicy.decide("X 1.0", table(sha = "other"), null, "c", "a", digest) is AdaptPolicy.Decision.Scan)
        assertEquals(AdaptPolicy.Decision.Skip(false), AdaptPolicy.decide("X 1.0", table(sha = "other"), table("c", "a"), "c", "a", digest))
        assertEquals(3, computed)
        // 没有内置表：按本机表的指纹和安装包判断，不算摘要
        assertTrue((AdaptPolicy.decide("X 1.0", null, null, "c", "a", digest) as AdaptPolicy.Decision.Scan).reason.contains("没有内置适配"))
        assertTrue((AdaptPolicy.decide("X 1.0", null, table("old", "a"), "c", "a", digest) as AdaptPolicy.Decision.Scan).reason.contains("符号定义变了"))
        assertTrue((AdaptPolicy.decide("X 1.0", null, table("c", "b"), "c", "a", digest) as AdaptPolicy.Decision.Scan).reason.contains("安装包变了"))
        assertEquals(AdaptPolicy.Decision.Skip(false), AdaptPolicy.decide("X 1.0", null, table("c", "a"), "c", "a", digest))
        assertEquals(3, computed)
        assertTrue((AdaptPolicy.decide("X 1.0", null, table("c", "a").copy(partial = true), "c", "a", digest) as AdaptPolicy.Decision.Scan).reason.contains("没做完"))
        val stamped = AdaptPolicy.stamp(table(), "c", "1.0", "a", 3, "why", now = 5)
        assertEquals(listOf("c", "dexkit", "1.0", "a"), listOf(stamped.catalog, stamped.generator, stamped.module, stamped.apk))
    }

    @Test
    fun `self check compares entry by entry`() {
        val a = Resolved.Class("x.A")
        val bundled = SymbolTable(1, "", mapOf("s1" to a, "s2" to Resolved.Class("x.B"), "s3" to Resolved.Class("x.C")))
        val local = SymbolTable(1, "", mapOf("s1" to a, "s2" to Resolved.Class("x.Z"), "s4" to Resolved.Class("x.D")), mapOf("s3" to "no match"))
        val r = AdaptPolicy.compare(bundled, local, 3)
        assertEquals(1, r.same)
        assertEquals(listOf("s2"), r.different)
        assertEquals(listOf("s3"), r.onlyBundled)
        assertEquals(listOf("s4"), r.onlyLocal)
    }

    @Test
    fun `linker turns table entries into reflection and explains failures`() {
        val cls = ClassSymbol("t.string", ClassQuery(name = "io.github.wzgrx.dexadapt.runtime.Sample"))
        val length = MethodSymbol("t.length", MethodQuery(owner = TypeRef.Of(cls)))
        val hash = FieldSymbol("t.hash", FieldQuery(TypeRef.Of(cls)))
        val gone = MethodSymbol("t.gone", MethodQuery(owner = TypeRef.Of(cls)))
        val wrong = MethodSymbol("t.wrong", MethodQuery(owner = TypeRef.Of(cls)))
        val t = SymbolTable(
            1, "", mapOf(
                "t.string" to Resolved.Class("io.github.wzgrx.dexadapt.runtime.Sample"),
                "t.length" to Resolved.Method("io.github.wzgrx.dexadapt.runtime.Sample", "size", emptyList(), "int"),
                "t.hash" to Resolved.Field("io.github.wzgrx.dexadapt.runtime.Sample", "count", "int"),
                "t.wrong" to Resolved.Method("io.github.wzgrx.dexadapt.runtime.Sample", "size", emptyList(), "long"),
            ),
            missing = mapOf("t.gone" to "no match"),
        )
        val logged = ArrayList<String>()
        val linker = SymbolLinker(t, javaClass.classLoader, log = { logged += it })
        assertEquals(Sample::class.java, linker.clazz(cls))
        assertEquals("size", linker.method(length).name)
        assertEquals("count", linker.field(hash).name)
        assertNull(linker.problem(length))
        assertTrue(linker.problem(gone)!!.contains("no match"))
        assertTrue(linker.problem(wrong)!!.contains("return type changed"))
        assertTrue(logged.any { it.contains("t.wrong") })
        // 没有表时全部降级；热替换后可用
        val empty = SymbolLinker(null, javaClass.classLoader, unsupported = "此版本未适配")
        assertEquals("t.string: 此版本未适配", empty.problem(cls))
        empty.replace(t)
        assertNull(empty.problem(cls))
    }
}

/** 测试用的“宿主类”。 */
@Suppress("unused")
class Sample {
    private var count: Int = 0
    private fun size(): Int = count
}
