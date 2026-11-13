package io.github.wzgrx.dexadapt.runtime

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.StandardCopyOption

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
        assertEquals(1, reads)
        Files.move(replacement.toPath(), path.toPath(), StandardCopyOption.REPLACE_EXISTING)
        assertEquals("b".repeat(64), cache.current("host")?.dexSha256)
        assertEquals(2, reads)
    }
}
