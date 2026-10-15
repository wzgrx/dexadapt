package io.github.wzgrx.dexadapt.dexlib

import io.github.wzgrx.dexadapt.resolve.SymbolResolver
import io.github.wzgrx.dexadapt.resolve.forBuild
import io.github.wzgrx.dexadapt.ClassQuery
import io.github.wzgrx.dexadapt.ClassSymbol
import io.github.wzgrx.dexadapt.FieldQuery
import io.github.wzgrx.dexadapt.FieldSymbol
import io.github.wzgrx.dexadapt.MethodQuery
import io.github.wzgrx.dexadapt.MethodShape
import io.github.wzgrx.dexadapt.MethodSymbol
import io.github.wzgrx.dexadapt.Resolved
import io.github.wzgrx.dexadapt.TypeRef
import io.github.wzgrx.dexadapt.type
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ResolverQueryTest {
    /** A bean with a plain getter `g()` and a `toString()` that reads every field. */
    private val bean = ClassInfo("a.b", "java.lang.Object", emptyList(), 0).apply {
        fields += FieldInfo(name, "type", "java.lang.String", 0)
        fields += FieldInfo(name, "link", "java.lang.String", 0)
        methods += MethodInfo(name, "g", emptyList(), "java.lang.String", 0, emptySet(), emptySet(), setOf("a.b#type"))
        methods += MethodInfo(name, "h", emptyList(), "java.lang.String", 0, emptySet(), emptySet(), setOf("a.b#link"))
        methods += MethodInfo(name, "toString", emptyList(), "java.lang.String", 0, setOf("Bean(type="), emptySet(), setOf("a.b#type", "a.b#link"))
    }
    private val resolver = SymbolResolver.forBuild(DexIndex.of(listOf(bean)), 9491803, "CN_9491803")
    private val cls = ClassSymbol("t.bean", ClassQuery(strings = listOf("Bean(type=")))
    private val typeField = FieldSymbol("t.type", FieldQuery(owner = TypeRef.Of(cls), name = "type"))

    private fun getter(only: Boolean) = MethodSymbol(
        "t.getter.$only",
        MethodQuery(
            owner = TypeRef.Of(cls),
            shape = MethodShape(params = emptyList(), returns = type("java.lang.String")),
            usesFields = listOf(typeField),
            onlyTheseFields = only,
        ),
    )

    @Test
    fun `onlyTheseFields tells a getter from toString`() {
        assertTrue(resolver.resolve(getter(false)).isFailure)
        assertEquals(Resolved.Method("a.b", "g", emptyList(), "java.lang.String"), resolver.resolve(getter(true)).getOrThrow())
    }

    @Test
    fun `a class can be found by a declared method shape alone`() {
        val byShape = ClassSymbol("t.byShape", ClassQuery(methods = listOf(MethodShape(name = "toString", params = emptyList()))))
        assertEquals(Resolved.Class("a.b"), resolver.resolve(byShape).getOrThrow())
    }
}
