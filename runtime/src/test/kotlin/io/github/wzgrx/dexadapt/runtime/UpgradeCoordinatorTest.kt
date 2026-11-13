package io.github.wzgrx.dexadapt.runtime

import io.github.wzgrx.dexadapt.Resolved
import io.github.wzgrx.dexadapt.SymbolTable
import io.github.wzgrx.dexadapt.runtime.UpgradeCoordinator.Feature
import io.github.wzgrx.dexadapt.runtime.UpgradeCoordinator.Outcome
import io.github.wzgrx.dexadapt.runtime.UpgradeCoordinator.Trigger
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.Executor

class UpgradeCoordinatorTest {
    @get:Rule val temp = TemporaryFolder()

    private val old = HostCodeIdentity("sample", 1, "1".repeat(64))
    private val sameCode = old.copy(dexSha256 = "2".repeat(64))
    private val newer = old.copy(versionCode = 2, dexSha256 = "3".repeat(64))
    private val enabled = setOf(Feature("hot", setOf("hot")), Feature("startup", setOf("startup"), startupOnly = true))
    private fun table(identity: HostCodeIdentity, partial: Boolean = false) = SymbolTable(
        identity.versionCode, identity.dexSha256,
        (if (partial) listOf("hot") else listOf("hot", "startup", "background")).associateWith { Resolved.Class(it) },
        catalog = "catalog", packageName = identity.packageName, partial = partial,
    )

    @Test fun `strict identity checks digest package build and completeness before selecting`() {
        for (wrong in listOf(sameCode, newer, old.copy(packageName = "other"))) {
            assertNull(AdaptPolicy.chooseVerified(wrong, table(old), table(old), "catalog").table)
        }
        assertNull(AdaptPolicy.chooseVerified(null, table(old), table(old), "catalog").table)
        assertNull(AdaptPolicy.chooseVerified(old, table(old, true), table(old, true), "catalog").table)
        assertNull(AdaptPolicy.chooseVerified(old, table(old), table(old), "new-catalog").table)
        assertEquals(AdaptPolicy.Source.BUNDLED, AdaptPolicy.chooseVerified(old, null, table(old).copy(catalog = ""), "catalog").source)
    }

    @Test fun `first open coalesces triggers and measures only acknowledged feature restoration`() {
        val queued = ArrayDeque<Runnable>()
        var ticks = 0L
        val events = ArrayList<UpgradeCoordinator.Publication>()
        val reports = ArrayList<UpgradeCoordinator.Report>()
        val store = DirectoryAdaptationStore(temp.newFolder())
        val q = UpgradeCoordinator(Executor(queued::addLast), { old }, store, { request, _, stage ->
            ticks = 10
            stage(table(request.identity, true))
            assertNull(store.local(old, "catalog"))
            ticks = 25
            table(request.identity)
        }, { publication ->
            events += publication
            if (publication.table.partial) {
                ticks = 12
                setOf("hot")
            } else emptySet()
        }, reports::add, { ticks })
        assertTrue(q.onEvent(old.packageName, "catalog", enabled, Trigger.HOST_FIRST_OPEN))
        assertFalse(q.onEvent(old.packageName, "catalog", enabled, Trigger.HOST_PACKAGE_CHANGED))
        queued.removeFirst().run()
        assertEquals(listOf(setOf("hot"), emptySet()), events.map { it.ready })
        assertEquals(listOf(emptySet(), setOf("startup")), events.map { it.restartRequired })
        assertEquals(12L, reports.single().firstRestoreNanos)
        assertEquals(25L, reports.single().elapsedNanos)
        assertEquals(table(old), store.local(old, "catalog"))
    }

    @Test fun `interrupt retry and downgrade reuse only completed identity bound tables`() {
        var identity = old
        var interrupt = true
        val reports = ArrayList<UpgradeCoordinator.Report>()
        val store = DirectoryAdaptationStore(temp.newFolder())
        var scans = 0
        val q = UpgradeCoordinator(Executor(Runnable::run), { identity }, store, { request, _, stage ->
            scans++
            stage(table(request.identity, true))
            if (interrupt) throw InterruptedException("test")
            table(request.identity)
        }, { it.ready }, reports::add)
        fun send(trigger: Trigger) = q.onEvent(identity.packageName, "catalog", enabled, trigger)
        send(Trigger.HOST_FIRST_OPEN)
        assertEquals(Outcome.FAILED, reports.last().outcome)
        assertNull(store.local(old, "catalog"))
        interrupt = false
        send(Trigger.RETRY)
        identity = sameCode
        assertNull(store.local(identity, "catalog"))
        send(Trigger.HOST_PACKAGE_CHANGED)
        identity = newer
        send(Trigger.HOST_PACKAGE_CHANGED)
        identity = old
        send(Trigger.HOST_PACKAGE_CHANGED)
        assertEquals(Outcome.REUSED, reports.last().outcome)
        assertEquals(4, scans)
        assertEquals(table(old), store.local(old, "catalog"))
        assertEquals(table(sameCode), store.local(sameCode, "catalog"))
    }

    @Test fun `consecutive upgrades discard queued middle build and never publish stale result`() {
        val middle = sameCode
        var identity = old
        val publications = ArrayList<HostCodeIdentity>()
        val reports = ArrayList<Outcome>()
        val store = DirectoryAdaptationStore(temp.newFolder())
        lateinit var q: UpgradeCoordinator
        q = UpgradeCoordinator(Executor(Runnable::run), { identity }, store, { request, _, stage ->
            if (request.identity == old) {
                identity = middle
                q.onEvent(old.packageName, "catalog", enabled, Trigger.HOST_PACKAGE_CHANGED)
                identity = newer
                q.onEvent(old.packageName, "catalog", enabled, Trigger.HOST_PACKAGE_CHANGED)
            }
            stage(table(request.identity, true))
            table(request.identity)
        }, { publications += it.request.identity; it.ready }, { reports += it.outcome })
        q.onEvent(old.packageName, "catalog", enabled, Trigger.HOST_FIRST_OPEN)
        assertEquals(listOf(Outcome.SUPERSEDED, Outcome.SCANNED), reports)
        assertEquals(listOf(newer, newer), publications)
        assertNull(store.local(old, "catalog"))
        assertNull(store.local(middle, "catalog"))
        assertEquals(table(newer), store.local(newer, "catalog"))
    }
}
