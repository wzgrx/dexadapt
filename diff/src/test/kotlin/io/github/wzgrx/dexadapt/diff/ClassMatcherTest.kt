package io.github.wzgrx.dexadapt.diff

import io.github.wzgrx.dexadapt.dexlib.ClassInfo
import io.github.wzgrx.dexadapt.dexlib.DexIndex
import io.github.wzgrx.dexadapt.dexlib.FieldInfo
import io.github.wzgrx.dexadapt.dexlib.MethodInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClassMatcherTest {
    private fun cls(name: String, superName: String = "java.lang.Object", fields: List<Pair<String, String>> = emptyList(), methods: List<MethodInfo> = emptyList()) =
        ClassInfo(name, superName, emptyList(), 1).apply {
            fields.forEach { (n, t) -> this.fields += FieldInfo(name, n, t, 2) }
            this.methods += methods.map { m -> MethodInfo(name, m.name, m.params, m.returns, m.flags, m.strings, m.calls, m.fieldRefs) }
        }

    private fun m(name: String, params: List<String> = emptyList(), returns: String = "void", strings: Set<String> = emptySet(), calls: Set<String> = emptySet()) =
        MethodInfo("?", name, params, returns, 1, strings, calls, emptySet())

    @Test
    fun `four stages pair renamed classes`() {
        // 旧版：稳定的 Activity；混淆的 Bean（有独特的 toString 字符串）；Bean 的父类（只能靠传播）；一个只有结构的工具类。
        val old = DexIndex.of(listOf(
            cls("com.example.MainActivity", "android.app.Activity", methods = listOf(m("onCreate", listOf("android.os.Bundle")))),
            cls("ab.c", "ab.d", fields = listOf("id" to "int"), methods = listOf(m("toString", returns = "java.lang.String", strings = setOf("FeedBean(id=")))),
            cls("ab.d", methods = listOf(m("a", listOf("int"), "boolean"))),
            cls("ab.e", fields = listOf("x" to "long", "y" to "java.lang.String"), methods = listOf(m("a", listOf("long", "java.lang.String"), "int", calls = setOf("android.util.Log#d(java.lang.String,java.lang.String)int")))),
            cls("ab.gone", methods = listOf(m("b", strings = setOf("only in old version")))),
        ))
        val new = DexIndex.of(listOf(
            cls("com.example.MainActivity", "android.app.Activity", methods = listOf(m("onCreate", listOf("android.os.Bundle")))),
            cls("zq.k", "zq.m", fields = listOf("id" to "int"), methods = listOf(m("toString", returns = "java.lang.String", strings = setOf("FeedBean(id=")))),
            cls("zq.m", methods = listOf(m("b", listOf("int"), "boolean"))),
            cls("zq.n", fields = listOf("x" to "long", "y" to "java.lang.String"), methods = listOf(m("c", listOf("long", "java.lang.String"), "int", calls = setOf("android.util.Log#d(java.lang.String,java.lang.String)int")))),
            cls("zq.fresh", methods = listOf(m("c", strings = setOf("only in new version")))),
        ))
        val r = ClassMatcher(old, new).run()
        val byOld = r.classes.associateBy { it.old }
        assertEquals(ClassPair.How.SAME_NAME, byOld.getValue("com.example.MainActivity").how)
        assertEquals("zq.k", byOld.getValue("ab.c").new)
        assertEquals(ClassPair.How.RARE_STRING, byOld.getValue("ab.c").how)
        assertEquals("zq.m", byOld.getValue("ab.d").new)
        assertEquals(ClassPair.How.PROPAGATED, byOld.getValue("ab.d").how)
        assertEquals("zq.n", byOld.getValue("ab.e").new)
        assertEquals(ClassPair.How.STRUCTURE, byOld.getValue("ab.e").how)
        assertNull("功能删掉的类不硬配", byOld["ab.gone"])
        // 配上的类里按形状唯一对应的方法也配上。
        assertTrue(r.methods.any { it.old.startsWith("ab.e#a(") && it.new.startsWith("zq.n#c(") })
        // ProGuard 映射：旧名 → 新名，方法名一起。
        val mapping = MappingWriter.proguard(r)
        assertTrue(mapping.contains("ab.e -> zq.n:\n    int a(long,java.lang.String) -> c\n"))
        assertFalse("同名的不写", mapping.contains("MainActivity"))
    }

    @Test
    fun `obfuscation heuristics`() {
        assertTrue(Obfuscation.looksObfuscated("sa9.g0"))
        assertTrue(Obfuscation.looksObfuscated("mna.c"))
        assertTrue(Obfuscation.looksObfuscated("com.xingin.a.b"))
        assertTrue(Obfuscation.looksObfuscated("X"))
        assertFalse(Obfuscation.looksObfuscated("com.xingin.matrix.v2.collection.PortfolioService"))
        assertFalse(Obfuscation.looksObfuscated("com.xingin.entities.notedetail.NoteFeed\$Companion"))
        assertFalse(Obfuscation.looksObfuscated("okhttp3.OkHttpClient"))
        assertTrue(Obfuscation.memberObfuscated("a"))
        assertTrue(Obfuscation.memberObfuscated("h2"))
        assertFalse(Obfuscation.memberObfuscated("onCreate"))
    }
}
