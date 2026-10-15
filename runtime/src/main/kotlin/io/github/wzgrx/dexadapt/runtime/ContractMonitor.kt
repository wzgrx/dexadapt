package io.github.wzgrx.dexadapt.runtime

import io.github.wzgrx.dexadapt.Contract
import io.github.wzgrx.dexadapt.SymbolTable
import io.github.wzgrx.dexadapt.ValueRule
import java.util.concurrent.ConcurrentHashMap

/**
 * 观察模式（[Contract]）：本机适配出的新表，带契约的 Hook 点先只记录调用、不改行为；被调用够次数且都满足契约才放开，
 * 有一次不满足就判“未适配”。判定结果按表保存（[Store]），同一张表下次启动不用再观察。
 *
 * 线程安全；[record] 在 Hook 里调用，不抛异常（规则本身出错算不满足）。
 */
class ContractMonitor(
    private val contracts: Map<String, Contract>,
    /** 这张表的身份（[key]）；表换了（热适配）要新建一个。 */
    val tableKey: String,
    /** 把 `$id` 类型和类名解析成 Class（宿主的 ClassLoader + 符号表）；解析不了返回 null。 */
    private val classOf: (String) -> Class<*>?,
    private val store: Store? = null,
    private val log: (String) -> Unit = {},
) {
    enum class Verdict { OBSERVING, PASSED, VIOLATED }

    /** 判定结果的持久化（宿主进程自己的文件即可）。 */
    interface Store {
        /** 读这张表已经判定过的：符号 id → 结果（`passed` / `violated:原因`）。 */
        fun load(tableKey: String): Map<String, String>
        fun save(tableKey: String, verdicts: Map<String, String>)
    }

    private class Progress {
        @Volatile var calls = 0
        @Volatile var verdict = Verdict.OBSERVING
        @Volatile var reason: String? = null
    }

    private val progress = ConcurrentHashMap<String, Progress>()

    init {
        val saved = runCatching { store?.load(tableKey) }.getOrNull().orEmpty()
        for ((id, v) in saved) {
            if (id !in contracts) continue
            val p = Progress()
            when {
                v == "passed" -> p.verdict = Verdict.PASSED
                v.startsWith("violated") -> { p.verdict = Verdict.VIOLATED; p.reason = v.substringAfter(':', "") }
            }
            progress[id] = p
        }
    }

    fun contract(id: String): Contract? = contracts[id]

    fun verdict(id: String): Verdict = if (id !in contracts) Verdict.PASSED else progress[id]?.verdict ?: Verdict.OBSERVING

    fun reason(id: String): String? = progress[id]?.reason

    /** 已观察到的满足契约的调用次数。 */
    fun calls(id: String): Int = progress[id]?.calls ?: 0

    /**
     * 记录一次调用（方法正常返回后）。返回记录后的判定；判定变了（通过或违反）时保存。
     * [receiver] 是 this，[result] 是返回值，[onMainThread] 是这次调用是否在主线程。
     */
    fun record(id: String, receiver: Any?, args: List<Any?>, result: Any?, onMainThread: Boolean): Verdict {
        val c = contracts[id] ?: return Verdict.PASSED
        val p = progress.getOrPut(id) { Progress() }
        if (p.verdict != Verdict.OBSERVING) return p.verdict
        val problem = runCatching { check(c, receiver, args, result, onMainThread) }.getOrElse { "检查出错：${it.message}" }
        synchronized(p) {
            if (p.verdict != Verdict.OBSERVING) return p.verdict
            if (problem != null) {
                p.verdict = Verdict.VIOLATED
                p.reason = problem
                log("contract $id violated: $problem")
            } else if (++p.calls >= c.minCalls) {
                p.verdict = Verdict.PASSED
                log("contract $id passed after ${p.calls} calls")
            } else {
                return p.verdict
            }
        }
        persist()
        return p.verdict
    }

    private fun persist() {
        val snapshot = progress.filterValues { it.verdict != Verdict.OBSERVING }
            .mapValues { (_, p) -> if (p.verdict == Verdict.PASSED) "passed" else "violated:${p.reason.orEmpty()}" }
        runCatching { store?.save(tableKey, snapshot) }.onFailure { log("contract verdicts not saved: ${it.message}") }
    }

    /** 不满足的原因；满足时为 null。 */
    internal fun check(c: Contract, receiver: Any?, args: List<Any?>, result: Any?, onMainThread: Boolean): String? {
        c.mainThread?.let { want -> if (want != onMainThread) return if (want) "不在主线程被调用" else "在主线程被调用" }
        c.receiver?.let { rule -> fails(rule, receiver)?.let { return "this $it" } }
        for ((i, rule) in c.args) {
            if (i >= args.size) return "参数 $i 不存在（只有 ${args.size} 个）"
            fails(rule, args[i])?.let { return "参数 $i $it" }
        }
        c.returns?.let { rule -> fails(rule, result)?.let { return "返回值 $it" } }
        return null
    }

    private val classCache = ConcurrentHashMap<String, Result<Class<*>>>()

    private fun fails(rule: ValueRule, v: Any?): String? = when (rule) {
        ValueRule.NotNull -> if (v == null) "是 null" else null
        is ValueRule.InstanceOf -> {
            val cls = classCache.getOrPut(rule.of) { runCatching { classOf(rule.of) ?: error("找不到类 ${rule.of}") } }.getOrThrow()
            when {
                v == null -> "是 null，应该是 ${rule.of}"
                !cls.isInstance(v) -> "是 ${v.javaClass.name}，应该是 ${rule.of}"
                else -> null
            }
        }
        is ValueRule.Matches -> when {
            v == null -> "是 null，应该匹配 /${rule.regex}/"
            !Regex(rule.regex).matches(v.toString()) -> "“${v.toString().take(40)}”不匹配 /${rule.regex}/"
            else -> null
        }
        is ValueRule.Range -> {
            val n = when (v) {
                is Number -> v.toDouble()
                is Char -> v.code.toDouble()
                is Boolean -> if (v) 1.0 else 0.0
                else -> null
            }
            when {
                n == null -> "不是数字（${v?.javaClass?.name}）"
                n < rule.min || n > rule.max -> "是 $n，不在 ${rule.min}..${rule.max}"
                else -> null
            }
        }
    }

    companion object {
        /** 表的身份：同一份宿主代码（dex 摘要）+ 同一版查询（目录指纹）。 */
        fun key(table: SymbolTable): String = "${table.dexSha256.take(16)}-${table.catalog}"
    }
}
