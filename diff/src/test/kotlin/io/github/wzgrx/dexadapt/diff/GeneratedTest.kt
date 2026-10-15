package io.github.wzgrx.dexadapt.diff

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeneratedTest {
    @Test
    fun `recognises compiler and r8 names`() {
        listOf(
            "a.b.Foo\$\$ExternalSyntheticLambda3", "a.b.Foo\$\$ExternalSyntheticOutline0", "a.b.Foo#\$r8\$lambda\$abc",
            "a.Linker\$setUp\$lambda\$41\$\$inlined\$dynamicLinker\$default\$1\$1", "a.b.Foo\$1", "a.b.Foo#access\$000",
            "com/x/Presenter#handleModifyPhone\$lambda\$12", "a.b.Foo#lambda\$onCreate\$3",
        ).forEach { assertTrue(it, Generated.isGenerated(it)) }
        listOf("a.b.Foo", "a.b.Foo\$Builder", "a.b.Foo#onCreate", "price \$1 off", "a.b.Foo\$Companion").forEach { assertFalse(it, Generated.isGenerated(it)) }
        assertTrue(Generated.isR8("a.b.Foo\$\$ExternalSyntheticLambda3"))
        assertFalse(Generated.isR8("a.b.Foo\$1"))
    }

    @Test
    fun `renumbering cancels out, real changes stay`() {
        val (delta, stats) = Generated.split(
            added = listOf("a.L\$setUp\$lambda\$41\$\$inlined\$x\$1", "a.L\$setUp\$lambda\$41\$\$inlined\$x\$2", "a.NewFeature", "a.Z\$\$ExternalSyntheticLambda7"),
            removed = listOf("a.L\$setUp\$lambda\$40\$\$inlined\$x\$1", "a.L\$setUp\$lambda\$40\$\$inlined\$x\$2", "a.OldFeature"),
        )
        assertEquals(listOf("a.NewFeature"), delta.added)
        assertEquals(listOf("a.OldFeature"), delta.removed)
        assertEquals(Generated.Stats(added = 3, removed = 2, renumbered = 2, r8 = 1), stats)
    }
}
