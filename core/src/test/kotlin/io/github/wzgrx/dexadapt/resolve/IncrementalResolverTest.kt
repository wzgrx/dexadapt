package io.github.wzgrx.dexadapt.resolve

import io.github.wzgrx.dexadapt.ClassQuery
import io.github.wzgrx.dexadapt.ClassSymbol
import io.github.wzgrx.dexadapt.MethodQuery
import io.github.wzgrx.dexadapt.MethodShape
import io.github.wzgrx.dexadapt.MethodSymbol
import io.github.wzgrx.dexadapt.Resolved
import io.github.wzgrx.dexadapt.TypeRef
import io.github.wzgrx.dexadapt.absentIn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class IncrementalResolverTest {
    @Test fun `unchanged targets seed dependencies without rerunning their queries`() {
        val data = FakeSource(listOf(
            FakeClass("host.Target").method("new"),
            FakeClass("host.Anchor").method("mark", strings = setOf("anchor")),
        ))
        val owner = ClassSymbol("owner", ClassQuery(name = "host.Target"))
        val reused = ClassSymbol("reused", ClassQuery(strings = listOf("anchor")))
        val method = MethodSymbol("method", MethodQuery(owner = TypeRef.Of(owner), shape = MethodShape(name = "new")))
        val absent = ClassSymbol("absent", ClassQuery(name = "host.Target")).absentIn("CN_1")
        val symbols = listOf(method, reused, owner, absent)
        val calls = ArrayList<String>()
        val prefetched = ArrayList<String>()
        val counted = object : SymbolSource by data {
            override fun methodsUsingString(s: String): List<MethodView> {
                calls += s
                return data.methodsUsingString(s)
            }
            override fun prefetchStrings(strings: Collection<String>) {
                prefetched += strings
            }
        }
        val full = SymbolResolver.forBuild(data, 1, "CN_1").table(symbols)
        val resolver = SymbolResolver.forBuild(counted, 1, "CN_1")
        val seeds = full.entries.filterKeys { it != method.id }
        assertEquals(full, resolver.tableWithVerifiedEntries(symbols, seeds))
        assertEquals(emptyList<String>(), calls)
        assertEquals(emptyList<String>(), prefetched)
        assertEquals(Resolved.Method("host.Target", "new", emptyList(), "void"), resolver.resolve(method).getOrThrow())
        assertThrows(IllegalStateException::class.java) { resolver.tableWithVerifiedEntries(symbols, seeds) }
        assertThrows(IllegalArgumentException::class.java) {
            SymbolResolver.forBuild(data, 1, "CN_1").tableWithVerifiedEntries(symbols, mapOf("absent" to Resolved.Class("host.Target")))
        }
    }
}
