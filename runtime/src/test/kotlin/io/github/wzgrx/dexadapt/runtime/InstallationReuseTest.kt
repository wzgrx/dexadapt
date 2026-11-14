package io.github.wzgrx.dexadapt.runtime

import io.github.wzgrx.dexadapt.ClassSymbol
import io.github.wzgrx.dexadapt.Resolved
import io.github.wzgrx.dexadapt.SymbolTable
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class InstallationReuseTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun privateRecordSurvivesProcessRecreationButNotReplacementOrCorruption() {
        val apk = temp.newFile("host.apk").apply { writeText("a") }
        val store = temp.newFolder("identities")
        var installation = InstalledDexIdentityCache.Installation(1, 100, listOf(apk))
        var reads = 0
        fun cache() = InstalledDexIdentityCache({ installation }, store) { files ->
            reads++
            files.single().readText().repeat(64)
        }
        assertEquals("a".repeat(64), cache().current("host")?.dexSha256)
        val count = reads
        assertEquals("a".repeat(64), cache().current("host")?.dexSha256)
        assertEquals(count, reads)
        val replacement = temp.newFile("replacement.apk").apply { writeText("b"); setLastModified(apk.lastModified()) }
        Files.move(replacement.toPath(), apk.toPath(), StandardCopyOption.REPLACE_EXISTING)
        assertEquals("b".repeat(64), cache().current("host")?.dexSha256)
        assertEquals(count + 1, reads)
        store.listFiles()!!.single { it.extension == "identity" }.writeText("corrupt")
        assertEquals("b".repeat(64), cache().current("host")?.dexSha256)
        assertEquals(count + 2, reads)
        installation = installation.copy(lastUpdateTime = 101)
        assertNotNull(cache().current("host"))
        assertEquals(count + 3, reads)
        cache().invalidate("host")
        assertNotNull(cache().current("host"))
        assertEquals(count + 4, reads)
    }

    @Test fun sameInodeRewriteAndSplitReorderCannotUseOldDigest() {
        val first = temp.newFile("first.apk").apply { writeText("a") }
        val second = temp.newFile("second.apk").apply { writeText("b") }
        val store = temp.newFolder("identities")
        var files = listOf(first, second)
        var reads = 0
        fun read() = InstalledDexIdentityCache({ InstalledDexIdentityCache.Installation(1, 10, files) }, store) {
            reads++
            (it[0].readText() + it[1].readText()).repeat(32)
        }.current("host")?.dexSha256
        assertEquals("ab".repeat(32), read())
        assertEquals("ab".repeat(32), read())
        assertEquals(1, reads)
        val original = Files.getLastModifiedTime(first.toPath())
        first.writeText("c")
        Files.setLastModifiedTime(first.toPath(), original)
        assertEquals("cb".repeat(32), read())
        files = files.reversed()
        assertEquals("bc".repeat(32), read())
        assertEquals(3, reads)
    }

    @Test fun distinctInstancesMergeConcurrentColdReadsAndUnwritableStoreFallsBack() {
        val apk = temp.newFile("host.apk").apply { writeText("a") }
        val store = temp.newFolder("identities")
        val reads = java.util.concurrent.atomic.AtomicInteger()
        fun cache(dir: java.io.File) = InstalledDexIdentityCache({ InstalledDexIdentityCache.Installation(1, 10, listOf(apk)) }, dir) {
            reads.incrementAndGet()
            it.single().readText().repeat(64)
        }
        val pool = Executors.newFixedThreadPool(2)
        try {
            val pending = List(2) { pool.submit<HostCodeIdentity?> { cache(store).current("host") } }
            assertEquals(pending[0].get(5, TimeUnit.SECONDS), pending[1].get(5, TimeUnit.SECONDS))
            assertEquals(1, reads.get())
        } finally { pool.shutdownNow() }
        assertNotNull(cache(temp.newFile("not-a-directory")).current("host"))
        assertEquals(2, reads.get())
    }

    @Test fun receiptOnlyUpdatePreservesLinkedObjectsAndPinnedSnapshots() {
        val table = SymbolTable(1, "a".repeat(64), mapOf("type" to Resolved.Class("java.lang.String")))
        val linker = SymbolLinker(table, javaClass.classLoader)
        val symbol = ClassSymbol("type", io.github.wzgrx.dexadapt.ClassQuery())
        val type = linker.clazz(symbol)
        assertEquals("type", linker.idOf(type))
        linker.withSnapshot {
            assertTrue(linker.updateRecoveryToken(table, "new"))
            assertEquals("new", linker.table?.recoveryToken)
            assertSame(type, linker.clazz(symbol))
            assertEquals("type", linker.idOf(type))
        }
        assertEquals("type", linker.idOf(type))
        assertFalse(linker.updateRecoveryToken(table, "stale"))
    }

    @Test fun readyWakesOnePendingEventWithItsOriginalTime() {
        var now = 1_000_000_000L
        val outbox = RecoveryOutbox { now }
        outbox.bind("token", 123)
        outbox.effect("token", 456)
        val first = outbox.next()!!
        assertEquals(250L, outbox.complete(first, RecoveryOutbox.Reply.TEMPORARY))
        assertNull(outbox.next())
        outbox.ready()
        val retry = outbox.next()!!
        assertEquals(first, retry)
        outbox.ready()
        assertNull(outbox.next())
        outbox.complete(retry, RecoveryOutbox.Reply.ACCEPTED)
        val effect = outbox.next()!!
        assertEquals(456L, effect.observed)
        outbox.complete(effect, RecoveryOutbox.Reply.ACCEPTED)
        now += 5_000_000_000L
        outbox.ready()
        assertNull(outbox.next())
    }

    @Test fun lateTransportFailureCannotOverwriteAReadyWakeup() {
        val outbox = RecoveryOutbox { 1_000_000_000L }
        outbox.bind("token", 123)
        outbox.effect("token", 456)
        val beforeReady = outbox.next()!!
        outbox.ready()
        assertEquals(0L, outbox.complete(beforeReady, RecoveryOutbox.Reply.TEMPORARY))
        val retry = outbox.next()!!
        assertEquals(beforeReady, retry)
        assertEquals(250L shl 1, outbox.complete(retry, RecoveryOutbox.Reply.TEMPORARY))
        assertNull(outbox.next())
    }
}
