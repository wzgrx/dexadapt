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
    /** 使用同一设备单调时钟。跨进程补报可早于本次交付，保留相对交付的负偏移。 */
    @Synchronized fun observe(step: Step, at: Long): Sample = Sample(step, marks.getOrPut(step) {
        at.coerceIn(0L, clock().coerceAtLeast(0L)) - started
    })
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

    enum class Rejection { EXPIRED, IDENTITY_CHANGED, CATALOG_CHANGED, INVALID_TRANSITION }

    sealed interface Confirmation {
        data class Advanced(val status: Status) : Confirmation
        data class Duplicate(val status: Status) : Confirmation
        data class Rejected(val reason: Rejection) : Confirmation
    }

    /** 在一次锁内验证并推进。只有 [Confirmation.Advanced] 应触发记录或重试。 */
    @Synchronized fun confirm(token: String, identity: HostCodeIdentity, catalog: String, state: State): Confirmation {
        val current = latest?.takeIf { it.delivery.token == token } ?: return Confirmation.Rejected(Rejection.EXPIRED)
        if (current.delivery.identity != identity) return Confirmation.Rejected(Rejection.IDENTITY_CHANGED)
        if (current.delivery.catalog != catalog) return Confirmation.Rejected(Rejection.CATALOG_CHANGED)
        return advance(current, state)
    }

    @Synchronized fun acknowledge(delivery: Delivery, state: State): Boolean {
        val current = latest?.takeIf { it.delivery == delivery } ?: return false
        return advance(current, state) !is Confirmation.Rejected
    }

    @Synchronized fun acknowledge(token: String, state: State): Boolean {
        val current = latest?.takeIf { it.delivery.token == token } ?: return false
        return advance(current, state) !is Confirmation.Rejected
    }

    private fun advance(current: Status, state: State): Confirmation {
        if (state == State.DELIVERED) return Confirmation.Rejected(Rejection.INVALID_TRANSITION)
        if (current.state == state || (state == State.HOST_BOUND && current.state == State.FIRST_EFFECT)) {
            return Confirmation.Duplicate(current)
        }
        val allowed = when (state) {
            State.HOST_BOUND -> current.state == State.DELIVERED
            State.FIRST_EFFECT -> current.state == State.HOST_BOUND
            State.RETRYABLE_FAILURE -> current.state == State.DELIVERED || current.state == State.HOST_BOUND
            State.DELIVERED -> false
        }
        if (!allowed) return Confirmation.Rejected(Rejection.INVALID_TRANSITION)
        val next = current.copy(state = state)
        latest = next
        return Confirmation.Advanced(next)
    }

    @Synchronized fun invalidate() { latest = null }
    @Synchronized fun status(): Status? = latest
}
