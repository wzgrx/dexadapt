package io.github.wzgrx.dexadapt.runtime

import io.github.wzgrx.dexadapt.Contract
import io.github.wzgrx.dexadapt.ValueRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContractMonitorTest {
    private class MemoryStore : ContractMonitor.Store {
        val saved = HashMap<String, Map<String, String>>()
        override fun load(tableKey: String) = saved[tableKey].orEmpty()
        override fun save(tableKey: String, verdicts: Map<String, String>) { saved[tableKey] = verdicts }
    }

    private val contracts = mapOf(
        "feed.bind" to Contract(args = mapOf(0 to ValueRule.InstanceOf("java.lang.CharSequence"), 1 to ValueRule.Range(0.0, 100.0)), mainThread = true, minCalls = 2),
        "feed.title" to Contract(returns = ValueRule.Matches("[a-z]+")),
    )

    private fun monitor(store: MemoryStore) = ContractMonitor(contracts, "t1", { Class.forName(it) }, store)

    @Test
    fun `observes until enough good calls, then remembers the verdict`() {
        val store = MemoryStore()
        val m = monitor(store)
        assertEquals(ContractMonitor.Verdict.OBSERVING, m.verdict("feed.bind"))
        assertEquals(ContractMonitor.Verdict.PASSED, m.verdict("unrelated"))
        assertEquals(ContractMonitor.Verdict.OBSERVING, m.record("feed.bind", null, listOf("hi", 5), null, true))
        assertEquals(ContractMonitor.Verdict.PASSED, m.record("feed.bind", null, listOf(StringBuilder("x"), 100), null, true))
        assertEquals(mapOf("feed.bind" to "passed"), store.saved[m.tableKey])
        // 下次启动同一张表：直接通过
        assertEquals(ContractMonitor.Verdict.PASSED, monitor(store).verdict("feed.bind"))
        // 换了表：重新观察
        assertEquals(ContractMonitor.Verdict.OBSERVING, ContractMonitor(contracts, "t2", { Class.forName(it) }, store).verdict("feed.bind"))
    }

    @Test
    fun `one bad call marks the hook point unadapted with a reason`() {
        val store = MemoryStore()
        val m = monitor(store)
        assertEquals(ContractMonitor.Verdict.VIOLATED, m.record("feed.bind", null, listOf(42, 5), null, true))
        assertTrue(m.reason("feed.bind")!!.contains("应该是 java.lang.CharSequence"))
        assertEquals(ContractMonitor.Verdict.VIOLATED, m.record("feed.bind", null, listOf("ok", 5), null, true))
        assertEquals(ContractMonitor.Verdict.VIOLATED, monitor(store).verdict("feed.bind"))
        assertTrue(monitor(store).reason("feed.bind")!!.startsWith("参数 0"))

        val other = ContractMonitor(contracts, "t3", { Class.forName(it) })
        assertEquals(ContractMonitor.Verdict.VIOLATED, other.record("feed.bind", null, listOf("ok", 101), null, true))
        assertEquals(ContractMonitor.Verdict.VIOLATED, ContractMonitor(contracts, "t4", { Class.forName(it) }).record("feed.bind", null, listOf("ok", 1), null, false))
        assertEquals(ContractMonitor.Verdict.VIOLATED, ContractMonitor(contracts, "t5", { Class.forName(it) }).record("feed.title", null, emptyList(), "ABC", false))
    }

    @Test
    fun `a rule that cannot be evaluated counts as a violation`() {
        val m = ContractMonitor(mapOf("x" to Contract(args = mapOf(0 to ValueRule.InstanceOf("no.Such")))), "t", { null })
        assertEquals(ContractMonitor.Verdict.VIOLATED, m.record("x", null, listOf("a"), null, true))
    }

    @Test
    fun `only locally adapted tables are observed`() {
        val t = io.github.wzgrx.dexadapt.SymbolTable(1, "d", emptyMap(), catalog = "c", apk = "a")
        assertTrue(AdaptPolicy.choose(t, null, "c", "a").observe)
        assertTrue(!AdaptPolicy.choose(null, t, "c", "a").observe)
    }
    @Test fun `changed contract forgets old verdict without changing query identity`() {
        val store = MemoryStore()
        fun create(c: Contract) = ContractMonitor(mapOf("x" to c), "same-table", { String::class.java }, store)
        val old = create(Contract(minCalls = 1))
        assertEquals(ContractMonitor.Verdict.PASSED, old.record("x", null, listOf(null), null, true))
        assertEquals(ContractMonitor.Verdict.PASSED, create(Contract(minCalls = 1)).verdict("x"))
        val next = create(Contract(args = mapOf(0 to ValueRule.NotNull), minCalls = 1))
        assertEquals(ContractMonitor.Verdict.OBSERVING, next.verdict("x"))
        assertEquals(ContractMonitor.Verdict.VIOLATED, next.record("x", null, listOf(null), null, true))
    }

    @Test fun `changed mapped target invalidates contract verdict identity`() {
        val a = io.github.wzgrx.dexadapt.SymbolTable(1, "same-dex", mapOf("x" to io.github.wzgrx.dexadapt.Resolved.Method("A", "a", emptyList(), "void")), catalog = "same-query")
        assertTrue(ContractMonitor.key(a) != ContractMonitor.key(a.copy(entries = mapOf("x" to io.github.wzgrx.dexadapt.Resolved.Method("A", "b", emptyList(), "void")))))
        assertEquals(ContractMonitor.key(a), ContractMonitor.key(a.copy(generatedAt = 999, module = "new")))
    }

    @Test fun `range refuses all non-finite input and accepts both finite endpoints`() {
        val c = mapOf("x" to Contract(args = mapOf(0 to ValueRule.Range(0.1, 8.0)), minCalls = 1))
        for (n in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            val m = ContractMonitor(c, "t", { null })
            assertEquals(ContractMonitor.Verdict.VIOLATED, m.record("x", null, listOf(n), null, false))
        }
        for (n in listOf(0.1, 8.0)) assertEquals(ContractMonitor.Verdict.PASSED, ContractMonitor(c, "t", { null }).record("x", null, listOf(n), null, false))
    }

}
