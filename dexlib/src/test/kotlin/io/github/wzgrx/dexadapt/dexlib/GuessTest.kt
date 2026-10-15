package io.github.wzgrx.dexadapt.dexlib

import io.github.wzgrx.dexadapt.ClassQuery
import io.github.wzgrx.dexadapt.ClassSymbol
import io.github.wzgrx.dexadapt.FieldQuery
import io.github.wzgrx.dexadapt.FieldSymbol
import io.github.wzgrx.dexadapt.MethodQuery
import io.github.wzgrx.dexadapt.MethodShape
import io.github.wzgrx.dexadapt.MethodSymbol
import io.github.wzgrx.dexadapt.Resolved
import io.github.wzgrx.dexadapt.SymbolTable
import io.github.wzgrx.dexadapt.TypeRef
import io.github.wzgrx.dexadapt.resolve.SymbolResolver
import io.github.wzgrx.dexadapt.type
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 分级回退：查询坏了（新版改了一个字符串、多了一个同类型字段）时推测位置。 */
class GuessTest {
    private val x = ClassInfo("a.X", "java.lang.Object", emptyList(), 1).apply {
        methods += MethodInfo("a.X", "m", listOf("java.lang.String"), "void", 1, setOf("load feed", "v2 render"), emptySet(), emptySet())
        methods += MethodInfo("a.X", "n", listOf("int"), "void", 1, setOf("other"), emptySet(), emptySet())
        fields += FieldInfo("a.X", "f", "int", 2)
        fields += FieldInfo("a.X", "g", "int", 2)
    }
    private val owner = ClassSymbol("t.x", ClassQuery(name = "a.X"))
    // 旧版的字符串是 "v1 render"，新版改成了 "v2 render"
    private val m = MethodSymbol("t.m", MethodQuery(owner = TypeRef.Of(owner), shape = MethodShape(strings = listOf("load feed", "v1 render"))))
    // 旧版只有一个 int 字段，新版多了一个
    private val f = FieldSymbol("t.f", FieldQuery(TypeRef.Of(owner), type = type("int")))
    private val nothing = MethodSymbol("t.none", MethodQuery(owner = TypeRef.Of(owner), shape = MethodShape(strings = listOf("gone"))))

    @Test
    fun `relaxing one condition or reusing the old location gives a guess`() {
        val r = SymbolResolver(DexIndex.of(listOf(x)), 2)
        val g = r.guess(m)!!
        assertEquals(Resolved.Method("a.X", "m", listOf("java.lang.String"), "void"), g.resolved)
        assertTrue(g.how, g.how.contains("v1 render"))
        assertNull(r.guess(f))
        val old = Resolved.Field("a.X", "f", "int")
        assertEquals(old, r.guess(f, listOf(Resolved.Field("a.X", "zz", "int"), old))!!.resolved)
        assertNull(r.guess(nothing))
        assertNull(r.guess(owner)) // 解析得到的不推测

        val table = r.withGuesses(r.table(listOf(owner, m, f, nothing)), listOf(owner, m, f, nothing)) { id -> if (id == "t.f") listOf(old) else emptyList() }
        assertEquals(setOf("t.m", "t.f"), table.guesses.keys)
        assertTrue("t.m" in table.missing && "t.m" !in table.entries)
        assertEquals(table, SymbolTable.fromJson(table.toJson()))
    }

    @Test
    fun `stages hand out partial tables, then the full one`() {
        val r = SymbolResolver(DexIndex.of(listOf(x)), 2)
        val seen = ArrayList<SymbolTable>()
        val full = r.tableInStages(listOf(listOf(owner), listOf(m, f, nothing)), { seen += it })
        assertEquals(1, seen.size)
        assertTrue(seen[0].partial)
        assertEquals(setOf("t.x"), seen[0].entries.keys)
        assertTrue(!full.partial && full.entries.keys == setOf("t.x") && full.missing.keys == setOf("t.m", "t.f", "t.none"))
        assertEquals(r.table(listOf(owner, m, f, nothing)), full)
    }
}
