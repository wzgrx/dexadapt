package io.github.wzgrx.dexadapt.cli

import io.github.wzgrx.dexadapt.SymbolTable
import io.github.wzgrx.dexadapt.apk.ApkInfo
import io.github.wzgrx.dexadapt.apk.ApkInspector
import io.github.wzgrx.dexadapt.apk.ApkSet
import io.github.wzgrx.dexadapt.matrix.Build
import io.github.wzgrx.dexadapt.matrix.Matrix
import io.github.wzgrx.dexadapt.resolve.MarginReport
import io.github.wzgrx.dexadapt.resolve.SymbolMargin
import io.github.wzgrx.dexadapt.resolve.SymbolResolver
import io.github.wzgrx.dexadapt.resolve.forBuild
import io.github.wzgrx.dexadapt.resolve.margins
import io.github.wzgrx.dexadapt.spec.Spec
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.system.exitProcess

private const val USAGE = """dexadapt <命令> [参数]

  inspect <安装包> [--json]
      安装包档案：版本、签名（是否被重签）、清单、dex、原生库。安装包可以是 .apk、拆分包目录、.apks / .xapk。

  resolve --spec <spec.yaml> --apk <安装包> [--build <构建标识>] [--engine dexlib|dexkit] [--out <table.json>]
      按 spec 解析一个安装包，得到符号表。给了 --build 时用这个构建的钉子和“没有”的标注。

  matrix --spec <spec.yaml> --apk <安装包> [--apk ...] [--channel <安装包>=<渠道>] [--parity] [--out <目录>]
      多个版本一起解析：每个版本的符号表、余量（单点依赖）、版本矩阵（漂移）；--parity 再用 DexKit 解析一遍、
      要求和 dexlib 逐条相同。输出 tables/<versionCode>.json、margin.json、matrix.md。

  diff <旧安装包> <新安装包> [--spec <spec.yaml>] [--old-build <标识>] [--new-build <标识>] [--out <目录>]
      升级分析：包、签名、清单、原生库、代码规模、没混淆的包和类、网络接口、字符串，以及混淆映射（旧类 → 新类）。
      给了 spec 时再列出模块的每个符号旧位置 → 新位置，新版找不到的按映射推测新位置。
      输出 report.md、report.json、mapping.txt（ProGuard 格式，jadx 打开新版时加载，混淆名显示成旧版的名字）。

  export --spec <输入> --out <输出.yaml|输出.json>
      spec 转换格式（YAML ⇄ JSON）。

  export --table <table.json> --nametable <输出.json>
      符号表导出成扁平的名字表（id → 位置），给按名字表工作的模块用。"""

fun main(args: Array<String>) {
    val code = runCatching { run(args.toList()) }.getOrElse { e ->
        System.err.println("错误：" + (e.message ?: e.toString()))
        if (System.getenv("DEXADAPT_DEBUG") != null) e.printStackTrace()
        2
    }
    exitProcess(code)
}

/** 解析参数并执行；返回退出码（测试直接调用）。 */
fun run(args: List<String>): Int {
    val cmd = args.firstOrNull() ?: run { println(USAGE); return 1 }
    val opts = Opts(args.drop(1))
    return when (cmd) {
        "inspect" -> inspect(opts)
        "resolve" -> resolve(opts)
        "matrix" -> matrix(opts)
        "export" -> export(opts)
        "diff" -> diff(opts)
        "help", "-h", "--help" -> { println(USAGE); 0 }
        else -> { System.err.println("不认识的命令 $cmd\n\n$USAGE"); 1 }
    }
}

/** `--key value`、`--flag` 和位置参数；同名参数可以出现多次。 */
class Opts(args: List<String>) {
    val positional = ArrayList<String>()
    private val values = LinkedHashMap<String, MutableList<String>>()
    private val flags = HashSet<String>()

    init {
        var i = 0
        while (i < args.size) {
            val a = args[i]
            if (a.startsWith("--")) {
                val key = a.removePrefix("--")
                if (i + 1 < args.size && !args[i + 1].startsWith("--") && key !in BOOLEAN) {
                    values.getOrPut(key) { ArrayList() } += args[i + 1]; i += 2
                } else { flags += key; i++ }
            } else { positional += a; i++ }
        }
    }

    fun get(key: String): String? = values[key]?.lastOrNull()
    fun all(key: String): List<String> = values[key].orEmpty()
    fun need(key: String): String = get(key) ?: error("缺少 --$key")
    fun flag(key: String): Boolean = key in flags

    private companion object {
        val BOOLEAN = setOf("json", "parity")
    }
}

private val json = Json { prettyPrint = true; prettyPrintIndent = "  "; encodeDefaults = true }

private fun engine(opts: Opts) = when (opts.get("engine") ?: "dexlib") {
    "dexlib" -> Engine.DEXLIB
    "dexkit" -> Engine.DEXKIT
    else -> error("--engine 只能是 dexlib 或 dexkit")
}

private fun inspect(opts: Opts): Int {
    val path = File(opts.positional.firstOrNull() ?: error("缺少安装包路径"))
    val info = ApkInspector.inspect(ApkSet.open(path))
    println(if (opts.flag("json")) json.encodeToString(ApkInfo.serializer(), info) else Report.inspect(info))
    return 0
}

private fun resolve(opts: Opts): Int {
    val spec = Spec.read(File(opts.need("spec")))
    Engines.open(File(opts.need("apk")), engine(opts)).use { o ->
        val started = System.currentTimeMillis()
        val table = SymbolResolver.forBuild(o.source, o.info.versionCode, opts.get("build")).table(spec.catalog.symbols)
        val seconds = (System.currentTimeMillis() - started) / 1000.0
        opts.get("out")?.let { File(it).apply { parentFile?.mkdirs() }.writeText(table.toJson() + "\n") }
        println("${o.info.label}: ${table.entries.size} 个找到，${table.missing.size} 个缺失（${"%.1f".format(seconds)} 秒）")
        table.missing.forEach { (id, why) -> println("  缺失 $id：$why") }
        return if (table.missing.isEmpty()) 0 else 3
    }
}

private fun matrix(opts: Opts): Int {
    val spec = Spec.read(File(opts.need("spec")))
    val apks = opts.all("apk").ifEmpty { error("至少给一个 --apk") }.map(::File)
    val channels = opts.all("channel").associate { it.substringBeforeLast('=') to it.substringAfterLast('=') }
    val out = File(opts.get("out") ?: "dexadapt-matrix").apply { mkdirs() }
    val tables = LinkedHashMap<Build, SymbolTable>()
    val margins = LinkedHashMap<Long, List<SymbolMargin>>()
    val parity = ArrayList<String>()
    var failed = false
    for (apk in apks) {
        val (build, table) = Engines.open(apk, Engine.DEXLIB).use { o ->
            val b = Engines.build(apk, o.info, channels)
            val started = System.currentTimeMillis()
            val resolver = SymbolResolver.forBuild(o.source, o.info.versionCode, b.id)
            val t = resolver.table(spec.catalog.symbols)
            margins[o.info.versionCode] = resolver.margins(spec.catalog.symbols)
            println("${b.label}（${b.id}）：${t.entries.size} 个找到，${t.missing.size} 个缺失（${(System.currentTimeMillis() - started) / 1000} 秒）")
            b to t
        }
        tables[build] = table
        if (table.missing.isNotEmpty()) failed = true
        File(out, "tables/${build.versionCode}.json").apply { parentFile.mkdirs() }.writeText(table.toJson() + "\n")
        if (opts.flag("parity")) {
            // DexKit 按“未知版本”的方式解析（不用钉子），结果要和 dexlib 的逐条相同。
            val kit = Engines.open(apk, Engine.DEXKIT).use { o -> SymbolResolver(o.source, o.info.versionCode).table(spec.catalog.symbols) }
            val diff = (table.entries.keys + kit.entries.keys).filter { table.entries[it] != kit.entries[it] }.sorted()
            parity += "${build.label}: " + if (diff.isEmpty()) "DexKit 一致" else "DexKit 不一致 ${diff.size} 个：${diff.take(10).joinToString()}"
            if (diff.isNotEmpty()) failed = true
        }
    }
    File(out, "margin.json").writeText(MarginReport.of(spec.catalog.fingerprint(), margins).toJson() + "\n")
    File(out, "matrix.md").writeText(Matrix.render(tables, margins, regenerate = "由 `dexadapt matrix` 生成。"))
    parity.forEach(::println)
    println("输出在 ${out.path}/（tables/、margin.json、matrix.md）")
    return if (failed) 3 else 0
}

private fun diff(opts: Opts): Int {
    val (oldPath, newPath) = opts.positional.takeIf { it.size >= 2 }?.let { File(it[0]) to File(it[1]) } ?: error("用法：dexadapt diff <旧安装包> <新安装包>")
    val out = File(opts.get("out") ?: "dexadapt-diff").apply { mkdirs() }
    val spec = opts.get("spec")?.let { Spec.read(File(it)) }
    val started = System.currentTimeMillis()
    Engines.open(oldPath, Engine.DEXLIB).use { a ->
        Engines.open(newPath, Engine.DEXLIB).use { b ->
            val ia = a.source as? io.github.wzgrx.dexadapt.dexlib.DexIndex ?: error("拆分包里有多份代码的暂不支持 diff")
            val ib = b.source as? io.github.wzgrx.dexadapt.dexlib.DexIndex ?: error("拆分包里有多份代码的暂不支持 diff")
            println("读入 ${a.info.label} 和 ${b.info.label}（${(System.currentTimeMillis() - started) / 1000} 秒），开始配对…")
            val mapping = io.github.wzgrx.dexadapt.diff.ClassMatcher(ia, ib).run()
            val symbols = spec?.let { s ->
                val ta = SymbolResolver.forBuild(ia, a.info.versionCode, opts.get("old-build")).table(s.catalog.symbols)
                val tb = SymbolResolver.forBuild(ib, b.info.versionCode, opts.get("new-build")).table(s.catalog.symbols)
                io.github.wzgrx.dexadapt.diff.SymbolDiff.of(ta, tb, mapping)
            }
            val report = io.github.wzgrx.dexadapt.diff.DiffReport(
                io.github.wzgrx.dexadapt.diff.PackageDiff.of(a.info, b.info), io.github.wzgrx.dexadapt.diff.CodeDiff.of(ia, ib, mapping), symbols,
            )
            File(out, "report.md").writeText(report.markdown())
            File(out, "report.json").writeText(json.encodeToString(io.github.wzgrx.dexadapt.diff.DiffReport.serializer(), report) + "\n")
            File(out, "mapping.txt").writeText(io.github.wzgrx.dexadapt.diff.MappingWriter.proguard(mapping))
            val m = report.code.mapping
            println("配上 ${m.matched}/${m.oldClasses} 个类、${m.methods} 对方法（${(System.currentTimeMillis() - started) / 1000} 秒）")
            symbols?.counts?.let { println("模块符号：" + it.entries.joinToString("，") { (k, v) -> "$k $v" }) }
            println("输出在 ${out.path}/（report.md、report.json、mapping.txt）")
        }
    }
    return 0
}

private fun export(opts: Opts): Int {
    opts.get("table")?.let { t ->
        val table = SymbolTable.fromJson(File(t).readText())
        File(opts.need("nametable")).writeText(json.encodeToString(NameTable.serializer(), NameTable.of(table)) + "\n")
        return 0
    }
    val spec = Spec.read(File(opts.need("spec")))
    val out = File(opts.need("out"))
    out.writeText(if (out.name.endsWith(".json")) spec.toJson() + "\n" else spec.toYaml())
    println("${spec.catalog.symbols.size} 个符号 → ${out.path}")
    return 0
}
