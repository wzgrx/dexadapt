package io.github.wzgrx.dexadapt.runtime

import io.github.wzgrx.dexadapt.MethodQuery
import io.github.wzgrx.dexadapt.MethodSymbol
import io.github.wzgrx.dexadapt.Resolved
import io.github.wzgrx.dexadapt.SymbolTable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CheckedInvocationTest {
    class Host(val label: String) {
        fun instance(value: Number, suffix: String?): String = label + value + suffix
        companion object {
            @JvmStatic fun choose(value: Int) = "old:$value"
            @JvmStatic fun choose(value: Int, flag: Boolean) = "new:$value:$flag"
            @JvmStatic fun throwsHost(): String = throw expected
        }
    }

    private val symbol = MethodSymbol("host.method", MethodQuery())
    private val linker = SymbolLinker(null, Host::class.java.classLoader)
    private fun target(name: String, params: List<String>, returns: String = "java.lang.String") {
        linker.replace(SymbolTable(1, "dex", mapOf(symbol.id to Resolved.Method(Host::class.java.name, name, params, returns))))
    }
    private fun invoke(receiver: Any? = null, vararg args: Any?) = linker.invokeChecked(symbol, receiver, args)

    @Test fun `links live signature and rejects stale arguments before invoking`() {
        target("choose", listOf("int"))
        assertEquals("old:4", invoke(null, 4))
        target("choose", listOf("int", "boolean"))
        assertEquals("new:4:true", invoke(null, 4, true))
        for (error in listOf(
            assertThrows(IllegalArgumentException::class.java) { invoke(null, 4) },
            assertThrows(IllegalArgumentException::class.java) { invoke(null, 4L, true) },
            assertThrows(IllegalArgumentException::class.java) { invoke(null, 4, null) },
            assertThrows(IllegalArgumentException::class.java) { invoke(Host("wrong"), 4, true) },
        )) {
            assertTrue(error.message.orEmpty().startsWith("host.method → "))
        }
    }

    @Test fun `constructors receivers reference types and host exceptions preserve behavior`() {
        target("<init>", listOf("java.lang.String"), "void")
        val instance = invoke(null, "name") as Host
        target("instance", listOf("java.lang.Number", "java.lang.String"))
        assertEquals("name3null", invoke(instance, 3, null))
        assertThrows(IllegalArgumentException::class.java) { invoke(Any(), 3, null) }
        target("throwsHost", emptyList())
        assertSame(expected, assertThrows(IllegalStateException::class.java) { invoke() })
    }

    companion object {
        private val expected = IllegalStateException("host failure")
    }
}
