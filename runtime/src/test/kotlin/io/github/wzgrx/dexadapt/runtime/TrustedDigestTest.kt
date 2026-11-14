package io.github.wzgrx.dexadapt.runtime

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class TrustedDigestTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun separatePrivateCachesReuseOnlyACompleteTicketForTheCurrentInstallation() {
        val apk = temp.newFile("host.apk").apply { writeText("a") }
        var installed = InstalledDexIdentityCache.Installation(1, 10, listOf(apk))
        val channel = ConcurrentHashMap<String, String>()
        var producerReads = 0; var consumerReads = 0
        fun producer() = InstalledDexIdentityCache({ installed }, temp.newFolder()) {
            producerReads++; it.single().readText().repeat(64)
        }.withTickets(publish = { name, raw -> channel[name] = raw; true })
        fun consumer(read: (String) -> String? = channel::get) = InstalledDexIdentityCache({ installed }, temp.newFolder()) {
            consumerReads++; it.single().readText().repeat(64)
        }.withTickets(read = read)
        val first = producer().current("host")
        assertEquals(first, consumer().current("host"))
        assertEquals(1, producerReads); assertEquals(0, consumerReads)
        assertEquals(first, consumer { null }.current("host"))
        assertEquals(1, consumerReads)
        installed = installed.copy(versionCode = 2, lastUpdateTime = 11)
        assertEquals(2L, consumer().current("host")?.versionCode)
        assertEquals(2, consumerReads)
        val replacement = temp.newFile("new.apk").apply { writeText("b"); setLastModified(apk.lastModified()) }
        Files.move(replacement.toPath(), apk.toPath(), StandardCopyOption.REPLACE_EXISTING)
        assertEquals("b".repeat(64), consumer().current("host")?.dexSha256)
        assertEquals(3, consumerReads)
    }

    @Test fun pendingReadIsMergedAndFailedOrForgedDocumentsCannotProduceIdentity() {
        val apk = temp.newFile("host.apk").apply { writeText("a") }
        val installed = InstalledDexIdentityCache.Installation(1, 10, listOf(apk))
        val channel = ConcurrentHashMap<String, String>()
        val began = CountDownLatch(1); val proceed = CountDownLatch(1)
        var fullReads = 0
        val owner = InstalledDexIdentityCache({ installed }, temp.newFolder()) {
            began.countDown(); check(proceed.await(5, TimeUnit.SECONDS)); it.single().readText().repeat(64)
        }.withTickets(publish = { n, raw -> channel[n] = raw; true })
        val host = InstalledDexIdentityCache({ installed }, temp.newFolder()) {
            fullReads++; it.single().readText().repeat(64)
        }.withTickets(read = channel::get)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val first = pool.submit<HostCodeIdentity?> { owner.current("host") }
            assertTrue(began.await(5, TimeUnit.SECONDS))
            val next = pool.submit<HostCodeIdentity?> { host.current("host") }
            proceed.countDown()
            assertEquals(first.get(5, TimeUnit.SECONDS), next.get(5, TimeUnit.SECONDS))
            assertEquals(0, fullReads)
        } finally { proceed.countDown(); pool.shutdownNow() }
        channel.clear()
        val forged = InstalledDexIdentityCache({ installed }, temp.newFolder()) {
            fullReads++; "a".repeat(64)
        }.withTickets(read = { name ->
            if (name.startsWith("identity-ticket")) VerifiedDigestTicket(packageName="other", versionCode=1,
                installation="b".repeat(64), dexSha256="c".repeat(64)).toJson() else null
        })
        assertEquals("a".repeat(64), forged.current("host")?.dexSha256)
        assertEquals(1, fullReads)
    }

    @Test fun stageEpochSequenceCodeAndCatalogAreAllRequired() {
        val host = HostCodeIdentity("host", 1, "a".repeat(64))
        val gate = StageNoticeGate(host,"cat")
        fun msg(epoch: String="old", seq: Long=1) = StageNotice(epoch, seq, "host", 1, host.dexSha256, "cat", true, 10)
        assertFalse(gate.accept(msg()))
        gate.subscribe("old")
        assertTrue(gate.accept(msg()))
        assertFalse(gate.accept(msg()))
        assertFalse(gate.accept(msg(seq=2).copy(dexSha256="b".repeat(64))))
        assertFalse(gate.accept(msg(seq=2).copy(catalog="other")))
        assertTrue(gate.accept(msg(seq=2).copy(partial=false)))
        gate.subscribe("new")
        assertFalse(gate.accept(msg(seq=3)))
        assertTrue(gate.accept(msg("new")))
    }

    @Test fun failedProducerLeaseExpiresEvenWhenTimingCollectionIsDisabled() {
        val apk=temp.newFile("failed.apk").apply { writeText("a") }
        val installed=InstalledDexIdentityCache.Installation(1,10,listOf(apk))
        val channel=ConcurrentHashMap<String,String>()
        val owner=InstalledDexIdentityCache({installed},temp.newFolder()) { throw java.io.IOException("interrupted producer") }
            .withTickets(publish={n,raw->channel[n]=raw;true})
        assertNull(owner.current("host"))
        var reads=0
        val host=InstalledDexIdentityCache({installed},temp.newFolder()) { reads++; "a".repeat(64) }
            .withTickets(read=channel::get)
        val pool=Executors.newSingleThreadExecutor()
        try {
            val result=pool.submit<HostCodeIdentity?> { host.current("host") }.get(5,TimeUnit.SECONDS)
            assertEquals("a".repeat(64),result?.dexSha256)
            assertEquals(1,reads)
        } finally {pool.shutdownNow()}
    }
}
