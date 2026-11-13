package io.github.wzgrx.dexadapt.cli

import io.github.wzgrx.dexadapt.Symbol
import io.github.wzgrx.dexadapt.Resolved
import io.github.wzgrx.dexadapt.SymbolTable
import io.github.wzgrx.dexadapt.resolve.SymbolGraph
import io.github.wzgrx.dexadapt.resolve.SymbolMargin
import io.github.wzgrx.dexadapt.spec.Spec
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** 每个包、构建、DEX 身份和引擎一个记录；每行按自己的查询及递归依赖匹配。 */
internal object IncrementalMargins {
    private val json = Json { encodeDefaults = true }
    private const val FORMAT = "symbol-rows-v2"
    private const val MAX_BYTES = 32L * 1024 * 1024

    @Serializable data class Row(val fingerprint: String, val margin: MatrixCache.Margin?)
    @Serializable data class Snapshot(
        val spec: String,
        val table: String,
        val rows: Map<String, Row>,
        val fingerprints: Map<String, String>,
    )

    fun verifiedEntries(
        table: SymbolTable,
        previous: Map<String, String>,
        current: Map<String, String>,
        absent: Set<String>,
    ): Map<String, Resolved> = table.entries.filterKeys {
        it !in absent && current[it]?.let { fingerprint -> previous[it] == fingerprint } == true
    }
    @Serializable private data class Record(val key: String, val snapshot: Snapshot, val checksum: String)

    data class Result(val rows: Map<String, Row>, val margins: List<SymbolMargin>, val reused: Int, val computed: Int)

    fun key(engine: String, pkg: String, build: String, dex: String): String =
        MatrixCache.hash(listOf(FORMAT, engine, pkg, build, dex).joinToString("\u0000"))

    fun fingerprints(spec: Spec): Map<String, String> {
        val problems = spec.catalog.problems()
        require(problems.isEmpty()) { problems.joinToString("\n") }
        val declarations = spec.encode().getValue("symbols").jsonArray.associate {
            val fields = it.jsonObject
            // 契约在 Hook 阶段使用，不影响符号解析或余量。
            fields.getValue("id").jsonPrimitive.content to JsonObject(fields - "contract").toString()
        }
        val memo = HashMap<String, String>()
        fun hash(s: Symbol): String = memo[s.id] ?: run {
            val deps = SymbolGraph.dependencies(s).map { (_, dep) -> hash(requireNotNull(dep)) }
            MatrixCache.hash((listOf(declarations.getValue(s.id)) + deps).joinToString("\u0000")).also { memo[s.id] = it }
        }
        return spec.catalog.symbols.associate { it.id to hash(it) }
    }

    fun collect(
        symbols: List<Symbol>,
        fingerprints: Map<String, String>,
        table: SymbolTable,
        previous: Map<String, Row>,
        calculate: (Symbol) -> SymbolMargin?,
    ): Result {
        val rows = LinkedHashMap<String, Row>()
        val margins = ArrayList<SymbolMargin>()
        var reused = 0
        var computed = 0
        for (symbol in symbols) {
            val id = symbol.id
            if (id in table.missing) continue
            val fingerprint = fingerprints.getValue(id)
            val row = previous[id]?.takeIf { it.fingerprint == fingerprint }?.also { reused++ } ?: run {
                computed++
                val margin = calculate(symbol)
                Row(fingerprint, margin?.let { MatrixCache.Margin(id, it.drops.map { d -> MatrixCache.Drop(d.constraint, d.count) }) })
            }
            val report = row.margin?.let { m -> SymbolMargin(id, m.drops.map { SymbolMargin.Drop(it.constraint, it.count) }) }
            if (report != null) margins += report
            if (report == null || report.drops.none { it.count == SymbolMargin.UNKNOWN }) rows[id] = row
        }
        return Result(rows, margins, reused, computed)
    }

    fun read(dir: File, key: String): Snapshot? = runCatching {
        val file = File(dir, "$key.json")
        require(file.length() in 1..MAX_BYTES)
        val record = json.decodeFromString(Record.serializer(), file.readText())
        require(record.key == key && checksum(record.snapshot) == record.checksum)
        record.snapshot
    }.getOrNull()

    fun write(dir: File, key: String, snapshot: Snapshot) {
        Files.createDirectories(dir.toPath())
        val temp = Files.createTempFile(dir.toPath(), "$key.", ".tmp")
        try {
            val text = json.encodeToString(Record.serializer(), Record(key, snapshot, checksum(snapshot)))
            temp.toFile().outputStream().use { stream ->
                stream.write(text.toByteArray(Charsets.UTF_8))
                stream.fd.sync()
            }
            Files.move(temp, File(dir, "$key.json").toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    private fun checksum(snapshot: Snapshot): String = MatrixCache.hash(json.encodeToString(Snapshot.serializer(), snapshot))
}
