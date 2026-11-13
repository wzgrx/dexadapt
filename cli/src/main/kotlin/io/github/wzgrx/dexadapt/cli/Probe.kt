package io.github.wzgrx.dexadapt.cli

import io.github.wzgrx.dexadapt.Symbol
import io.github.wzgrx.dexadapt.resolve.SymbolGraph
import io.github.wzgrx.dexadapt.resolve.SymbolResolver
import io.github.wzgrx.dexadapt.resolve.forBuild
import io.github.wzgrx.dexadapt.spec.Spec
import io.github.wzgrx.dexadapt.synth.Repair
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

private val probeJson = Json { prettyPrint = true; encodeDefaults = true }

@Serializable
internal data class ProbeState(
    val status: String,
    val target: String? = null,
    val reason: String? = null,
    val dependencyPath: List<String> = emptyList(),
)

@Serializable
internal data class ProbeRow(val id: String, val current: ProbeState, val baseline: ProbeState?, val change: String?)

@Serializable
internal data class ProbeReport(
    val packageName: String,
    val versionCode: Long,
    val build: String?,
    val dexSha256: String,
    val engine: String,
    val openMs: Long,
    val resolveMs: Long,
    val symbols: List<ProbeRow>,
) {
    val failed get() = symbols.any {
        it.current.status == "missing" || it.current.status == "blocked" ||
            it.change == "regressed" || it.change == "targetChanged"
    }
}

internal fun probeRows(
    current: List<Symbol>,
    baseline: List<Symbol>?,
    ids: List<String>,
    currentResolver: SymbolResolver,
    baselineResolver: SymbolResolver,
): List<ProbeRow> {
    val all = current + baseline.orEmpty()
    val currentById = current.associateBy { it.id }
    val baselineById = baseline.orEmpty().associateBy { it.id }
    val known = currentById.keys + baselineById.keys
    require(known.containsAll(ids)) { "未知符号：${ids.filterNot(known::contains).joinToString()}" }
    val selected = if (ids.isEmpty()) known else {
        val reverse = HashMap<String, MutableSet<String>>()
        for (symbol in all) {
            for ((_, dep) in SymbolGraph.dependencies(symbol)) {
                if (dep != null) reverse.getOrPut(dep.id, ::linkedSetOf).add(symbol.id)
            }
        }
        val seen = ids.toMutableSet()
        val queue = ArrayDeque(ids)
        while (queue.isNotEmpty()) for (child in reverse[queue.removeFirst()].orEmpty()) {
            if (seen.add(child)) queue.addLast(child)
        }
        seen
    }

    fun state(symbol: Symbol?, resolver: SymbolResolver): ProbeState {
        if (symbol == null) return ProbeState("undeclared")
        return resolver.resolve(symbol).fold(
            { ProbeState("resolved", target = Repair.text(it)) },
            { failure ->
                val path = generateSequence(failure) { it.cause }
                    .mapNotNull { (it as? SymbolResolver.Unresolved)?.dependencyId }.toList()
                val status = when {
                    failure.message == SymbolResolver.ABSENT -> "absent"
                    path.isNotEmpty() -> "blocked"
                    else -> "missing"
                }
                ProbeState(status, reason = failure.message, dependencyPath = path)
            },
        )
    }
    return selected.sorted().map { id ->
        val now = state(currentById[id], currentResolver)
        val before = baseline?.let { state(baselineById[id], baselineResolver) }
        val change = when {
            before == null -> null
            before.status == "resolved" && now.status != "resolved" -> "regressed"
            before.status == "resolved" && before.target != now.target -> "targetChanged"
            now.status == "resolved" && before.status != "resolved" -> "resolved"
            before.status != now.status -> "statusChanged"
            else -> null
        }
        ProbeRow(id, now, before, change)
    }
}

internal fun probe(opts: Opts): Int {
    val spec = Spec.read(File(opts.need("spec")))
    val baseline = opts.get("baseline")?.let { Spec.read(File(it)) }
    val build = opts.get("build")
    val engine = when (val arg = opts.get("engine") ?: "dexlib") {
        "dexlib" -> Engine.DEXLIB
        "dexkit" -> Engine.DEXKIT
        else -> error("不支持的引擎：$arg")
    }
    val started = System.nanoTime()
    Engines.open(File(opts.need("apk")), engine).use { o ->
        val opened = System.nanoTime()
        for (catalog in listOfNotNull(spec, baseline)) {
            require(catalog.app == null || catalog.app == o.info.packageName) {
                "spec 的包名 ${catalog.app} 与安装包 ${o.info.packageName} 不同"
            }
        }
        val rows = probeRows(
            spec.catalog.symbols, baseline?.catalog?.symbols, opts.all("id"),
            SymbolResolver.forBuild(o.source, o.info.versionCode, build),
            SymbolResolver.forBuild(o.source, o.info.versionCode, build),
        )
        val report = ProbeReport(
            o.info.packageName, o.info.versionCode, build, o.codeIdentity, engine.name.lowercase(),
            (opened - started) / 1_000_000, (System.nanoTime() - opened) / 1_000_000, rows,
        )
        val output = if (opts.flag("json")) {
            probeJson.encodeToString(ProbeReport.serializer(), report)
        } else buildString {
            appendLine("${o.info.label} · ${report.engine} · 打开 ${report.openMs}ms，解析 ${report.resolveMs}ms")
            appendLine("DEX: ${report.dexSha256}")
            appendLine(rows.groupingBy { it.current.status }.eachCount().toString())
            for (row in rows) if (row.change != null || row.current.status in setOf("missing", "blocked")) {
                val old = row.baseline?.let { it.target ?: it.status }
                val current = row.current.target ?: row.current.reason ?: row.current.status
                appendLine("  ${row.id} [${row.change ?: row.current.status}]：${old?.let { "$it → " }.orEmpty()}$current")
            }
        }.trimEnd()
        opts.get("out")?.let { File(it).apply { parentFile?.mkdirs() }.writeText(output + "\n") } ?: println(output)
        return if (report.failed) 3 else 0
    }
}
