package io.github.wzgrx.dexadapt.dexlib

import io.github.wzgrx.dexadapt.resolve.SymbolResolver
import io.github.wzgrx.dexadapt.resolve.forBuild
import io.github.wzgrx.dexadapt.ClassQuery
import io.github.wzgrx.dexadapt.ClassSymbol
import io.github.wzgrx.dexadapt.MethodQuery
import io.github.wzgrx.dexadapt.MethodShape
import io.github.wzgrx.dexadapt.MethodSymbol
import io.github.wzgrx.dexadapt.Resolved
import io.github.wzgrx.dexadapt.TypeRef
import io.github.wzgrx.dexadapt.type
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 不靠按版本钉死也能区分形状相同的成员（新版本在手机上本机适配时钉子不起作用）。 */
class ResolverNoPinTest {
    private fun iface(name: String) = ClassInfo(name, "java.lang.Object", emptyList(), 0x200 or 0x400).apply {
        methods += MethodInfo(name, "a", listOf("android.widget.TextView", "int", "int"), "boolean", 0x400, emptySet(), emptySet(), emptySet())
    }

    @Test
    fun `fieldOf picks the interface the holder actually uses`() {
        val layout = ClassInfo("sel.Layout", "android.widget.FrameLayout", emptyList(), 0).apply {
            fields += FieldInfo(name, "f", "sel.b", 0)
        }
        val index = DexIndex.of(listOf(layout, iface("sel.b"), iface("other.c")))
        val shape = MethodShape(params = listOf(type("android.widget.TextView"), type("int"), type("int")), returns = type("boolean"))
        val holder = ClassSymbol("t.layout", ClassQuery(name = "sel.Layout"))
        val loose = ClassSymbol("t.loose", ClassQuery(isInterface = true, packagePrefix = null, methods = listOf(shape), superclass = type("java.lang.Object")))
        val exact = ClassSymbol("t.exact", ClassQuery(isInterface = true, fieldOf = TypeRef.Of(holder), methods = listOf(shape)))
        val r = SymbolResolver.forBuild(index, 9491803, "CN_9491803")
        assertTrue(r.resolve(loose).exceptionOrNull()!!.message!!.startsWith("2 matches"))
        assertEquals(Resolved.Class("sel.b"), r.resolve(exact).getOrThrow())
    }

    @Test
    fun `excludes leaves the one sibling that is not already known`() {
        val f = ClassInfo("loc.F", "java.lang.Object", emptyList(), 0).apply {
            fields += FieldInfo(name, "flag", "boolean", 0)
            methods += MethodInfo(name, "c", emptyList(), "loc.B", 0, emptySet(), emptySet(), setOf("loc.F#flag"))
            methods += MethodInfo(name, "h", emptyList(), "loc.B", 0, emptySet(), emptySet(), emptySet())
        }
        val r = SymbolResolver.forBuild(DexIndex.of(listOf(f)), 9491803, "CN_9491803")
        val owner = ClassSymbol("t.f", ClassQuery(name = "loc.F"))
        val flag = io.github.wzgrx.dexadapt.FieldSymbol("t.flag", io.github.wzgrx.dexadapt.FieldQuery(TypeRef.Of(owner), name = "flag"))
        val current = MethodSymbol("t.current", MethodQuery(owner = TypeRef.Of(owner), shape = MethodShape(params = emptyList(), returns = type("loc.B")), usesFields = listOf(flag)))
        val gps = MethodSymbol("t.gps", MethodQuery(owner = TypeRef.Of(owner), shape = MethodShape(params = emptyList(), returns = type("loc.B")), excludes = listOf(current)))
        assertEquals("c", (r.resolve(current).getOrThrow() as Resolved.Method).name)
        assertEquals("h", (r.resolve(gps).getOrThrow() as Resolved.Method).name)
    }
}
