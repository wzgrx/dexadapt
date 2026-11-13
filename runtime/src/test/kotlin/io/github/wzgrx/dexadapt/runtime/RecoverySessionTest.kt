package io.github.wzgrx.dexadapt.runtime

import io.github.wzgrx.dexadapt.runtime.RecoveryReceipt.State
import io.github.wzgrx.dexadapt.runtime.RecoverySession.Decision
import org.junit.Assert.*
import org.junit.Test

class RecoverySessionTest {
    private val host = HostCodeIdentity("fixture", 1, "a".repeat(64))

    @Test fun oneEffectSurvivesTransportFailureAndModuleRestart() {
        var now = 1_000_000_000L
        val outbox = RecoveryOutbox { now }
        var module = RecoverySession { now }
        val ticket = module.publish(host, "spec", false)
        outbox.bind(ticket.token)
        val binding = outbox.next()!!
        assertEquals(Decision.ACCEPTED, module.confirm(binding.token, host, "spec", binding.state, binding.observed).decision)
        outbox.complete(binding, RecoveryOutbox.Reply.ACCEPTED)
        now += 123
        outbox.effect(ticket.token)
        val effect = outbox.next()!!
        assertEquals(State.FIRST_EFFECT, effect.state)
        assertEquals(250L, outbox.complete(effect, RecoveryOutbox.Reply.TEMPORARY))
        assertNull(outbox.next())
        now += 250_000_000L
        module = RecoverySession { now }
        val retry = outbox.next()!!
        assertEquals(effect, retry)
        assertEquals(Decision.EXPIRED, module.confirm(retry.token, host, "spec", retry.state, retry.observed).decision)
        val renewed = module.publish(host, "spec", false)
        assertTrue(outbox.renew(ticket.token, renewed.token))
        val rebound = outbox.next()!!
        assertEquals(State.HOST_BOUND, rebound.state)
        outbox.complete(rebound, RecoveryOutbox.Reply.ACCEPTED)
        val delivered = outbox.next()!!
        assertEquals(renewed.token, delivered.token)
        assertEquals(effect.observed, delivered.observed)
        outbox.complete(delivered, RecoveryOutbox.Reply.ACCEPTED)
        assertNull(outbox.next())
        assertFalse(outbox.needsEffect(renewed.token))
    }

    @Test fun onlyNewFailureConsumesBudgetAndNewIdentityResetsIt() {
        val session = RecoverySession()
        repeat(3) { attempt ->
            val d = session.publish(host, "spec", false)
            val expected = if (attempt < 2) Decision.RETRY else Decision.STOP
            assertEquals(expected, session.confirm(d.token, host, "spec", State.RETRYABLE_FAILURE, 0).decision)
            repeat(2) { assertEquals(Decision.DUPLICATE, session.confirm(d.token, host, "spec", State.RETRYABLE_FAILURE, 0).decision) }
        }
        val newer = host.copy(dexSha256 = "b".repeat(64))
        val d = session.publish(newer, "spec", false)
        assertEquals(Decision.RETRY, session.confirm(d.token, newer, "spec", State.RETRYABLE_FAILURE, 0).decision)
    }

    @Test fun renewalIgnoresDeliveryMetadataButNotResolvedTargets() {
        val t = io.github.wzgrx.dexadapt.SymbolTable(1, host.dexSha256,
            mapOf("a" to io.github.wzgrx.dexadapt.Resolved.Class("example.A")), packageName = host.packageName)
        assertEquals(recoveryTableFingerprint(t), recoveryTableFingerprint(t.copy(apk = "new-path", module = "new", recoveryToken = "new")))
        assertNotEquals(recoveryTableFingerprint(t), recoveryTableFingerprint(t.copy(entries = emptyMap())))
    }
}
