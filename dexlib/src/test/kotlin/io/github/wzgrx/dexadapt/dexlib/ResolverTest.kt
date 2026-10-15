package io.github.wzgrx.dexadapt.dexlib

import io.github.wzgrx.dexadapt.resolve.SymbolResolver
import io.github.wzgrx.dexadapt.resolve.forBuild
import io.github.wzgrx.dexadapt.ClassQuery
import io.github.wzgrx.dexadapt.ClassSymbol
import io.github.wzgrx.dexadapt.FieldQuery
import io.github.wzgrx.dexadapt.FieldSymbol
import io.github.wzgrx.dexadapt.Resolved
import io.github.wzgrx.dexadapt.TypeRef
import io.github.wzgrx.dexadapt.type
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ResolverTest {
    private fun cls(name: String, superName: String? = "java.lang.Object", fields: List<Pair<String, String>> = emptyList()) =
        ClassInfo(name, superName, emptyList(), 0).apply { fields.forEach { (n, t) -> this.fields += FieldInfo(name, n, t, 0) } }

    @Test
    fun `SuperOf resolves members declared on an obfuscated base class`() {
        val index = DexIndex.of(
            listOf(
                cls("x.Base", fields = listOf("c" to "x.Adapter")),
                cls("x.Child", superName = "x.Base"),
                cls("x.Adapter"),
            ),
        )
        val child = ClassSymbol("t.child", ClassQuery(name = "x.Child"))
        val field = FieldSymbol("t.adapter", FieldQuery(TypeRef.SuperOf(child), type = type("x.Adapter")))
        val resolver = SymbolResolver.forBuild(index, 9491803, "CN_9491803")
        assertEquals(Resolved.Field("x.Base", "c", "x.Adapter"), resolver.resolve(field).getOrThrow())
    }

    @Test
    fun `SuperOf fails cleanly when the superclass is outside the APK`() {
        val index = DexIndex.of(listOf(cls("x.Child", superName = null)))
        val child = ClassSymbol("t.child", ClassQuery(name = "x.Child"))
        val field = FieldSymbol("t.f", FieldQuery(TypeRef.SuperOf(child), name = "a"))
        assertTrue(SymbolResolver.forBuild(index, 9491803, "CN_9491803").resolve(field).isFailure)
    }
}
