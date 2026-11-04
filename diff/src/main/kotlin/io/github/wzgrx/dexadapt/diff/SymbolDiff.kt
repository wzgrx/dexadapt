package io.github.wzgrx.dexadapt.diff

import io.github.wzgrx.dexadapt.Resolved
import io.github.wzgrx.dexadapt.SymbolTable
import io.github.wzgrx.dexadapt.dexlib.DexIndex
import io.github.wzgrx.dexadapt.resolve.AccessFlag
import io.github.wzgrx.dexadapt.resolve.MethodView
import kotlinx.serialization.Serializable

/**
 * 模块视角：spec 里每个符号在旧版、新版各解析到哪里。新版解析失败的，按混淆映射给出“推测的新位置”，
 * 方便判断是查询坏了（位置还在、只是特征变了）还是功能没了。
 */
@Serializable
data class SymbolDiff(val rows: List<Row>) {
    @Serializable
    data class Row(
        val id: String,
        val status: Status,
        val old: String?,
        val new: String?,
        /** 新版失败的原因（解析器给的）。 */
        val reason: String? = null,
        /** 新版失败时，按混淆映射推测的位置。 */
        val guess: String? = null,
        /** 方法：新旧操作码序列的相似度（不代表参数/常量/分支语义相同），0～1；低说明“找到了但逻辑可能变了”，要复核 Hook 的写法。 */
        val bodySimilarity: Double? = null,
        /** 方法：Hook 可能不生效的风险（太小可能被 ART 内联、抽象方法……）。 */
        val hookRisk: String? = null,
        val semanticEvidence:List<String> = emptyList(),
        val beforeStringLoads:List<String> = emptyList(),
        val afterStringLoads:List<String> = emptyList(),
    )

    enum class Status {
        /** 两边位置一样。 */
        SAME,
        /** 名字变了（混淆改名），查询跟上了。 */
        MOVED,
        /** 新版解析不到。 */
        LOST,
        /** 旧版没有、新版有。 */
        GAINED,
        /** 两边都没有。 */
        ABSENT,
    }

    val counts: Map<Status, Int> get() = rows.groupingBy { it.status }.eachCount()

    companion object {
        fun of(old: SymbolTable, new: SymbolTable, mapping: ClassMatcher.Result?, oldIndex: DexIndex? = null, newIndex: DexIndex? = null): SymbolDiff {
            val callers = newIndex?.let { idx -> callerCounts(idx, new.entries.values.filterIsInstance<Resolved.Method>().map(::text).toSet()) }.orEmpty()
            val ids = (old.entries.keys + old.missing.keys + new.entries.keys + new.missing.keys).toSortedSet()
            val methodMap = mapping?.methods?.associate { it.old to it.new }.orEmpty()
            return SymbolDiff(ids.map { id ->
                val a = old.entries[id]
                val b = new.entries[id]
                when {
                    a != null && b != null -> {
                        val ma = (a as? Resolved.Method)?.let { method(oldIndex, it) }
                        val mb = (b as? Resolved.Method)?.let { method(newIndex, it) }
                        Row(
                            id, if (a == b) Status.SAME else Status.MOVED, text(a), text(b),
                            bodySimilarity = if (ma != null && mb != null) BodySimilarity.of(ma.opcodes, mb.opcodes) else null,
                            hookRisk = mb?.let { HookRisk.of(it, callers[text(b)] ?: 0) },
                            semanticEvidence = if(ma!=null && mb!=null)buildList {
                                if(ma.orderedStrings!=mb.orderedStrings)add("字符串加载顺序变化，需核对extra键/比较值的角色")
                                if(ma.strings!=mb.strings)add("字符串常量集合变化，需核对键/值角色")
                                if(ma.numbers!=mb.numbers)add("数字常量集合变化，需核对开关code/selector")
                                if(ma.calls!=mb.calls)add("调用签名集合变化，可能包含混淆改名；需核对真实调用")
                                if(ma.fieldRefs!=mb.fieldRefs)add("字段引用集合变化，需核对数据流")
                                add("操作码相似度不证明参数含义或分支语义等价")
                            } else emptyList(),
                            beforeStringLoads=if(ma!=null && mb!=null && ma.orderedStrings!=mb.orderedStrings)ma.orderedStrings.take(32).map { it.take(256) } else emptyList(),
                            afterStringLoads=if(ma!=null && mb!=null && ma.orderedStrings!=mb.orderedStrings)mb.orderedStrings.take(32).map { it.take(256) } else emptyList(),
                        )
                    }
                    a != null -> Row(id, Status.LOST, text(a), null, new.missing[id], mapping?.let { guess(a, it.byOld, methodMap) })
                    b != null -> Row(id, Status.GAINED, null, text(b))
                    else -> Row(id, Status.ABSENT, null, null, new.missing[id])
                }
            })
        }

        private fun method(index: DexIndex?, r: Resolved.Method) =
            index?.classes?.get(r.owner)?.methods?.firstOrNull { it.name == r.name && it.params == r.params && it.returns == r.returns }

        /** [targets]（方法签名）各被多少个方法调用。 */
        private fun callerCounts(index: DexIndex, targets: Set<String>): Map<String, Int> {
            val counts = HashMap<String, Int>()
            for (c in index.classes.values) for (m in c.methods) for (call in m.calls) if (call in targets) counts.merge(call, 1, Int::plus)
            return counts
        }

        fun text(r: Resolved): String = when (r) {
            is Resolved.Class -> r.name
            is Resolved.Method -> "${r.owner}#${r.name}(${r.params.joinToString(",")})${r.returns}"
            is Resolved.Field -> "${r.owner}#${r.name}:${r.type}"
        }

        private fun guess(r: Resolved, classes: Map<String, String>, methods: Map<String, String>): String? = when (r) {
            is Resolved.Class -> classes[r.name]
            is Resolved.Method -> methods["${r.owner}#${r.name}(${r.params.joinToString(",")})${r.returns}"]
                ?: classes[r.owner]?.let { "$it#?（类配上了，方法没有唯一对应）" }
            is Resolved.Field -> classes[r.owner]?.let { "$it#?:${classes[r.type] ?: r.type}" }
        }
    }
}

/** ProGuard 格式的映射（jadx 的“加载映射”、retrace 能直接用）。 */
object MappingWriter {
    /**
     * 旧名 → 新名：在 jadx 里打开新版、加载它，新版的混淆名显示成旧版的名字，旧版里的笔记和理解能直接沿用。
     * [names] 可以再给旧版的类起可读的名字（例如模块的符号 id），有的话用它代替旧名。
     */
    fun proguard(result: ClassMatcher.Result, names: Map<String, String> = emptyMap()): String = buildString {
        val methodsByClass = result.methods.groupBy { it.old.substringBefore('#') }
        for (p in result.classes.sortedBy { it.old }) {
            if (p.old == p.new && p.old !in names) continue
            appendLine("${names[p.old] ?: p.old} -> ${p.new}:")
            for (m in methodsByClass[p.old].orEmpty()) {
                val oldName = m.old.substringAfter('#').substringBefore('(')
                val newName = m.new.substringAfter('#').substringBefore('(')
                if (oldName == newName) continue
                val params = m.old.substringAfter('(').substringBefore(')')
                val ret = m.old.substringAfterLast(')')
                appendLine("    $ret $oldName($params) -> $newName")
            }
        }
    }
}

/** 两段指令序列的相似度（0～1）：短的用最长公共子序列，长的用操作码直方图。 */
object BodySimilarity {
    fun of(a: List<String>, b: List<String>): Double {
        if (a.isEmpty() && b.isEmpty()) return 1.0
        if (a.isEmpty() || b.isEmpty()) return 0.0
        if (a.size.toLong() * b.size > 4_000_000) {
            val ha = a.groupingBy { it }.eachCount()
            val hb = b.groupingBy { it }.eachCount()
            val inter = (ha.keys + hb.keys).sumOf { minOf(ha[it] ?: 0, hb[it] ?: 0) }
            return 2.0 * inter / (a.size + b.size)
        }
        var prev = IntArray(b.size + 1)
        var cur = IntArray(b.size + 1)
        for (i in 1..a.size) {
            for (j in 1..b.size) cur[j] = if (a[i - 1] == b[j - 1]) prev[j - 1] + 1 else maxOf(prev[j], cur[j - 1])
            val t = prev; prev = cur; cur = t
        }
        return 2.0 * prev[b.size] / (a.size + b.size)
    }
}

/**
 * Hook 可能不生效的风险。LSPosed 一类框架改的是方法入口；ART 编译时把很小的方法内联进调用者，调用者里就不再走入口，
 * Hook 不触发（可以改 Hook 调用者，或让框架对调用者反优化）。抽象方法、native 方法同理要换目标。
 */
object HookRisk {
    /** 指令数在这以内的方法，ART 很可能内联（ART 的内联上限按代码单元算，十几条指令就在范围里）。 */
    const val SMALL = 10

    fun of(m: MethodView, callers: Int): String? = when {
        m.flags and AccessFlag.ABSTRACT != 0 -> "抽象方法（接口或抽象类）：只调用它没关系；要 Hook 的话得 Hook 实现类"
        m.flags and 0x100 != 0 -> "native 方法：Java 层没有方法体，Hook 只拦得到 Java 侧的调用"
        m.opcodes.size <= SMALL && m.name != "<init>" ->
            "方法体只有 ${m.opcodes.size} 条指令${if (callers == 1) "、只有 1 个调用者" else ""}，可能被 ART 内联导致 Hook 不触发"
        else -> null
    }
}
