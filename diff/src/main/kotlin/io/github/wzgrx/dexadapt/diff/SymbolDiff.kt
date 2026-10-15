package io.github.wzgrx.dexadapt.diff

import io.github.wzgrx.dexadapt.Resolved
import io.github.wzgrx.dexadapt.SymbolTable
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
        fun of(old: SymbolTable, new: SymbolTable, mapping: ClassMatcher.Result?): SymbolDiff {
            val ids = (old.entries.keys + old.missing.keys + new.entries.keys + new.missing.keys).toSortedSet()
            val methodMap = mapping?.methods?.associate { it.old to it.new }.orEmpty()
            return SymbolDiff(ids.map { id ->
                val a = old.entries[id]
                val b = new.entries[id]
                when {
                    a != null && b != null -> Row(id, if (a == b) Status.SAME else Status.MOVED, text(a), text(b))
                    a != null -> Row(id, Status.LOST, text(a), null, new.missing[id], mapping?.let { guess(a, it.byOld, methodMap) })
                    b != null -> Row(id, Status.GAINED, null, text(b))
                    else -> Row(id, Status.ABSENT, null, null, new.missing[id])
                }
            })
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
