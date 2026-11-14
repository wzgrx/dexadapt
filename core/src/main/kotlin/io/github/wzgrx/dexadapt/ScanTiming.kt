package io.github.wzgrx.dexadapt

import kotlinx.serialization.Serializable

/** 只计耗时和稳定符号ID，不记录宿主参数。观察者失败不影响适配正确性。 */
class ScanTiming(
    private val clock: () -> Long = System::nanoTime,
    private val cpuClock: () -> Long? = { null },
    private val emit: (Sample) -> Unit = {},
) {
    @Serializable data class Sample(val phase: String, val elapsedNanos: Long, val cpuNanos: Long? = null,
                      val symbol: String? = null, val cached: Boolean = false, val selfNanos: Long? = null, val count: Long = 1)
    fun now(): Long = clock()
    fun cpu(): Long? = if (this === NONE) null else cpuClock()
    fun record(phase: String, elapsed: Long, cpu: Long? = null, symbol: String? = null, cached: Boolean = false, selfNanos: Long? = null, count: Long = 1) {
        if (this === NONE) return
        runCatching { emit(Sample(phase, elapsed.coerceAtLeast(0), cpu?.coerceAtLeast(0), symbol, cached, selfNanos, count)) }
    }
    fun <T> measure(phase: String, symbol: String? = null, cached: Boolean = false, block: () -> T): T {
        if (this === NONE) return block()
        val start = clock()
        val cpu = cpuClock()
        return try { block() } finally {
            record(phase, clock() - start, cpu?.let { before -> cpuClock()?.minus(before) }, symbol, cached)
        }
    }
    companion object { val NONE = ScanTiming() }
}
