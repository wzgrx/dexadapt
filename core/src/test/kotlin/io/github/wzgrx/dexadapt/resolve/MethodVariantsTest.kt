package io.github.wzgrx.dexadapt.resolve

import io.github.wzgrx.dexadapt.Catalog
import io.github.wzgrx.dexadapt.ClassQuery
import io.github.wzgrx.dexadapt.ClassSymbol
import io.github.wzgrx.dexadapt.MethodQuery
import io.github.wzgrx.dexadapt.MethodShape
import io.github.wzgrx.dexadapt.MethodSymbol
import io.github.wzgrx.dexadapt.MethodVariant
import io.github.wzgrx.dexadapt.Pin
import io.github.wzgrx.dexadapt.Resolved
import io.github.wzgrx.dexadapt.TypeRef
import io.github.wzgrx.dexadapt.pin
import io.github.wzgrx.dexadapt.spec.Spec
import io.github.wzgrx.dexadapt.type
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MethodVariantsTest {
    private val digest = "a".repeat(64)
    private val source = object : SymbolSource by FakeSource(listOf(
        FakeClass("app.Menu").method("open", listOf("int")).method("open", listOf("boolean")),
    )) {
        override val dexSha256 = digest
    }
    private val old = MethodShape(name = "open", params = listOf(type("int")))
    private val new = MethodShape(name = "open", params = listOf(type("boolean")))
    private fun method(vararg variants: MethodVariant, shape: MethodShape = old) = MethodSymbol(
        "menu.open", MethodQuery(owner = type("app.Menu"), shape = shape, variants = variants.toList()),
    )
    private fun result(method: MethodSymbol, build: String? = null) =
        SymbolResolver.forBuild(source, 1, build).resolve(method)

    @Test fun `unqualified variants deduplicate one method but refuse competing methods`() {
        assertEquals(listOf("int"), (result(method(MethodVariant(old))).getOrThrow() as Resolved.Method).params)
        val error = result(method(MethodVariant(new))).exceptionOrNull()!!.message!!
        assertTrue(error, error.contains("2 matches") && error.contains("shape=1") && error.contains("variants[0]=1"))
        assertTrue(error, error.contains("app.Menu#open(int)") && error.contains("app.Menu#open(boolean)"))
        assertEquals(listOf("boolean"), (result(method(MethodVariant(new), shape = MethodShape())).getOrThrow() as Resolved.Method).params)
    }

    @Test fun `digest overrides build and pins still match selected shape`() {
        val method = method(MethodVariant(new, setOf("CN_1")), MethodVariant(old, dexSha256 = setOf(digest)))
        assertEquals(listOf("int"), (result(method, "CN_1").getOrThrow() as Resolved.Method).params)
        method.pin("CN_1", Pin.Method("app.Menu", "open", listOf("boolean")))
        assertTrue(result(method, "CN_1").exceptionOrNull()!!.message!!.contains("does not match"))
        val byBuild = method(MethodVariant(new, setOf("CN_1"))).pin("CN_1", Pin.Method("app.Menu", "open", listOf("boolean")))
        assertEquals(listOf("boolean"), (result(byBuild, "CN_1").getOrThrow() as Resolved.Method).params)
    }

    @Test fun `selected branch excludes inactive dependencies while unknown build fails closed`() {
        val absent = ClassSymbol("menu.dependency", ClassQuery(name = "not.InApk"))
        val method = method(
            MethodVariant(new.copy(params = listOf(TypeRef.Of(absent)))),
            MethodVariant(old, setOf("CN_1")),
        )
        assertTrue(result(method, "CN_1").isSuccess)
        val failure = result(method).exceptionOrNull() as SymbolResolver.Unresolved
        assertEquals(absent.id, failure.dependencyId)
        val original = Catalog(listOf(absent, method))
        val decoded = Spec.fromYaml(Spec(null, original).toYaml()).catalog
        assertEquals(original.fingerprint(), decoded.fingerprint())
        assertEquals(listOf(absent.id), SymbolGraph.dependencies(decoded.symbols.last()).map { it.second?.id })
        assertTrue(Catalog(listOf(method())).fingerprint() != Catalog(listOf(method(MethodVariant(new)))).fingerprint())
    }
}
