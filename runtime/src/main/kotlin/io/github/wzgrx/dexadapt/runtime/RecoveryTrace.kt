package io.github.wzgrx.dexadapt.runtime

/** 时间来自同一单调时钟。跨进程时双方应使用 Android elapsedRealtimeNanos。 */
class RecoveryTrace(
    private val clock: () -> Long = System::nanoTime,
    private val started: Long = clock(),
) {
    enum class Step { REQUESTED, WORK_STARTED, IDENTITY_VERIFIED, SCAN_STARTED, PRIORITY_READY, TABLE_READY,
        TABLE_DELIVERED, HOST_BOUND, FIRST_EFFECT }

    data class Sample(val step: Step, val sinceRequestNanos: Long)
    private val marks = linkedMapOf(Step.REQUESTED to 0L)

    @Synchronized fun mark(step: Step): Long = marks.getOrPut(step) { (clock() - started).coerceAtLeast(0L) }
    @Synchronized fun snapshot(): List<Sample> = marks.map { (step, time) -> Sample(step, time) }
}

/** 一个宿主的最新交付状态。调用方在已鉴权的桥接上转发确认，不以表写入代替宿主生效。 */
class RecoveryReceipt {
    data class Delivery(val session: String, val generation: Long, val identity: HostCodeIdentity, val catalog: String, val partial: Boolean) {
        val token: String get() = "$session:$generation"
    }
    enum class State { DELIVERED, HOST_BOUND, FIRST_EFFECT, RETRYABLE_FAILURE }
    data class Status(val delivery: Delivery, val state: State)
    private val session = java.util.UUID.randomUUID().toString()
    private var generation = 0L
    private var latest: Status? = null

    @Synchronized fun publish(identity: HostCodeIdentity, catalog: String, partial: Boolean): Delivery {
        require(catalog.isNotBlank())
        val next = Delivery(session, ++generation, identity, catalog, partial)
        latest = Status(next, State.DELIVERED)
        return next
    }

    @Synchronized fun acknowledge(delivery: Delivery, state: State): Boolean {
        val current = latest ?: return false
        if (current.delivery != delivery || state == State.DELIVERED) return false
        if (current.state == state) return true
        val allowed = when (state) {
            State.HOST_BOUND -> current.state == State.DELIVERED
            State.FIRST_EFFECT -> current.state == State.HOST_BOUND
            State.RETRYABLE_FAILURE -> current.state == State.DELIVERED || current.state == State.HOST_BOUND
            State.DELIVERED -> false
        }
        if (allowed) latest = Status(delivery, state)
        return allowed
    }

    @Synchronized fun acknowledge(token: String, state: State): Boolean {
        val delivery = latest?.delivery ?: return false
        return delivery.token == token && acknowledge(delivery, state)
    }

    @Synchronized fun invalidate() { latest = null }
    @Synchronized fun status(): Status? = latest
}
