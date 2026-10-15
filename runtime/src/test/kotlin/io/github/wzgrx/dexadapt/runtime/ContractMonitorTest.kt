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
        assertEquals(mapOf("feed.bind" to "passed"), store.saved["t1"])
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
}
