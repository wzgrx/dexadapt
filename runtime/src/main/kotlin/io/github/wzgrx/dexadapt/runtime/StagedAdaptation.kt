package io.github.wzgrx.dexadapt.runtime

import io.github.wzgrx.dexadapt.Symbol
import io.github.wzgrx.dexadapt.SymbolTable
import io.github.wzgrx.dexadapt.resolve.SymbolGraph
import io.github.wzgrx.dexadapt.resolve.SymbolResolver
import io.github.wzgrx.dexadapt.resolve.SymbolSource
import io.github.wzgrx.dexadapt.resolve.forBuild

/** 分阶段规划与解析共用一个源；已启用、可热安装功能及其传递依赖先解析。 */
object StagedAdaptation {
    fun scan(
        request: UpgradeCoordinator.Request,
        source: SymbolSource,
        symbols: List<Symbol>,
        checkpoint: UpgradeCoordinator.Checkpoint,
        onPartial: (SymbolTable) -> Unit,
        verifiedBuild: String? = null,
    ): SymbolTable {
        require(source.dexSha256 == request.identity.dexSha256) { "解析数据源不匹配已核验的完整 DEX" }
        val problems = SymbolGraph.problems(symbols)
        require(problems.isEmpty()) { problems.joinToString("\n") }
        val byId = symbols.associateBy { it.id }
        require(byId.size == symbols.size)
        val priority = LinkedHashSet<String>()
        fun add(id: String) {
            val s = requireNotNull(byId[id]) { "功能引用了未知符号：$id" }
            if (priority.add(id)) SymbolGraph.dependencies(s).forEach { (_, dep) -> add(requireNotNull(dep).id) }
        }
        require(request.enabled.all { f -> f.symbols.all(byId::containsKey) }) { "已启用功能引用了未知符号" }
        for (feature in request.enabled.filterNot { it.startupOnly }) feature.symbols.forEach(::add)
        val first = symbols.filter { it.id in priority }
        val remaining = symbols.filter { it.id !in priority }
        val stages = listOf(first, remaining).filter { it.isNotEmpty() }
        fun stamp(table: SymbolTable) = table.copy(packageName = request.identity.packageName, catalog = request.catalog, generator = "dexkit")
        checkpoint.check()
        val resolver = SymbolResolver.forBuild(source, request.identity.versionCode, verifiedBuild)
        return stamp(resolver.tableInStages(stages, { table ->
            checkpoint.check()
            onPartial(stamp(table))
        }) { _, _ -> checkpoint.check() })
    }
}
