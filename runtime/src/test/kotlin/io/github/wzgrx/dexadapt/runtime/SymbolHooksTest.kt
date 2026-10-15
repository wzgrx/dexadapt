package io.github.wzgrx.dexadapt.runtime

import io.github.wzgrx.dexadapt.ClassQuery
import io.github.wzgrx.dexadapt.ClassSymbol
import io.github.wzgrx.dexadapt.Contract
import io.github.wzgrx.dexadapt.MethodQuery
import io.github.wzgrx.dexadapt.MethodShape
import io.github.wzgrx.dexadapt.MethodSymbol
import io.github.wzgrx.dexadapt.Resolved
import io.github.wzgrx.dexadapt.SymbolTable
import io.github.wzgrx.dexadapt.TypeRef
import io.github.wzgrx.dexadapt.ValueRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SymbolHooksTest {
    class Target { fun greet(name: String): String = "hi $name" }

    private val cls = ClassSymbol("t.cls", ClassQuery(name = Target::class.java.name))
    private val greet = MethodSymbol("t.greet", MethodQuery(owner = TypeRef.Of(cls), shape = MethodShape(name = "greet")))
    private val missing = MethodSymbol("t.gone", MethodQuery(owner = TypeRef.Of(cls), shape = MethodShape(name = "gone")))
    private val table = SymbolTable(
        1, "d",
        mapOf("t.cls" to Resolved.Class(Target::class.java.name), "t.greet" to Resolved.Method(Target::class.java.name, "greet", listOf("java.lang.String"), "java.lang.String")),
        mapOf("t.gone" to "no match"),
    )
    private val linker = SymbolLinker(table, javaClass.classLoader)

    @Test
    fun `finds the member, skips missing ones, gates by contract`() {
        val logs = ArrayList<String>()
        val monitor = ContractMonitor(mapOf("t.greet" to Contract(args = mapOf(0 to ValueRule.Matches("[a-z]+")), minCalls = 2)), "k", { Class.forName(it) })
        val hooks = SymbolHooks(linker, { monitor }) { logs += it }
        assertNull(hooks.hook(missing) { _, _ -> "installed" })
        assertTrue(logs.single().contains("t.gone"))

        var gate: SymbolHooks.Gate? = null
        val member = hooks.hook(greet) { m, g -> gate = g; m }!!
        assertEquals("greet", member.name)
        val g = gate!!
        assertFalse(g.open)
        g.record(null, listOf("ann"), "hi ann", false)
        assertFalse(g.open)
        g.record(null, listOf("bob"), "hi bob", false)
        assertTrue(g.open)
        assertNull(g.problem)
    }

    @Test
    fun `violation keeps the gate shut and explains why, no monitor means open`() {
        val monitor = ContractMonitor(mapOf("t.greet" to Contract(args = mapOf(0 to ValueRule.Matches("[a-z]+")))), "k", { Class.forName(it) })
        val g = SymbolHooks(linker, { monitor }).gate(greet)
        g.record(null, listOf("Ann1"), "x", false)
        assertFalse(g.open)
        assertTrue(g.problem!!.contains("不匹配"))
        assertTrue(SymbolHooks(linker).gate(greet).open)
        assertFalse(SymbolHooks.onMainThread())
    }

    @Test
    fun `guessed locations are used only when allowed`() {
        val guessed = table.copy(guesses = mapOf("t.gone" to SymbolTable.Guessed(Resolved.Method(Target::class.java.name, "greet", listOf("java.lang.String"), "java.lang.String"), "test")))
        assertFalse(SymbolLinker(guessed, javaClass.classLoader).available(missing))
        assertTrue(SymbolLinker(guessed, javaClass.classLoader, useGuess = { it == "t.gone" }).available(missing))
    }
}
