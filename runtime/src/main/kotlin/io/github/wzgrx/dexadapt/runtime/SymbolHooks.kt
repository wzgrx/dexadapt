package io.github.wzgrx.dexadapt.runtime

import io.github.wzgrx.dexadapt.MethodSymbol
import java.lang.reflect.Executable

/**
 * 和 Hook 框架无关的“符号 → Hook”（设计 11.2 第 8 点）：按符号表找到成员、找不到时跳过并说明原因、接上观察模式
 * （[ContractMonitor]）。各框架的适配（dexadapt-hooks-libxposed、-xposed、-yuki、-ezxhelper）只负责把 [Gate]
 * 接到自己的回调上，模块原来的 Hook 写法不用改。
 *
 * [contracts] 每次调用时取（热适配换了表，观察器也跟着换）；没有观察器时所有闸门都是开的。
 */
class SymbolHooks(
    val linker: SymbolLinker,
    private val contracts: () -> ContractMonitor? = { null },
    private val log: (String) -> Unit = {},
) {
    /**
     * 一个 Hook 点的闸门：[open] 为假时（还在观察，或契约不符）模块的回调不该跑，适配层只调用原方法、
     * 原方法返回后调 [record] 记录这次调用。
     */
    inner class Gate internal constructor(val symbolId: String) {
        val open: Boolean
            get() = contracts()?.verdict(symbolId)?.let { it == ContractMonitor.Verdict.PASSED } ?: true

        /** 契约不符的原因（不符时这个点在本版本一直不放开）。 */
        val problem: String?
            get() = contracts()?.takeIf { it.verdict(symbolId) == ContractMonitor.Verdict.VIOLATED }?.reason(symbolId)

        /** 原方法正常返回后调用（只在观察中有用；不抛异常）。 */
        fun record(thisObject: Any?, args: List<Any?>, result: Any?, onMainThread: Boolean) {
            val m = contracts() ?: return
            if (m.verdict(symbolId) != ContractMonitor.Verdict.OBSERVING) return
            runCatching { m.record(symbolId, thisObject, args, result, onMainThread) }
        }
    }

    /**
     * 找到 [symbol] 的成员交给 [install]（框架的挂钩代码）；成员找不到时不挂，返回 null 并记一条日志，
     * 模块其余部分照常运行。
     */
    fun <R> hook(symbol: MethodSymbol, install: (Executable, Gate) -> R): R? {
        val member = runCatching { linker.method(symbol) }.getOrElse {
            log("hook ${symbol.id} skipped: ${it.message}")
            return null
        }
        return install(member, Gate(symbol.id))
    }

    /** 和 [hook] 一样，但只要闸门（例如框架自己找好了成员，只想接观察模式）。 */
    fun gate(symbol: MethodSymbol): Gate = Gate(symbol.id)

    companion object {
        /** 当前线程是不是主线程（不依赖 Android：没有 Looper 的环境一律算否）。 */
        fun onMainThread(): Boolean = runCatching {
            val looper = Class.forName("android.os.Looper")
            looper.getMethod("myLooper").invoke(null) == looper.getMethod("getMainLooper").invoke(null)
        }.getOrDefault(false)
    }
}
