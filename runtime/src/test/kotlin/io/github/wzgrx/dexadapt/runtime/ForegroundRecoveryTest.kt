package io.github.wzgrx.dexadapt.runtime

import io.github.wzgrx.dexadapt.SymbolTable
import org.junit.Assert.*
import org.junit.Test

class ForegroundRecoveryTest {
    private val identity = HostCodeIdentity("example.host", 123, "a".repeat(64))
    private val table = SymbolTable(identity.versionCode, identity.dexSha256, emptyMap(), packageName = identity.packageName, catalog = "rules")

    @Test fun `matching complete local table does not need bundled lookup or scan`() {
        val result = ForegroundRecovery.select(123, identity, "rules",
            { table.copy(recoveryToken = "expired") }, { error("unneeded fallback") }) as ForegroundRecovery.Result.Available
        assertEquals(table, result.table)
    }

    @Test fun `same version wrong digest and partial table request a scan`() {
        assertEquals(ForegroundRecovery.Result.NeedsScan, ForegroundRecovery.select(123,
            identity.copy(dexSha256 = "b".repeat(64)), "rules", { table }, { null }))
        assertEquals(ForegroundRecovery.Result.NeedsScan,
            ForegroundRecovery.select(123, identity, "rules", { table.copy(partial = true) }, { null }))
    }

    @Test fun `unreadable or changed installed version does not load or queue table`() {
        for (actual in listOf(null, identity.copy(versionCode = 124))) {
            assertEquals(ForegroundRecovery.Result.Unavailable, ForegroundRecovery.select(123, actual, "rules",
                { error("must not load") }, { error("must not load") }))
        }
    }

    @Test fun `a delayed receipt preserves its negative offset from renewed delivery`() {
        val trace = RecoveryTrace({ 800L }, 500L)
        assertEquals(-200L, trace.observe(RecoveryTrace.Step.FIRST_EFFECT, 300L).sinceRequestNanos)
        assertEquals(-200L, trace.observe(RecoveryTrace.Step.FIRST_EFFECT, 700L).sinceRequestNanos)
    }
}
