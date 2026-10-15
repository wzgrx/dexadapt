package io.github.wzgrx.dexadapt.resolve

import io.github.wzgrx.dexadapt.ClassQuery
import io.github.wzgrx.dexadapt.ClassSymbol
import io.github.wzgrx.dexadapt.FieldQuery
import io.github.wzgrx.dexadapt.FieldSymbol
import io.github.wzgrx.dexadapt.MethodQuery
import io.github.wzgrx.dexadapt.MethodShape
import io.github.wzgrx.dexadapt.MethodSymbol
import io.github.wzgrx.dexadapt.Pin
import io.github.wzgrx.dexadapt.Resolved
import io.github.wzgrx.dexadapt.Symbol
import io.github.wzgrx.dexadapt.TypeRef
import io.github.wzgrx.dexadapt.type
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SymbolResolverTest {
    private val player = FakeClass("x.a.Player")
        .field("speed", "float")
        .field("listener", "x.a.L")
        .method("a", listOf("float"), strings = setOf("setSpeed"), fieldRefs = setOf("x.a.Player#speed"))
        .method("b", listOf("float"), strings = setOf("setSpeed"))
        .method("c", listOf("int"), returns = "java.lang.String", strings = setOf("toString:"))
    private val listener = FakeClass("x.a.L", interfaces = listOf("java.lang.Runnable"), flags = AccessFlag.INTERFACE or AccessFlag.ABSTRACT)
        .method("run")
    private val other = FakeClass("x.a.M", interfaces = listOf("java.lang.Runnable"), flags = AccessFlag.INTERFACE or AccessFlag.ABSTRACT)
        .method("run")
    private val view = FakeClass("x.b.MyView", superName = "android.view.View")
    private val caller = FakeClass("x.b.Caller")
        .method("show", listOf("x.b.MyView"), calls = setOf("x.b.MyView#setVisibility(int)void"))
        .method("hide", listOf("android.view.View"), calls = setOf("android.view.View#setVisibility(int)void", "x.b.MyView#setVisibility(int)void"))
    private val lambda = FakeClass("x.c.Fn")
        .method("invoke", listOf("java.lang.Object"), returns = "java.lang.Object", flags = AccessFlag.BRIDGE or AccessFlag.SYNTHETIC)
        .method("invoke", listOf("java.lang.String"), returns = "java.lang.Object")
    private val source = FakeSource(listOf(player, listener, other, view, caller, lambda))

    private fun resolver(pins: Map<String, Pin> = emptyMap(), skip: Set<String> = emptySet()) =
        SymbolResolver(source, 1, pinFor = { pins[it.id] }, skip = { it.id in skip })

    private val playerCls = ClassSymbol("t.player", ClassQuery(strings = listOf("toString:")))
    private val setSpeedAll = MethodSymbol("t.setSpeedAll", MethodQuery(owner = TypeRef.Of(playerCls), shape = MethodShape(params = listOf(type("float")))))

    @Test
    fun `only a single match resolves`() {
        val r = resolver()
        assertEquals(Resolved.Class("x.a.Player"), r.resolve(playerCls).getOrThrow())
        val ambiguous = r.resolve(setSpeedAll)
        assertTrue(ambiguous.exceptionOrNull()!!.message!!.startsWith("2 matches"))
        val none = MethodSymbol("t.none", MethodQuery(owner = TypeRef.Of(playerCls), shape = MethodShape(name = "zzz")))
        assertEquals("no match", r.resolve(none).exceptionOrNull()!!.message)
    }

    @Test
    fun `uses fields and excludes narrow sibling methods`() {
        val speed = FieldSymbol("t.speed", FieldQuery(owner = TypeRef.Of(playerCls), type = type("float")))
        val a = MethodSymbol("t.a", MethodQuery(owner = TypeRef.Of(playerCls), shape = MethodShape(params = listOf(type("float"))), usesFields = listOf(speed)))
        val b = MethodSymbol("t.b", MethodQuery(owner = TypeRef.Of(playerCls), shape = MethodShape(params = listOf(type("float"))), excludes = listOf(a)))
        val r = resolver()
        assertEquals("a", (r.resolve(a).getOrThrow() as Resolved.Method).name)
        assertEquals("b", (r.resolve(b).getOrThrow() as Resolved.Method).name)
        assertEquals(Resolved.Field("x.a.Player", "speed", "float"), r.resolve(speed).getOrThrow())
    }

    @Test
    fun `field of tells two identical interfaces apart`() {
        val shapeOnly = ClassSymbol("t.iface", ClassQuery(packagePrefix = "x.a", isInterface = true, interfaces = listOf(type("java.lang.Runnable"))))
        assertTrue(resolver().resolve(shapeOnly).exceptionOrNull()!!.message!!.startsWith("2 matches"))
        val held = ClassSymbol("t.held", ClassQuery(packagePrefix = "x.a", isInterface = true, fieldOf = TypeRef.Of(playerCls)))
        assertEquals(Resolved.Class("x.a.L"), resolver().resolve(held).getOrThrow())
    }

    @Test
    fun `pins must exist and still match the query`() {
        val good = mapOf("t.setSpeedAll" to Pin.Method("x.a.Player", "b", listOf("float")))
        assertEquals("b", (resolver(good).resolve(setSpeedAll).getOrThrow() as Resolved.Method).name)
        val wrongShape = mapOf("t.setSpeedAll" to Pin.Method("x.a.Player", "c", listOf("int")))
        assertTrue(resolver(wrongShape).resolve(setSpeedAll).exceptionOrNull()!!.message!!.contains("does not match"))
        val gone = mapOf("t.setSpeedAll" to Pin.Method("x.a.Player", "zz", listOf("float")))
        assertTrue(resolver(gone).resolve(setSpeedAll).exceptionOrNull()!!.message!!.contains("missing"))
        val speed = FieldSymbol("t.speed", FieldQuery(owner = TypeRef.Of(playerCls), type = type("float")))
        val wrongField = mapOf("t.speed" to Pin.Field("x.a.Player", "listener"))
        assertTrue(resolver(wrongField).resolve(speed).exceptionOrNull()!!.message!!.contains("does not match"))
        val wrongClass = mapOf("t.player" to Pin.Class("x.a.L"))
        assertTrue(resolver(wrongClass).resolve(playerCls).exceptionOrNull()!!.message!!.contains("does not match"))
    }

    @Test
    fun `skipped symbols are not resolved, nor anything depending on them`() {
        val r = resolver(skip = setOf("t.player"))
        assertTrue(r.resolve(playerCls).exceptionOrNull()!!.message!!.contains("absentIn"))
        assertTrue(r.resolve(setSpeedAll).exceptionOrNull()!!.message!!.contains("depends on t.player"))
        val table = r.table(listOf(playerCls, setSpeedAll))
        assertTrue("t.player" !in table.missing) // 本身跳过的不算缺
        assertTrue("t.setSpeedAll" in table.missing)
    }

    @Test
    fun `calls exact falls back to a subclass reference only when nothing calls the named owner`() {
        val exact = MethodSymbol("t.hide", MethodQuery(owner = type("x.b.Caller"), callsExact = listOf("android.view.View#setVisibility")))
        assertEquals("hide", (resolver().resolve(exact).getOrThrow() as Resolved.Method).name) // 写明的类命中，不额外认 show
        val rebound = MethodSymbol(
            "t.show",
            MethodQuery(owner = type("x.b.Caller"), shape = MethodShape(params = listOf(type("x.b.MyView"))), callsExact = listOf("android.view.View#setVisibility")),
        )
        assertEquals("show", (resolver().resolve(rebound).getOrThrow() as Resolved.Method).name)
        val wrongName = MethodSymbol("t.wrong", MethodQuery(owner = type("x.b.Caller"), callsExact = listOf("android.view.View#setAlpha")))
        assertTrue(resolver().resolve(wrongName).isFailure)
    }

    @Test
    fun `bridge and synthetic methods give way to the real one`() {
        val invoke = MethodSymbol("t.invoke", MethodQuery(owner = type("x.c.Fn"), shape = MethodShape(name = "invoke", params = listOf(TypeRef.Any))))
        assertEquals(listOf("java.lang.String"), (resolver().resolve(invoke).getOrThrow() as Resolved.Method).params)
    }

    @Test
    fun `dependency cycles and forward references fail clearly`() {
        val callsOfFirst = ArrayList<MethodSymbol>()
        val first = MethodSymbol("t.first", MethodQuery(owner = type("x.a.Player"), calls = callsOfFirst))
        val second = MethodSymbol("t.second", MethodQuery(owner = type("x.a.Player"), calls = listOf(first)))
        callsOfFirst += second
        val r = resolver()
        val msg = r.resolve(first).exceptionOrNull()!!.message!!
        assertTrue(msg, msg.contains("dependency cycle: t.first -> t.second -> t.first"))
        assertTrue(r.resolve(second).exceptionOrNull()!!.message!!.contains("dependency cycle"))
        assertTrue(SymbolGraph.problems(listOf(first, second)).any { it.startsWith("dependency cycle") })

        @Suppress("UNCHECKED_CAST")
        val forward = MethodSymbol("t.forward", MethodQuery(owner = type("x.a.Player"), calls = listOf<MethodSymbol?>(null) as List<MethodSymbol>))
        assertTrue(r.resolve(forward).exceptionOrNull()!!.message!!.startsWith("forward reference: t.forward"))
        assertEquals(1, SymbolGraph.problems(listOf<Symbol>(forward)).size)
        // 引用了目录外的符号也报出来。
        assertTrue(SymbolGraph.problems(listOf<Symbol>(setSpeedAll)).single().contains("not in the catalog"))
    }
}
