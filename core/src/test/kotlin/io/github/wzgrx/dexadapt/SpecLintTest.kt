package io.github.wzgrx.dexadapt

import io.github.wzgrx.dexadapt.spec.SpecLint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpecLintTest {
    private object G : SymbolGroup("g") {
        val good = cls("good", ClassQuery(strings = listOf("FeedAdapter(position=")))
        val obf = cls("obf", ClassQuery(name = "a.b.c"))
        val shape = cls("shape", ClassQuery(superclass = type("android.view.View")))
        val scan = method("scan", MethodQuery(shape = MethodShape(params = listOf(type("int")), returns = type("void"))))
        val staticCtor = method("ctor", MethodQuery(owner = TypeRef.Of(good), shape = MethodShape(name = "<init>", isStatic = true)))
        val bareField = field("bare", FieldQuery(TypeRef.Of(good)))
        val twin = cls("twin", ClassQuery(strings = listOf("FeedAdapter(position=")))
        val realShort = cls("tag", ClassQuery(name = "com.xingin.entities.Tag"))
        val gsonId = field("id", FieldQuery(TypeRef.Of(realShort), name = "id"))
        val obfField = field("f", FieldQuery(TypeRef.Of(realShort), name = "b"))
    }

    @Test
    fun `static checks`() {
        val issues = SpecLint.check(Catalog(G))
        fun has(level: SpecLint.Level, id: String, text: String) = assertTrue(issues.joinToString("\n"), issues.any { it.level == level && it.id == id && text in it.message })
        has(SpecLint.Level.WARNING, "g.obf", "混淆名")
        has(SpecLint.Level.WARNING, "g.shape", "只靠形状")
        has(SpecLint.Level.WARNING, "g.scan", "扫全部方法")
        has(SpecLint.Level.ERROR, "g.ctor", "静态")
        has(SpecLint.Level.ERROR, "g.bare", "不可能唯一")
        has(SpecLint.Level.HINT, "g.good", "一模一样")
        assertTrue(issues.none { it.id == "g.good" && it.level == SpecLint.Level.ERROR })
        assertTrue(issues.none { it.id == "g.tag" && it.level == SpecLint.Level.WARNING })
        assertTrue(issues.joinToString(), issues.none { it.id == "g.id" })
        has(SpecLint.Level.WARNING, "g.f", "混淆的字段名")
    }

    @Test
    fun `checks against a resolved table`() {
        val c = Resolved.Class("x.Y")
        val table = SymbolTable(1, "", mapOf("g.good" to c, "g.twin" to c), mapOf("g.shape" to "no match"))
        val issues = SpecLint.check(Catalog(G), table, "1.0")
        assertEquals(1, issues.count { "同一个成员" in it.message && it.level == SpecLint.Level.WARNING })
        assertTrue(issues.any { it.id == "g.shape" && it.level == SpecLint.Level.ERROR })
    }
}
