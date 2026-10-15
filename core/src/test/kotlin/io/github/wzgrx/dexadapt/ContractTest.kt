package io.github.wzgrx.dexadapt

import io.github.wzgrx.dexadapt.spec.Spec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContractTest {
    private object Group : SymbolGroup("g") {
        val owner = cls("owner", ClassQuery(name = "a.Owner"))
        val bind = method("bind", MethodQuery(owner = TypeRef.Of(owner), shape = MethodShape(name = "bind")))
            .contract(Contract(args = mapOf(0 to ValueRule.InstanceOf("\$g.owner"), 1 to ValueRule.NotNull, 2 to ValueRule.Matches("[a-z]+")), returns = ValueRule.Range(0.5, 3.0), mainThread = true))
    }

    @Test
    fun `contracts travel with the catalog and the spec but not the fingerprint`() {
        val catalog = Catalog(Group)
        assertEquals(setOf("g.bind"), catalog.contracts.keys)
        assertEquals(catalog.fingerprint(), Catalog(catalog.symbols).fingerprint())
        for (text in listOf(Spec(null, catalog).toYaml(), Spec(null, catalog).toJson())) {
            val back = if (text.startsWith("{")) Spec.fromJson(text) else Spec.fromYaml(text)
            assertEquals(catalog.contracts, back.catalog.contracts)
            if (!text.startsWith("{")) assertTrue(text, text.contains("min: 0.5"))
        }
    }

    @Test
    fun `static problems against a resolved method`() {
        val c = Contract(args = mapOf(0 to ValueRule.NotNull, 3 to ValueRule.NotNull), returns = ValueRule.NotNull, receiver = ValueRule.NotNull)
        val p = c.problems(Resolved.Method("a.B", "m", listOf("int", "java.lang.String"), "void"), isStatic = true)
        assertEquals(4, p.size)
        assertTrue(p.any { it.contains("参数 3 不存在") } && p.any { it.contains("没有返回值") } && p.any { it.contains("this") } && p.any { it.contains("参数 0 是 int") })
        assertTrue(Contract(args = mapOf(1 to ValueRule.NotNull)).problems(Resolved.Method("a.B", "m", listOf("int", "java.lang.String"), "void")).isEmpty())
    }
}
