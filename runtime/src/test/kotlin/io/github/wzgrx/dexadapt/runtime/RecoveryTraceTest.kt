package io.github.wzgrx.dexadapt.runtime

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class RecoveryTraceTest {
    @get:Rule val temp = TemporaryFolder()
    private val host = HostCodeIdentity("host", 1, "a".repeat(64))

    @Test fun `confirmation belongs to exact session and cannot fake a successful effect`() {
        val receipts = RecoveryReceipt()
        val prior = receipts.publish(host, "cat", true)
        val full = receipts.publish(host, "cat", false)
        assertFalse(receipts.acknowledge(prior.token, RecoveryReceipt.State.HOST_BOUND))
        assertFalse(receipts.acknowledge(full.token, RecoveryReceipt.State.FIRST_EFFECT))
        assertTrue(receipts.acknowledge(full.token, RecoveryReceipt.State.HOST_BOUND))
        assertTrue(receipts.acknowledge(full.token, RecoveryReceipt.State.FIRST_EFFECT))
        assertFalse(receipts.acknowledge(full.token, RecoveryReceipt.State.RETRYABLE_FAILURE))
        val restarted = RecoveryReceipt()
        restarted.publish(host, "cat", false)
        assertFalse(restarted.acknowledge(full.token, RecoveryReceipt.State.HOST_BOUND))
    }

    @Test fun `same size same time replacement rehashes when installed file is replaced`() {
        val path = temp.newFile("host.apk").apply { writeText("a") }
        val replacement = temp.newFile("new.apk").apply { writeText("b"); setLastModified(path.lastModified()) }
        val installation = InstalledDexIdentityCache.Installation(1, 10, listOf(path))
        var reads = 0
        val cache = InstalledDexIdentityCache({ installation }) { files ->
            reads++
            files.single().readText().repeat(64)
        }
        assertEquals(host, cache.current("host"))
        assertEquals(host, cache.current("host"))
        val beforeReplacement = reads
        assertTrue(beforeReplacement in 1..2)
        Files.move(replacement.toPath(), path.toPath(), StandardCopyOption.REPLACE_EXISTING)
        assertEquals("b".repeat(64), cache.current("host")?.dexSha256)
        assertEquals(beforeReplacement + 1, reads)
    }
    @Test fun `only the first concurrent failure advances the receipt`() {
        val receipts = RecoveryReceipt()
        val ticket = receipts.publish(host, "cat", false)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val start = CountDownLatch(1)
            val calls = List(2) {
                pool.submit<RecoveryReceipt.Confirmation> {
                    check(start.await(5, TimeUnit.SECONDS))
                    receipts.confirm(ticket.token, host, "cat", RecoveryReceipt.State.RETRYABLE_FAILURE)
                }
            }
            start.countDown()
            val results = calls.map { it.get(5, TimeUnit.SECONDS) }
            assertEquals(1, results.count { it is RecoveryReceipt.Confirmation.Advanced })
            assertEquals(1, results.count { it is RecoveryReceipt.Confirmation.Duplicate })
        } finally {
            pool.shutdownNow()
        }
    }

    @Test fun `wrong code or catalog never advances and a delayed binding cannot undo first effect`() {
        val receipts = RecoveryReceipt()
        val ticket = receipts.publish(host, "cat", false)
        fun confirm(state: RecoveryReceipt.State, identity: HostCodeIdentity = host, catalog: String = "cat") =
            receipts.confirm(ticket.token, identity, catalog, state)
        assertEquals(RecoveryReceipt.Confirmation.Rejected(RecoveryReceipt.Rejection.IDENTITY_CHANGED),
            confirm(RecoveryReceipt.State.HOST_BOUND, host.copy(dexSha256 = "b".repeat(64))))
        assertEquals(RecoveryReceipt.Confirmation.Rejected(RecoveryReceipt.Rejection.CATALOG_CHANGED),
            confirm(RecoveryReceipt.State.HOST_BOUND, catalog = "next"))
        assertTrue(confirm(RecoveryReceipt.State.HOST_BOUND) is RecoveryReceipt.Confirmation.Advanced)
        assertTrue(confirm(RecoveryReceipt.State.FIRST_EFFECT) is RecoveryReceipt.Confirmation.Advanced)
        val replay = confirm(RecoveryReceipt.State.HOST_BOUND) as RecoveryReceipt.Confirmation.Duplicate
        assertEquals(RecoveryReceipt.State.FIRST_EFFECT, replay.status.state)
        receipts.invalidate()
        assertEquals(RecoveryReceipt.Confirmation.Rejected(RecoveryReceipt.Rejection.EXPIRED), confirm(RecoveryReceipt.State.HOST_BOUND))
    }

    @Test fun `rewriting the same file and restoring its original mtime rehashes it`() {
        val path = temp.newFile("host.apk").apply { writeText("a") }
        val mtime = java.nio.file.attribute.FileTime.fromMillis(1_000_000)
        Files.setLastModifiedTime(path.toPath(), mtime)
        val cache = InstalledDexIdentityCache({ InstalledDexIdentityCache.Installation(1, 10, listOf(path)) }) {
            it.single().readText().repeat(64)
        }
        assertEquals(host, cache.current("host"))
        path.writeText("b")
        Files.setLastModifiedTime(path.toPath(), mtime)
        assertEquals("b".repeat(64), cache.current("host")?.dexSha256)
    }

    @Test fun `an installation event invalidates a pending read without blocking other packages`() {
        val path = temp.newFile("first.apk").apply { writeText("a") }
        val other = temp.newFile("other.apk").apply { writeText("b") }
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val cache = InstalledDexIdentityCache({ pkg ->
            InstalledDexIdentityCache.Installation(1, 10, listOf(if (pkg == "host") path else other))
        }) {
            if (it.single() == path) {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
            it.single().readText().repeat(64)
        }
        val pool = Executors.newFixedThreadPool(2)
        try {
            val first = pool.submit<HostCodeIdentity?> { cache.current("host") }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val independent = pool.submit<HostCodeIdentity?> {
                cache.invalidate("host")
                cache.current("other")
            }
            assertEquals("b".repeat(64), independent.get(5, TimeUnit.SECONDS)?.dexSha256)
            release.countDown()
            assertNull(first.get(5, TimeUnit.SECONDS))
            assertEquals(host, cache.current("host"))
        } finally {
            release.countDown()
            pool.shutdownNow()
        }
    }

}
