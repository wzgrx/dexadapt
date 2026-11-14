package io.github.wzgrx.dexadapt

import kotlinx.serialization.Serializable

/** 只计耗时和稳定符号ID，不记录宿主参数。观察者失败不影响适配正确性。 */
class ScanTiming(
    private val clock: () -> Long = System::nanoTime,
    private val cpuClock: () -> Long? = { null },
    private val emit: (Sample) -> Unit = {},
) {
    @Serializable data class Sample(val phase: String, val elapsedNanos: Long, val cpuNanos: Long? = null,
                      val symbol: String? = null, val cached: Boolean = false, val selfNanos: Long? = null)
    fun now(): Long = clock()
    fun cpu(): Long? = cpuClock()
    fun record(phase: String, elapsed: Long, cpu: Long? = null, symbol: String? = null, cached: Boolean = false, selfNanos: Long? = null) {
        runCatching { emit(Sample(phase, elapsed.coerceAtLeast(0), cpu?.coerceAtLeast(0), symbol, cached, selfNanos)) }
    }
    fun <T> measure(phase: String, symbol: String? = null, cached: Boolean = false, block: () -> T): T {
        val start = clock()
        val cpu = cpuClock()
        return try { block() } finally {
            record(phase, clock() - start, cpu?.let { before -> cpuClock()?.minus(before) }, symbol, cached)
        }
    }
    companion object { val NONE = ScanTiming() }
}
