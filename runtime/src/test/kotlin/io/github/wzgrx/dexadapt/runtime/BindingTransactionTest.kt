package io.github.wzgrx.dexadapt.runtime

import io.github.wzgrx.dexadapt.*
import org.junit.Test
import org.junit.Assert.*

class BindingTransactionTest {
    private fun table(name: String) = SymbolTable(1,"same-code",mapOf("method" to Resolved.Method("Owner",name,emptyList(),"void")),packageName="pkg")
    private class Node(override val id: String, override val symbols: Set<String>, override val startupOnly: Boolean = false, override val dependsOn: Set<String> = emptySet(), val canInstall: () -> Boolean = { true }) : BindingTransaction.Node {
        override var installed = true
        var installs = 0
        var removals = 0
        var restart = false
        override fun uninstall() { if (installed) { removals++; installed=false } }
        override fun install(): Boolean { installs++; installed=canInstall(); return installed }
        override fun requireRestart() { restart=true }
    }
    @Test fun `changed target rebinds dependent features once and leaves unrelated hooks alone`() {
        var current: SymbolTable?=table("a");var paused=false
        val parent=Node("parent",setOf("method"));val child=Node("child",emptySet(),dependsOn=setOf("parent"));val other=Node("other",emptySet())
        val result=BindingTransaction.apply(current,table("b"),listOf(child,parent,other),{current=it},{paused=it})
        assertTrue(result.applied);assertEquals(setOf("parent","child"),result.rebound)
        assertEquals(1,parent.installs);assertEquals(1,child.installs);assertEquals(0,other.installs);assertFalse(paused)
        assertEquals(table("b"),current)
    }
    @Test fun `failed candidate restores previous table and old installations`() {
        val old=table("a");var current: SymbolTable?=old
        val node=Node("feature",setOf("method"),canInstall={current==old})
        val result=BindingTransaction.apply(old,table("b"),listOf(node),{current=it},{})
        assertFalse(result.applied);assertEquals(old,current);assertTrue(node.installed);assertEquals(2,node.installs)
    }
    @Test fun `startup binding and dependents stay installed but are marked for restart`() {
        val root=Node("root",setOf("method"),startupOnly=true);val child=Node("child",emptySet(),dependsOn=setOf("root"))
        val result=BindingTransaction.apply(table("a"),table("b"),listOf(root,child),{},{})
        assertEquals(setOf("root","child"),result.restart);assertTrue(root.restart);assertTrue(child.restart)
        assertEquals(0,root.removals);assertEquals(0,child.installs)
    }
    @Test fun `hook snapshot stays coherent while install capture reads current mapping`() {
        val symbol=ClassSymbol("x",ClassQuery(name="java.lang.String"))
        val old=SymbolTable(1,"code",mapOf("x" to Resolved.Class("java.lang.String")))
        val next=old.copy(entries=mapOf("x" to Resolved.Class("java.lang.Integer")))
        val linker=SymbolLinker(old,javaClass.classLoader)
        linker.withSnapshot {
            assertEquals(String::class.java,linker.clazz(symbol));linker.replace(next)
            val captured=linker.captureReads { linker.clazz(symbol) }
            assertEquals(Int::class.javaObjectType,captured.value);assertEquals(setOf("x"),captured.ids)
            assertEquals(String::class.java,linker.clazz(symbol))
            // 新进入的嵌套Hook读新表，返回后外层进行中的调用仍读旧表。
            linker.withSnapshot { assertEquals(Int::class.javaObjectType,linker.clazz(symbol)) }
            assertEquals(String::class.java,linker.clazz(symbol))
        }
        assertEquals(Int::class.javaObjectType,linker.clazz(symbol))
    }
    @Test fun `removed target may cleanly disable a feature without rejecting other bindings`() {
        val old=table("a")
        var current: SymbolTable?=old
        val next=old.copy(entries=emptyMap(),missing=mapOf("method" to "removed"))
        val node=object:BindingTransaction.Node {
            override val id="disabled"
            override var installed=true
            override val startupOnly=false
            override val dependsOn=emptySet<String>()
            override val symbols=setOf("method")
            override fun uninstall() { installed=false }
            override fun install()=true // 已干净停用，不是安装抛错。
            override fun requireRestart() {}
        }
        val result=BindingTransaction.apply(current,next,listOf(node),{current=it},{})
        assertTrue(result.applied);assertFalse(node.installed);assertEquals(next,current)
    }

}
