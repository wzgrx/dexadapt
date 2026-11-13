package io.github.wzgrx.dexadapt.cli

import io.github.wzgrx.dexadapt.SymbolTable
import io.github.wzgrx.dexadapt.resolve.SymbolMargin
import io.github.wzgrx.dexadapt.spec.Spec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class IncrementalMarginsTest {
    private val baseline = """
        dexadapt: 2
        symbols:
          - id: t.anchor
            class: {name: host.Anchor}
          - id: t.outer
            class: {fieldOf: ${'$'}t.anchor}
          - id: t.variant
            method:
              owner: ${'$'}t.outer
              name: run
              variants: [{params: [${'$'}t.anchor], builds: [CN_2]}]
          - id: t.unrelated
            class: {name: host.Unrelated}
    """.trimIndent()

    @Test fun `a change propagates transitively while unrelated rows are reused`() {
        val before = Spec.fromYaml(baseline)
        val after = Spec.fromYaml(baseline.replace("host.Anchor", "host.NewAnchor"))
        val old = IncrementalMargins.fingerprints(before)
        val new = IncrementalMargins.fingerprints(after)
        assertEquals(setOf("t.anchor", "t.outer", "t.variant"), old.keys.filter { old[it] != new[it] }.toSet())
        val table = SymbolTable(2, "dex", emptyMap())
        val collect = IncrementalMargins.collect(before.catalog.symbols, old, table, emptyMap()) { null }
        val touched = mutableListOf<String>()
        val changed = IncrementalMargins.collect(after.catalog.symbols, new, table, collect.rows) { symbol ->
            touched += symbol.id
            SymbolMargin(symbol.id, listOf(SymbolMargin.Drop("params", 2)))
        }
        assertEquals(listOf("t.anchor", "t.outer", "t.variant"), touched)
        assertEquals(1, changed.reused)
        assertEquals(3, changed.computed)
    }

    @Test fun `cache refuses broken records and does not retain failed or unknown rows`() {
        val spec = Spec.fromYaml(baseline)
        val fingerprints = IncrementalMargins.fingerprints(spec)
        val table = SymbolTable(2, "dex", emptyMap(), missing = mapOf("t.outer" to "failed"))
        val results = IncrementalMargins.collect(spec.catalog.symbols, fingerprints, table, emptyMap()) {
            if (it.id == "t.variant") SymbolMargin(it.id, listOf(SymbolMargin.Drop("params", SymbolMargin.UNKNOWN))) else null
        }
        assertFalse("t.variant" in results.rows)
        assertFalse("t.outer" in results.rows)
        assertTrue(results.margins.single().drops.single().count == SymbolMargin.UNKNOWN)
        val dir = kotlin.io.path.createTempDirectory("margins").toFile()
        try {
            val key = IncrementalMargins.key("engine", "app", "CN_2", "dex")
            val snapshot = IncrementalMargins.Snapshot("spec", table.toJson(), results.rows)
            IncrementalMargins.write(dir, key, snapshot)
            assertEquals(snapshot, IncrementalMargins.read(dir, key))
            assertNull(IncrementalMargins.read(dir, IncrementalMargins.key("engine", "app", "CN_2", "other")))
            val file = File(dir, "$key.json")
            file.writeText(file.readText().replace("t.unrelated", "t.other"))
            assertNull(IncrementalMargins.read(dir, key))
        } finally {
            dir.deleteRecursively()
        }
    }
}
