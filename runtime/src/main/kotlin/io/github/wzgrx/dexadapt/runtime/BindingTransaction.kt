package io.github.wzgrx.dexadapt.runtime

import io.github.wzgrx.dexadapt.SymbolTable

/** 仅描述安装绑定，不包含模块UI；调用方在自己的串行调度中执行。 */
object BindingTransaction {
    interface Node {
        val id: String
        val installed: Boolean
        val startupOnly: Boolean
        val dependsOn: Set<String>
        val symbols: Set<String>
        fun uninstall()
        /** true也包括按设置关闭或确认不支持后的干净停用；只有安装异常才返回false。 */
        fun install(): Boolean
        fun requireRestart()
    }
    data class Result(val applied: Boolean, val rebound: Set<String> = emptySet(), val restart: Set<String> = emptySet(), val failed: List<String> = emptyList())

    fun changed(old: SymbolTable?, next: SymbolTable): Set<String> {
        val ids = old?.let { it.entries.keys + it.guesses.keys + it.absent }.orEmpty() + next.entries.keys + next.guesses.keys + next.absent
        return ids.filterTo(LinkedHashSet()) { id ->
            val a = old?.entries?.get(id) ?: old?.guesses?.get(id)?.at
            val b = next.entries[id] ?: next.guesses[id]?.at
            a != b || (id in old?.absent.orEmpty()) != (id in next.absent)
        }
    }

    /** 在活动宿主消费阶段表前验证实际代码；跨安装的旧表必须重新绑定到新进程。 */
    fun applyVerified(
        identity: HostCodeIdentity,
        catalog: String,
        old: SymbolTable?,
        next: SymbolTable,
        nodes: List<Node>,
        replace: (SymbolTable?) -> Unit,
        pause: (Boolean) -> Unit,
    ): Result {
        require(identity.matches(next) && next.catalog == catalog) { "热替换的 DEX 或目录身份不匹配" }
        require(old == null || identity.matches(old)) { "旧表不属于正在运行的宿主" }
        return apply(old, next, nodes, replace, pause)
    }

    fun apply(old: SymbolTable?, next: SymbolTable, nodes: List<Node>, replace: (SymbolTable?) -> Unit, pause: (Boolean) -> Unit): Result {
        require(old == null || old.versionCode == next.versionCode) { "热替换不能跨宿主版本" }
        require(old == null || old.packageName.isEmpty() || next.packageName.isEmpty() || old.packageName == next.packageName) { "热替换不能跨宿主包名" }
        val byId = nodes.associateBy { it.id }
        require(byId.size == nodes.size) { "功能编号重复" }
        val ordered = ArrayList<Node>()
        val visiting = HashSet<String>()
        val visited = HashSet<String>()
        fun visit(node: Node) {
            if (node.id in visited) return
            require(visiting.add(node.id)) { "功能依赖成环：${node.id}" }
            node.dependsOn.forEach { byId[it]?.let(::visit) }
            visiting.remove(node.id); visited += node.id; ordered += node
        }
        nodes.forEach(::visit)
        val live = ordered.filter { it.installed }
        val differences = changed(old, next)
        val affected = live.filterTo(LinkedHashSet()) { n -> n.symbols.any { it in differences } }.mapTo(LinkedHashSet()) { it.id }
        fun expand(ids: MutableSet<String>) {
            do {
                val before = ids.size
                live.filter { n -> n.dependsOn.any { it in ids } }.forEach { ids += it.id }
            } while (ids.size != before)
        }
        expand(affected)
        val restart = live.filter { it.id in affected && it.startupOnly }.mapTo(LinkedHashSet()) { it.id }
        expand(restart)
        val hot = live.filter { it.id in affected && it.id !in restart }
        val failed = ArrayList<String>()
        pause(true)
        try {
            try {
                hot.asReversed().forEach { it.uninstall() }
                replace(next)
                for (node in hot) {
                    if (!node.install()) { failed += node.id; error("新绑定安装失败") }
                }
            } catch (e: Exception) {
                if (failed.isEmpty()) failed += "binding:${e.javaClass.simpleName}"
                hot.asReversed().forEach { runCatching { it.uninstall() }.onFailure { failed += "uninstall:${it.javaClass.simpleName}" } }
                runCatching { replace(old) }.onFailure { failed += "restore-table:${it.javaClass.simpleName}" }
                hot.forEach { node -> if (!runCatching { node.install() }.getOrDefault(false)) failed += "restore:${node.id}" }
                return Result(false, failed = failed)
            }
            live.filter { it.id in restart }.forEach { it.requireRestart() }
            return Result(true, hot.mapTo(LinkedHashSet()) { it.id }, restart)
        } finally { pause(false) }
    }
}
