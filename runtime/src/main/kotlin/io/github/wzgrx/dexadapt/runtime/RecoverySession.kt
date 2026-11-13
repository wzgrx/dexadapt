package io.github.wzgrx.dexadapt.runtime

import java.util.concurrent.TimeUnit

/** 单个宿主的交付确认及有限重试预算；调用方只在 [Decision.RETRY] 时安排一次工作。 */
class RecoverySession(private val clock: () -> Long = System::nanoTime) {
    enum class Decision { ACCEPTED, DUPLICATE, RETRY, STOP, EXPIRED, IDENTITY_CHANGED, CATALOG_CHANGED, INVALID }
    data class Confirmation(val decision: Decision, val event: RecoveryTrace.Sample? = null)
    private val receipt = RecoveryReceipt()
    private var failures = 0
    private var scope: Pair<HostCodeIdentity, String>? = null
    private var trace = RecoveryTrace(clock)

    @Synchronized fun publish(identity: HostCodeIdentity, catalog: String, partial: Boolean,
                              trace: RecoveryTrace = RecoveryTrace(clock)): RecoveryReceipt.Delivery {
        val key = identity to catalog
        if (scope != key) failures = 0
        scope = key
        this.trace = trace
        return receipt.publish(identity, catalog, partial)
    }

    @Synchronized fun delivery(): RecoveryReceipt.Delivery? = receipt.status()?.delivery
    @Synchronized fun failurePending(): Boolean = receipt.status()?.state == RecoveryReceipt.State.RETRYABLE_FAILURE

    @Synchronized fun confirm(token: String, identity: HostCodeIdentity, catalog: String, state: RecoveryReceipt.State,
                              observed: Long): Confirmation = when (val reply = receipt.confirm(token, identity, catalog, state)) {
        is RecoveryReceipt.Confirmation.Duplicate -> Confirmation(Decision.DUPLICATE)
        is RecoveryReceipt.Confirmation.Rejected -> Confirmation(when (reply.reason) {
            RecoveryReceipt.Rejection.EXPIRED -> Decision.EXPIRED
            RecoveryReceipt.Rejection.IDENTITY_CHANGED -> Decision.IDENTITY_CHANGED
            RecoveryReceipt.Rejection.CATALOG_CHANGED -> Decision.CATALOG_CHANGED
            RecoveryReceipt.Rejection.INVALID_TRANSITION -> Decision.INVALID
        })
        is RecoveryReceipt.Confirmation.Advanced -> when (state) {
            RecoveryReceipt.State.HOST_BOUND -> Confirmation(Decision.ACCEPTED, trace.observe(RecoveryTrace.Step.HOST_BOUND, observed))
            RecoveryReceipt.State.FIRST_EFFECT -> {
                failures = 0
                Confirmation(Decision.ACCEPTED, trace.observe(RecoveryTrace.Step.FIRST_EFFECT, observed))
            }
            RecoveryReceipt.State.RETRYABLE_FAILURE -> Confirmation(
                if (reply.status.delivery.partial) Decision.STOP else if (++failures <= 2) Decision.RETRY else Decision.STOP)
            RecoveryReceipt.State.DELIVERED -> error("Invalid receipt state")
        }
    }

    @Synchronized fun invalidate() {
        receipt.invalidate()
        scope = null
        failures = 0
    }
}

/** 宿主内存待发箱。保留原始效果时间；即使功能只触发一次，连接恢复后也能补发。 */
class RecoveryOutbox(private val clock: () -> Long = System::nanoTime) {
    enum class Reply { ACCEPTED, TEMPORARY, RENEW, REJECTED }
    data class Message(val token: String, val state: RecoveryReceipt.State, val observed: Long)
    private data class Target(
        val token: String,
        val boundAt: Long,
        var effectAt: Long? = null,
        var bound: Boolean = false,
        var done: Boolean = false,
        var failures: Int = 0,
        var inFlight: Message? = null,
        var epoch: Long = 0,
    )
    private var target: Target? = null

    @Synchronized fun bind(token: String) {
        require(token.isNotBlank())
        if (target?.token != token) target = Target(token, clock())
    }

    @Synchronized fun needsEffect(token: String): Boolean =
        target?.let { it.token != token || !it.done && it.effectAt == null } ?: true

    @Synchronized fun effect(token: String) {
        if (target?.token != token) bind(token)
        target?.let { if (!it.done && it.effectAt == null) it.effectAt = clock() }
    }

    /** 仅当使用方确认新令牌对应原来已经绑定的同一张表时调用。 */
    @Synchronized fun renew(previous: String, next: String): Boolean {
        val old = target?.takeIf { it.token == previous && !it.done } ?: return false
        require(next.isNotBlank() && next != previous)
        target = Target(next, old.boundAt, old.effectAt)
        return true
    }

    @Synchronized fun next(): Message? {
        val t = target ?: return null
        if (t.done || t.inFlight != null || clock() < t.epoch) return null
        val event = if (!t.bound) Message(t.token, RecoveryReceipt.State.HOST_BOUND, t.boundAt)
            else Message(t.token, RecoveryReceipt.State.FIRST_EFFECT, t.effectAt ?: return null)
        t.inFlight = event
        return event
    }

    @Synchronized fun complete(message: Message, reply: Reply): Long? {
        val t = target?.takeIf { it.inFlight === message } ?: return null
        t.inFlight = null
        when (reply) {
            Reply.ACCEPTED -> {
                t.failures = 0
                t.epoch = 0
                if (message.state == RecoveryReceipt.State.HOST_BOUND) t.bound = true else t.done = true
            }
            Reply.TEMPORARY, Reply.RENEW -> {
                val delay = 250L shl t.failures.coerceAtMost(5)
                t.failures = (t.failures + 1).coerceAtMost(5)
                t.epoch = clock() + TimeUnit.MILLISECONDS.toNanos(delay)
                return delay
            }
            Reply.REJECTED -> t.done = true
        }
        return null
    }
}

/** 续发只认同一内容，安装路径、生成时间和旧令牌不参与。目录由模块另行核对。 */
fun recoveryTableFingerprint(table: io.github.wzgrx.dexadapt.SymbolTable): String {
    val stable = table.copy(entries = table.entries.toSortedMap(), missing = table.missing.toSortedMap(),
        absent = table.absent.sorted(), guesses = table.guesses.toSortedMap(),
        catalog = "", generatedAt = 0, module = "", apk = "", seconds = 0, reason = "", recoveryToken = "")
    return io.github.wzgrx.dexadapt.Hex.of(java.security.MessageDigest.getInstance("SHA-256").digest(stable.toJson().toByteArray()))
}
