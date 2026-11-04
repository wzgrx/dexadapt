package io.github.wzgrx.dexadapt
import io.github.wzgrx.dexadapt.spec.SpecLint
import org.junit.Test
import org.junit.Assert.*
class LibraryFragilityTest {
    @Test fun `kotlin implementation names produce a stability warning`() {
        val cls=ClassSymbol("repo",ClassQuery(fieldTypes=listOf(type("kotlin.Lazy"))))
        val method=MethodSymbol("equal",MethodQuery(owner=type("Account"),callsExact=listOf("kotlin.jvm.internal.Intrinsics#areEqual")))
        assertEquals(2,SpecLint.check(Catalog(listOf(cls,method))).count { it.message.contains("Kotlin库实现名") })
    }
}
