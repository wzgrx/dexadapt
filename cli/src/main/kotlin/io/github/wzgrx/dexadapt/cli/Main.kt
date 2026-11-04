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

  matrix --spec <spec.yaml> --apk <安装包> [--apk ...] [--channel <安装包>=<渠道>] [--parity] [--incremental] [--out <目录>]
      多个版本一起解析：每个版本的符号表、余量（单点依赖）、版本矩阵（漂移）；--parity 再用 DexKit 解析一遍、
      要求和 dexlib 逐条相同。输出 tables/<versionCode>.json、margin.json、matrix.md、manifest.json；--incremental区分fresh/reused，parity始终新跑。

  diff <旧安装包> <新安装包> [--spec <spec.yaml>] [--old-build <标识>] [--new-build <标识>] [--out <目录>]
      升级分析：包、签名、清单、原生库、代码规模、没混淆的包和类、网络接口、字符串，以及混淆映射（旧类 → 新类）。
      给了 spec 时再列出模块的每个符号旧位置 → 新位置，新版找不到的按映射推测新位置。
      输出 report.md、report.json、mapping.txt（ProGuard 格式，jadx 打开新版时加载，混淆名显示成旧版的名字）。

  suggest --apk <安装包> --target <类名 | 类#方法(参数)返回 | 类#字段> [--other <安装包> ...] [--id <符号 id>]
      给一个目标自动写查询：挑出能让它唯一、最稳的特征组合（稀有字符串、源文件名、字段名、注解、稳定 API 调用、形状），
      要求在 --other 给的别的版本里也唯一命中。输出 YAML。

  repair --spec <spec.yaml> --old <旧安装包> --new <新安装包> [--old-build <标识>] [--new-build <标识>] [--id <符号 id> ...]
      可加 --apk / --channel 指定额外回归，--json 输出证据，--out 保存candidate-spec.json。
      新版找不到的符号：诊断是哪个条件不满足，给出修好的查询（逐个放宽条件，或按混淆映射找到新位置重新生成），
      每条都在新旧两版验证过。不给 --id 时修全部找不到的。

  upgrade --spec <spec.yaml> --old <旧安装包> --new <新安装包> [--apk <别的样本> ...] [--old-build <标识>] [--new-build <标识>] [--out <目录>]
      可加 --verify-repairs 复验候选，--parity 做DexKit对照，--incremental 复用严格匹配的结果。
      新版本一条龙：diff → matrix（旧版、新版和别的样本）→ repair，写 diff/、matrix/、repair.md 和 summary.md
      （可以直接当更新 spec 的 PR 说明）。新版全部解析到时退出码 0，否则 3。很慢（diff 和 matrix 各几分钟），一次只跑一个。

  libs <安装包> [--db <指纹库>] [--out <目录>]
      认出打包的开源库（OkHttp、Retrofit、RxJava、Gson、协程……）：版本、混淆后的类对应库里的哪个类、包被改到了哪里。
      输出 libs.md、libs.json、libs-mapping.txt（jadx 加载后混淆的库类显示回原名）。默认用随 DexAdapt 发布的指纹库。

  libs-db --out <指纹库.json.gz> [--lib <group:artifact=显示名> ...] [--add-to <已有指纹库>]
      从 Maven 仓库下载开源库的各个版本，建指纹库。不给 --lib 时收录内置的常用库列表。

  lint --spec <spec.yaml> [--apk <安装包> ...]
      检查 spec 的写法：引用、成环、只靠形状、按混淆名找、手机上要全量扫描的查询、重复的查询；给了安装包再查
      两个符号命中同一个成员、契约对不上、解析不到。有错误时退出码 3。

  decompile <安装包> (--class <类名> ... | --package <包名前缀> ... | --string <字符串> ... | --calls <方法> ...) [--out <目录>] [--limit <个数>]
      用 jadx 反编译指定的类或整个包（加载一次安装包，批量输出 Java 源码；写 Hook、确认类的用途时看）。
      --string 按方法里用到的字符串找类（包含即可，例如一条日志、一个埋点名、一个配置键）；
      --calls 按调用了哪个方法找类（写成 类#方法，包含即可，例如 VideoCommentTimestampView#f）。
      不给 --out 时直接打印（只适合少量类）。类名用安装包里的原始名字。

  codegen --spec <spec.yaml> --package <包名> [--class <类名>] --out <输出.kt>
      生成类型化访问器（Kotlin）：access.<组>.<成员>(...)，代替按名字反射；找不到的符号抛 SymbolUnavailable。

  keygen --out <私钥文件>
      生成共享适配表的签名密钥（ECDSA P-256）：私钥写进文件（只有自己可读，不要提交），公钥打印出来放进模块。

  sign --key <私钥文件> <表.json> [...]
      给共享适配表签名，写出同名 .sig。表要先盖上共享表身份（generator=ci、catalog=模块的目录指纹）。

  mcp
      作为 MCP 服务运行（标准输入输出），把上面的命令提供给 Claude 这类 Agent 调用。

  import --from revanced|morphe|qauxiliary [--group <组名>] <源文件.kt> [...] --out <spec.yaml>
      别的生态的指纹（ReVanced / Morphe 的 fingerprint、QAuxiliary 的 DexKitTarget）转成 DexAdapt 查询；
      表达不了的条件列出来。转出来的查询要用 resolve 在真实 APK 上验证。

  export --spec <spec.yaml> --to revanced --out <输出.kt>
      方法符号导出成 ReVanced 的 fingerprint { }（表达不了的条件写成注释）。

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
        "suggest" -> suggest(opts)
        "repair" -> repair(opts)
        "upgrade" -> upgrade(opts)
        "libs" -> libs(opts)
        "libs-db" -> libsDb(opts)
        "mcp" -> { Mcp.serve(); 0 }
        "codegen" -> codegen(opts)
        "lint" -> lint(opts)
        "decompile" -> decompile(opts)
        "keygen" -> keygen(opts)
        "import" -> importFingerprints(opts)
        "sign" -> sign(opts)
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
        val BOOLEAN = setOf("json", "parity", "incremental", "verify-repairs")
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

private fun matrix(opts: Opts): Int = runMatrix(opts).code

/** 一次 matrix 的结果（[upgrade] 汇总用）。 */
private class MatrixRun(val code: Int, val tables: Map<Build, SymbolTable>, val margins: Map<Long, List<SymbolMargin>>)

private fun runMatrix(opts: Opts): MatrixRun {
    val spec = Spec.read(File(opts.need("spec")))
    val apks = opts.all("apk").ifEmpty { error("至少给一个 --apk") }.map(::File).distinctBy { it.canonicalPath }
    val channels = opts.all("channel").associate { it.substringBeforeLast('=') to it.substringAfterLast('=') }
    val out = File(opts.get("out") ?: "dexadapt-matrix").apply { mkdirs() }
    val tables = LinkedHashMap<Build, SymbolTable>();val margins = LinkedHashMap<Long, List<SymbolMargin>>()
    val parity = ArrayList<String>();val manifest=ArrayList<kotlinx.serialization.json.JsonObject>()
    val identities=MatrixIdentityGuard()
    val engineIdentity=MatrixCache.engine();val specIdentity=MatrixCache.hash(spec.toJson())
    var failed=false
    for ((number,apk) in apks.withIndex()) {
        println("[${number+1}/${apks.size}] 打开 ${apk.name}")
        val started=System.currentTimeMillis()
        val (build, table) = Engines.open(apk, Engine.DEXLIB).use { o ->
            val b=Engines.build(apk,o.info,channels);val identity=o.codeIdentity
            val variant=o.info.packageName+"|"+b.id+"|"+identity
            identities.accept(b.versionCode,variant,apk.path)
            val key=MatrixCache.hash("$specIdentity|$engineIdentity|$variant")
            val cached=if(opts.flag("incremental"))MatrixCache.read(File(out,".matrix-cache"),key) else null
            val pair=if(cached!=null && cached.first.versionCode==o.info.versionCode && cached.first.dexSha256==o.source.dexSha256)cached else {
                val resolver=SymbolResolver.forBuild(o.source,o.info.versionCode,b.id)
                val t=resolver.table(spec.catalog.symbols, onProgress = { done,total->if(done%100==0 || done==total)println("  ${b.id} 解析 $done/$total") })
                println("  ${b.id} 计算查询余量")
                val rows=resolver.margins(spec.catalog.symbols)
                MatrixCache.write(File(out,".matrix-cache"),key,t,rows);t to rows
            }
            margins[o.info.versionCode]=pair.second
            val mode=if(cached!=null && cached==pair)"reused" else "fresh"
            manifest+=kotlinx.serialization.json.JsonObject(mapOf("build" to kotlinx.serialization.json.JsonPrimitive(b.id),
                "package" to kotlinx.serialization.json.JsonPrimitive(o.info.packageName),"versionCode" to kotlinx.serialization.json.JsonPrimitive(b.versionCode),
                "codeIdentity" to kotlinx.serialization.json.JsonPrimitive(identity),"mode" to kotlinx.serialization.json.JsonPrimitive(mode),
                "cacheKey" to kotlinx.serialization.json.JsonPrimitive(key),"elapsedMs" to kotlinx.serialization.json.JsonPrimitive(System.currentTimeMillis()-started)))
            println("${b.label}（${b.id}）：${pair.first.entries.size} 个找到，${pair.first.missing.size} 个缺失，$mode（${(System.currentTimeMillis()-started)/1000} 秒）")
            b to pair.first
        }
        tables[build]=table
        if(table.missing.isNotEmpty())failed=true
        File(out,"tables/${build.versionCode}.json").apply { parentFile.mkdirs() }.writeText(table.toJson()+"\n")
        if(opts.flag("parity")) {
            val kit=Engines.open(apk,Engine.DEXKIT).use { o->SymbolResolver(o.source,o.info.versionCode).table(spec.catalog.symbols) }
            val different=(table.entries.keys+kit.entries.keys).filter { table.entries[it]!=kit.entries[it] }.sorted()
            parity+="${build.label}: "+if(different.isEmpty())"DexKit 一致（fresh）" else "DexKit 不一致 ${different.size} 个：${different.take(10).joinToString()}"
            if(different.isNotEmpty())failed=true
        }
    }
    File(out,"manifest.json").writeText(kotlinx.serialization.json.JsonArray(manifest).toString()+"\n")
    File(out,"margin.json").writeText(MarginReport.of(spec.catalog.fingerprint(),margins).toJson()+"\n")
    File(out,"matrix.md").writeText(Matrix.render(tables,margins,regenerate="由 `dexadapt matrix` 生成；manifest.json区分fresh/reused。"))
    File(out,"matrix.html").writeText(Matrix.html(tables,margins));parity.forEach(::println)
    println("输出在 ${out.path}/（tables/、margin.json、matrix.md、matrix.html、manifest.json）")
    return MatrixRun(if(failed)3 else 0,tables,margins)
}

private fun diff(opts: Opts): Int {
    runDiff(opts)
    return 0
}

private fun runDiff(opts: Opts): io.github.wzgrx.dexadapt.diff.DiffReport {
    val (oldPath, newPath) = opts.positional.takeIf { it.size >= 2 }?.let { File(it[0]) to File(it[1]) } ?: error("用法：dexadapt diff <旧安装包> <新安装包>")
    val out = File(opts.get("out") ?: "dexadapt-diff").apply { mkdirs() }
    val spec = opts.get("spec")?.let { Spec.read(File(it)) }
    val started = System.currentTimeMillis()
    Engines.open(oldPath, Engine.DEXLIB).use { a ->
        Engines.open(newPath, Engine.DEXLIB).use { b ->
            val ia = a.diffIndex()
            val ib = b.diffIndex()
            println("读入 ${a.info.label} 和 ${b.info.label}（${(System.currentTimeMillis() - started) / 1000} 秒），开始配对…")
            val mapping = io.github.wzgrx.dexadapt.diff.ClassMatcher(ia, ib).run()
            val symbols = spec?.let { s ->
                val ta = SymbolResolver.forBuild(ia, a.info.versionCode, opts.get("old-build")).table(s.catalog.symbols)
                val tb = SymbolResolver.forBuild(ib, b.info.versionCode, opts.get("new-build")).table(s.catalog.symbols)
                io.github.wzgrx.dexadapt.diff.SymbolDiff.of(ta, tb, mapping, ia, ib)
            }
            val libraries = libraryDb(opts)?.let { db ->
                val scanner = io.github.wzgrx.dexadapt.libs.LibraryScanner(db)
                libraryDelta(scanner.scan(ia), scanner.scan(ib))
            } ?: io.github.wzgrx.dexadapt.diff.Delta()
            val report = io.github.wzgrx.dexadapt.diff.DiffReport(
                io.github.wzgrx.dexadapt.diff.PackageDiff.of(a.info, b.info), io.github.wzgrx.dexadapt.diff.CodeDiff.of(ia, ib, mapping), symbols, libraries,
                nativeSymbols = runCatching { io.github.wzgrx.dexadapt.diff.NativeDiff.of(ApkSet.open(oldPath).files, ApkSet.open(newPath).files) }
                    .onFailure { System.err.println("原生库比较失败：${it.message}") }.getOrDefault(io.github.wzgrx.dexadapt.diff.Delta()),
            )
            File(out, "report.md").writeText(report.markdown())
            File(out, "report.html").writeText(report.html())
            File(out, "report.json").writeText(json.encodeToString(io.github.wzgrx.dexadapt.diff.DiffReport.serializer(), report) + "\n")
            File(out, "mapping.txt").writeText(io.github.wzgrx.dexadapt.diff.MappingWriter.proguard(mapping))
            val m = report.code.mapping
            println("配上 ${m.matched}/${m.oldClasses} 个类、${m.methods} 对方法（${(System.currentTimeMillis() - started) / 1000} 秒）")
            symbols?.counts?.let { println("模块符号：" + it.entries.joinToString("，") { (k, v) -> "$k $v" }) }
            println("输出在 ${out.path}/（report.md、report.html、report.json、mapping.txt）")
            return report
        }
    }
}

private fun keygen(opts: Opts): Int {
    val out = File(opts.need("out"))
    require(!out.exists()) { "${out.path} 已经存在，不覆盖（丢了私钥就只能换公钥、发新版模块）" }
    val keys = io.github.wzgrx.dexadapt.TableSignature.generate()
    out.absoluteFile.parentFile?.mkdirs()
    out.writeText(keys.privateKey + "\n")
    out.setReadable(false, false); out.setReadable(true, true)
    out.setWritable(false, false); out.setWritable(true, true)
    println("私钥：${out.path}（只有自己可读，不要提交到仓库）")
    println("公钥：${keys.publicKey}")
    return 0
}

private fun sign(opts: Opts): Int {
    val key = File(opts.need("key")).readText().trim()
    val tables = opts.positional.ifEmpty { error("缺少要签名的表") }.map(::File)
    for (t in tables) {
        val table = SymbolTable.fromJson(t.readText())
        require(table.generator == io.github.wzgrx.dexadapt.runtime.SharedTables.GENERATOR && table.catalog.isNotEmpty()) {
            "${t.path} 没有盖共享表身份（generator=ci、catalog）"
        }
        File(t.path + ".sig").writeText(io.github.wzgrx.dexadapt.TableSignature.sign(t.readBytes(), key))
        println("${t.path}.sig")
    }
    return 0
}

private fun decompile(opts: Opts): Int {
    val apk = File(opts.positional.firstOrNull() ?: error("缺少安装包路径"))
    val packages = opts.all("package")
    val strings = opts.all("string")
    val callTargets = opts.all("calls")
    // 按字符串、按调用找：用索引（有记录时几秒）找出方法里用到它的类
    val index by lazy { io.github.wzgrx.dexadapt.dexlib.DexIndex.load(apk) }
    fun report(label: String, owners: Set<String>) =
        System.err.println("$label：${owners.size} 个类" + if (owners.isNotEmpty()) "（${owners.take(8).joinToString()}${if (owners.size > 8) " …" else ""}）" else "")
    val byString = strings.flatMap { s ->
        index.methodsByString.entries.asSequence().filter { (k, _) -> s in k }.flatMap { (_, ms) -> ms.asSequence() }.map { it.owner }.toSortedSet()
            .also { report("“$s”", it) }
    } + callTargets.flatMap { c ->
        index.classes.values.asSequence().filter { cls -> cls.methods.any { m -> m.calls.any { c in it } } }.map { it.name }.toSortedSet()
            .also { report("调用 $c", it) }
    }
    val classes = opts.all("class") + byString
    require(classes.isNotEmpty() || packages.isNotEmpty()) { "至少给一个 --class、--package 或能找到类的 --string" }
    val limit = opts.get("limit")?.toInt() ?: 200
    val out = opts.get("out")?.let { File(it).apply { mkdirs() } }
    val args = jadx.api.JadxArgs().apply {
        inputFiles.add(apk)
        isSkipResources = true
        isShowInconsistentCode = true
        threadsCount = Runtime.getRuntime().availableProcessors().coerceAtMost(8)
    }
    val started = System.currentTimeMillis()
    jadx.api.JadxDecompiler(args).use { jadx ->
        jadx.load()
        // 原始名字（不受 jadx 改名影响）→ 类；内部类跟着外部类一起输出
        val byRaw = jadx.classesWithInners.associateBy { it.rawName }
        val picked = LinkedHashSet<jadx.api.JavaClass>()
        for (c in classes) picked += byRaw[c]?.let { it.topParentClass } ?: run { System.err.println("找不到类 $c"); continue }
        for (p in packages) jadx.classes.filter { it.rawName.startsWith("$p.") }.forEach { picked += it }
        if (picked.size > limit) System.err.println("匹配到 ${picked.size} 个类，只输出前 $limit 个（--limit 调整）")
        val chosen = picked.take(limit)
        System.err.println("加载 ${(System.currentTimeMillis() - started) / 1000} 秒，反编译 ${chosen.size} 个类…")
        for (c in chosen) {
            val code = runCatching { c.code }.getOrElse { "// 反编译失败：${it.message}" }
            if (out == null) {
                println("// ===== ${c.rawName} =====")
                println(code)
            } else {
                File(out, c.rawName.replace('.', '/') + ".java").apply { parentFile.mkdirs() }.writeText(code)
            }
        }
        if (out != null) println("${chosen.size} 个类 → ${out.path}（${(System.currentTimeMillis() - started) / 1000} 秒）")
    }
    return 0
}

private fun lint(opts: Opts): Int {
    val spec = Spec.read(File(opts.need("spec")))
    val issues = ArrayList(io.github.wzgrx.dexadapt.spec.SpecLint.check(spec.catalog))
    for (apk in opts.all("apk")) {
        Engines.open(File(apk), Engine.DEXLIB).use { o ->
            val table = SymbolResolver(o.source, o.info.versionCode).table(spec.catalog.symbols)
            issues += io.github.wzgrx.dexadapt.spec.SpecLint.check(spec.catalog, table, o.info.label)
        }
    }
    val byLevel = issues.groupBy { it.level }
    for (level in io.github.wzgrx.dexadapt.spec.SpecLint.Level.entries) byLevel[level].orEmpty().sortedBy { it.id }.forEach { println(it) }
    println("${spec.catalog.symbols.size} 个符号：错误 ${byLevel[io.github.wzgrx.dexadapt.spec.SpecLint.Level.ERROR]?.size ?: 0}，警告 ${byLevel[io.github.wzgrx.dexadapt.spec.SpecLint.Level.WARNING]?.size ?: 0}，提示 ${byLevel[io.github.wzgrx.dexadapt.spec.SpecLint.Level.HINT]?.size ?: 0}")
    return if (byLevel.containsKey(io.github.wzgrx.dexadapt.spec.SpecLint.Level.ERROR)) 3 else 0
}

private fun codegen(opts: Opts): Int {
    val spec = Spec.read(File(opts.need("spec")))
    val options = io.github.wzgrx.dexadapt.codegen.Accessors.Options(opts.need("package"), opts.get("class") ?: "HostAccess")
    val out = File(opts.need("out")).apply { absoluteFile.parentFile?.mkdirs() }
    out.writeText(io.github.wzgrx.dexadapt.codegen.Accessors.kotlin(spec.catalog, options))
    println("${spec.catalog.symbols.size} 个符号 → ${out.path}")
    return 0
}

private fun libraryDb(opts: Opts): io.github.wzgrx.dexadapt.libs.LibraryDb? =
    opts.get("db")?.let { io.github.wzgrx.dexadapt.libs.LibraryDb.read(File(it)) } ?: io.github.wzgrx.dexadapt.libs.LibraryDb.bundled()

/** 两个版本认出的库比一比：新增、去掉、版本变了。 */
private fun libraryDelta(old: List<io.github.wzgrx.dexadapt.libs.LibraryScanner.Detection>, new: List<io.github.wzgrx.dexadapt.libs.LibraryScanner.Detection>): io.github.wzgrx.dexadapt.diff.Delta {
    val o = old.associateBy { it.id }
    val n = new.associateBy { it.id }
    return io.github.wzgrx.dexadapt.diff.Delta(
        added = (n.keys - o.keys).sorted().map { "${n.getValue(it).name} ${n.getValue(it).versionText}" },
        removed = (o.keys - n.keys).sorted().map { "${o.getValue(it).name} ${o.getValue(it).versionText}" },
        changed = (o.keys intersect n.keys).sorted().filter { o.getValue(it).versions != n.getValue(it).versions }
            .map { "${n.getValue(it).name}：${o.getValue(it).versionText} → ${n.getValue(it).versionText}" },
    )
}

private fun libs(opts: Opts): Int {
    val path = File(opts.positional.firstOrNull() ?: error("缺少安装包路径"))
    val db = libraryDb(opts) ?: error("没有指纹库：用 --db 指定，或先用 libs-db 建一个")
    val out = File(opts.get("out") ?: "dexadapt-libs").apply { mkdirs() }
    Engines.open(path, Engine.DEXLIB).use { o ->
        val index = o.source as? io.github.wzgrx.dexadapt.dexlib.DexIndex ?: error("拆分包里有多份代码的暂不支持")
        val found = io.github.wzgrx.dexadapt.libs.LibraryScanner(db).scan(index)
        File(out, "libs.md").writeText(io.github.wzgrx.dexadapt.libs.LibraryReport.markdown(o.info.label, found))
        File(out, "libs.json").writeText(
            json.encodeToString(kotlinx.serialization.builtins.ListSerializer(io.github.wzgrx.dexadapt.libs.LibraryScanner.Detection.serializer()), found) + "\n",
        )
        File(out, "libs-mapping.txt").writeText(io.github.wzgrx.dexadapt.libs.LibraryReport.proguard(found))
        found.forEach { d -> println("${d.name} ${d.versionText}（可信度${d.confidence}，${d.hits}/${d.total} 个特征字符串，配上 ${d.classes.size} 个类，${d.classes.size - d.namesKept} 个改了名）") }
        println("输出在 ${out.path}/（libs.md、libs.json、libs-mapping.txt）")
    }
    return 0
}

private fun libsDb(opts: Opts): Int {
    val out = File(opts.need("out"))
    val entries = opts.all("lib").map { spec ->
        val parts = spec.split('=', limit = 2)
        val coords = parts[0]
        val name = parts.getOrElse(1) { coords.substringAfter(':') }
        val (g, a) = coords.split(':').takeIf { it.size == 2 } ?: error("--lib 要写成 group:artifact=显示名")
        io.github.wzgrx.dexadapt.libs.KnownLibraries.Entry(g, a, name)
    }.ifEmpty { io.github.wzgrx.dexadapt.libs.KnownLibraries.ALL }
    val built = io.github.wzgrx.dexadapt.libs.KnownLibraries.build(io.github.wzgrx.dexadapt.libs.Maven(), entries) { println(it) }
    val base = opts.get("add-to")?.let { io.github.wzgrx.dexadapt.libs.LibraryDb.read(File(it)) }
    val db = base?.let { b -> built.libraries.fold(b) { acc, l -> acc.with(l) } } ?: built
    out.absoluteFile.parentFile?.mkdirs()
    db.write(out)
    println("${db.libraries.size} 个库，写到 ${out.path}（${out.length() / 1024} KB）")
    return 0
}

/** `类名`、`类#方法(参数)返回`、`类#字段`（字段类型从安装包里读）。 */
private fun target(text: String, source: io.github.wzgrx.dexadapt.resolve.SymbolSource): io.github.wzgrx.dexadapt.Resolved {
    if ('#' !in text) return io.github.wzgrx.dexadapt.Resolved.Class(text)
    val owner = text.substringBefore('#')
    val rest = text.substringAfter('#')
    if ('(' in rest) {
        val name = rest.substringBefore('(')
        val params = rest.substringAfter('(').substringBefore(')').split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val ret = rest.substringAfterLast(')').ifEmpty {
            source.classByName(owner)?.methods?.firstOrNull { it.name == name && it.params == params }?.returns ?: error("$text 不在安装包里")
        }
        return io.github.wzgrx.dexadapt.Resolved.Method(owner, name, params, ret)
    }
    val f = source.classByName(owner)?.fields?.firstOrNull { it.name == rest } ?: error("$text 不在安装包里")
    return io.github.wzgrx.dexadapt.Resolved.Field(owner, f.name, f.type)
}

private fun suggest(opts: Opts): Int {
    Engines.open(File(opts.need("apk")), Engine.DEXLIB).use { main ->
        val others = opts.all("other").map { Engines.open(File(it), Engine.DEXLIB) }
        try {
            val t = target(opts.need("target"), main.source)
            val s = io.github.wzgrx.dexadapt.synth.Suggester(main.source, others.map { it.source }).suggest(t, opts.get("id") ?: "suggested")
            println("# ${s.why}")
            if (s.query == null) return 3
            print(Spec(null, io.github.wzgrx.dexadapt.Catalog(s.extra + listOfNotNull(s.query))).toYaml())
            return 0
        } finally {
            others.forEach { it.close() }
        }
    }
}

private fun repairJson(r:RepairRun):kotlinx.serialization.json.JsonObject = kotlinx.serialization.json.JsonObject(mapOf(
    "broken" to kotlinx.serialization.json.JsonPrimitive(r.broken),"fixed" to kotlinx.serialization.json.JsonPrimitive(r.fixed),
    "fixes" to kotlinx.serialization.json.JsonArray(r.fixes.map { json.encodeToJsonElement(io.github.wzgrx.dexadapt.synth.Repair.Fix.serializer(),it) })))

private fun repair(opts: Opts): Int {
    val r=runRepair(opts)
    opts.get("out")?.let { path->val out=File(path).apply { mkdirs() }
        File(out,"repair.json").writeText(repairJson(r).toString()+"\n")
        File(out,"repair.md").writeText(r.text)
        File(out,"candidate-spec.json").writeText(r.candidate.toJson()+"\n")
    }
    if(opts.flag("json"))println(repairJson(r)) else print(r.text)
    return r.code
}

private class RepairRun(val code:Int,val text:String,val broken:Int,val fixed:Int,
    val fixes:List<io.github.wzgrx.dexadapt.synth.Repair.Fix>,val candidate:Spec)

private fun runRepair(opts:Opts):RepairRun {
    val result=runPairRepair(opts)
    if(result.code!=0)return result
    val original=Spec.read(File(opts.need("spec")))
    val pairPaths=setOf(File(opts.need("old")).canonicalPath,File(opts.need("new")).canonicalPath)
    val paths=opts.all("apk").map(::File).distinctBy { it.canonicalPath }.filter { it.canonicalPath !in pairPaths }
    val channels=opts.all("channel").associate { it.substringBeforeLast('=') to it.substringAfterLast('=') }
    val checked=ArrayList<String>()
    for(path in paths) {
        // 旧/新版索引已离开作用域；逐包验证，不同时把十一份大型索引留在堆里。
        Engines.open(path,Engine.DEXLIB).use { o->
            val build=Engines.build(path,o.info,channels)
            val before=SymbolResolver.forBuild(o.source,o.info.versionCode,build.id).table(original.catalog.symbols)
            val after=SymbolResolver.forBuild(o.source,o.info.versionCode,build.id).table(result.candidate.catalog.symbols)
            val changed=before.entries.keys.filter { before.entries[it]!=after.entries[it] }
            if(before.missing.isNotEmpty() || after.missing.isNotEmpty() || changed.isNotEmpty()) {
                val detail="额外回归 ${build.id} 未通过：旧缺失${before.missing.size}，新缺失${after.missing.size}，原目标改变${changed.size}"
                return RepairRun(3,result.text+"\n$detail\n",result.broken,0,result.fixes.map { it.copy(yaml=null,candidate=null,extra=emptyList(),action="额外回归失败，不应用候选") },original)
            }
            checked+=build.id;if(!opts.flag("json"))println("额外回归 ${build.id}：${after.entries.size} 个目标保持一致")
        }
    }
    return RepairRun(result.code,result.text,result.broken,result.fixed,result.fixes.map { it.copy(checkedBuilds=(it.checkedBuilds+checked).distinct()) },result.candidate)
}

private fun runPairRepair(opts: Opts): RepairRun {
    val spec=Spec.read(File(opts.need("spec")));val text=StringBuilder()
    Engines.open(File(opts.need("old")),Engine.DEXLIB).use { a->
        Engines.open(File(opts.need("new")),Engine.DEXLIB).use { b->
            run {
                val samples=emptyList<io.github.wzgrx.dexadapt.synth.Repair.Sample>()
                val tb=SymbolResolver.forBuild(b.source,b.info.versionCode,opts.get("new-build")).table(spec.catalog.symbols)
                val wanted=opts.all("id").toSet()
                val broken=spec.catalog.symbols.filter { it.id in tb.missing && (wanted.isEmpty() || it.id in wanted) }
                if(broken.isEmpty())return RepairRun(0,"新版里 spec 的符号都能解析，不用修\n",0,0,emptyList(),spec)
                val mapping=io.github.wzgrx.dexadapt.diff.ClassMatcher(a.diffIndex(),b.diffIndex()).run()
                val ta=SymbolResolver.forBuild(a.source,a.info.versionCode,opts.get("old-build")).table(spec.catalog.symbols)
                val diff=io.github.wzgrx.dexadapt.diff.SymbolDiff.of(ta,tb,mapping)
                val fixes=ArrayList<io.github.wzgrx.dexadapt.synth.Repair.Fix>();var candidate=spec;var fixed=0
                for ((index,original) in broken.withIndex()) {
                    if(!opts.flag("json"))println("修复 [${index+1}/${broken.size}] ${original.id}（额外回归 ${samples.size} 个构建）")
                    val symbol=candidate.catalog.symbols.single { it.id==original.id }
                    val restored=SymbolResolver.forBuild(b.source,b.info.versionCode,opts.get("new-build")).resolve(symbol).getOrNull()
                    if(restored!=null) {
                        fixed++;fixes+=io.github.wzgrx.dexadapt.synth.Repair.Fix(original.id,"依赖修复后恢复","无需修改本符号",io.github.wzgrx.dexadapt.synth.Repair.text(restored),null)
                        continue
                    }
                    val repair=io.github.wzgrx.dexadapt.synth.Repair(a.source,b.source,a.info.versionCode,b.info.versionCode,
                        opts.get("old-build"),opts.get("new-build"),samples,candidate.catalog)
                    val guessText=diff.rows.firstOrNull { it.id==symbol.id }?.guess
                    val guess=guessText?.takeIf { '?' !in it }?.let { runCatching { target(it,b.source) }.getOrNull() }
                    val fix=repair.repair(symbol,guess);fixes+=fix
                    text.appendLine("## ${symbol.id}\n诊断：${fix.diagnosis}\n处理：${fix.action}"+(fix.newLocation?.let { "\n新位置：$it" }?:""))
                    fix.candidate?.let { replacement->candidate=candidate.withSymbols(fix.extra+replacement);fixed++ }
                    fix.yaml?.let { text.appendLine(it) };text.appendLine()
                }
                val oldAfter=SymbolResolver.forBuild(a.source,a.info.versionCode,opts.get("old-build")).table(candidate.catalog.symbols)
                val newAfter=SymbolResolver.forBuild(b.source,b.info.versionCode,opts.get("new-build")).table(candidate.catalog.symbols)
                val displacedOld=ta.entries.keys.filter { ta.entries[it]!=oldAfter.entries[it] }
                val displacedNew=tb.entries.keys.filter { tb.entries[it]!=newAfter.entries[it] }
                if(displacedOld.isNotEmpty() || displacedNew.isNotEmpty()) {
                    text.appendLine("候选改变了原有健康目标（旧版${displacedOld.size}、新版${displacedNew.size}），不应用。")
                    return RepairRun(3,text.toString(),broken.size,0,fixes.map { it.copy(yaml=null,candidate=null,extra=emptyList(),action="健康目标发生变化，不应用候选") },spec)
                }
                text.appendLine("找不到 ${broken.size} 个，给出修复或依赖恢复 $fixed 个；候选需经完整matrix/parity复验。")
                return RepairRun(if(fixed==broken.size)0 else 3,text.toString(),broken.size,fixed,fixes,candidate)
            }
        }
    }
}

/**
 * 新版本一条龙（设计 10.7）：diff 出升级报告 → matrix 在旧版、新版（和 --apk 给的别的样本）上解析，出余量和矩阵 →
 * 新版找不到的跑 repair → 写 summary.md（可以直接当更新 spec 的 PR 说明）。新版全部解析到时退出码 0，否则 3。
 */
private fun upgrade(opts: Opts): Int {
    val spec = opts.need("spec")
    val old = opts.need("old")
    val new = opts.need("new")
    val out = File(opts.get("out") ?: "dexadapt-upgrade").apply { mkdirs() }
    fun pass(vararg keys: String) = keys.flatMap { k -> opts.all(k).flatMap { listOf("--$k", it) } }
    println("== 1/3 升级分析")
    val report = runDiff(Opts(listOf(old, new, "--spec", spec) + pass("old-build", "new-build", "db") + listOf("--out", File(out, "diff").path)))
    println("== 2/3 版本矩阵")
    val apks = (listOf(old, new) + opts.all("apk")).distinct()
    // 矩阵按渠道_版本号认构建：给了 --old-build / --new-build（例如 CN_9500803）时旧版、新版的渠道照它，钉子和“没有”的标注才对得上
    val derived = listOf(old to opts.get("old-build"), new to opts.get("new-build"))
        .mapNotNull { (apk, b) -> b?.takeIf { '_' in it }?.let { "$apk=" + it.substringBeforeLast('_').lowercase() } }
    val channels = (derived + opts.all("channel")).flatMap { listOf("--channel", it) }
    val matrixFlags=listOf("parity","incremental").filter(opts::flag).map { "--$it" }
    var m = runMatrix(Opts(listOf("--spec", spec) + apks.flatMap { listOf("--apk", it) } + channels + matrixFlags + listOf("--out", File(out, "matrix").path)))
    println("== 3/3 修复")
    val r = runRepair(Opts(listOf("--spec", spec, "--old", old, "--new", new) + pass("old-build", "new-build") + opts.all("apk").flatMap { listOf("--apk",it) } + channels))
    File(out,"repair.json").writeText(repairJson(r).toString()+"\n")
    val candidate=File(out,"candidate-spec.json").apply { writeText(r.candidate.toJson()+"\n") }
    if(opts.flag("verify-repairs") && r.code==0 && r.broken>0) {
        println("== 4/4 候选补丁全版本复验（原spec保持不变）")
        m=runMatrix(Opts(listOf("--spec",candidate.path)+apks.flatMap { listOf("--apk",it) }+channels+matrixFlags+listOf("--out",File(out,"verified-matrix").path)))
    }
    File(out, "repair.md").writeText(r.text)
    print(r.text)
    val summary = buildString {
        appendLine("# 适配 ${report.pkg.new}（从 ${report.pkg.old} 升级）")
        appendLine()
        appendLine("由 `dexadapt upgrade` 生成。详细报告：`diff/report.html`、`matrix/matrix.html`、`repair.md`。")
        appendLine()
        report.symbols?.counts?.let { c ->
            appendLine("## 模块符号")
            appendLine()
            appendLine(c.entries.sortedBy { it.key.ordinal }.joinToString("，") { (k, v) -> "$k $v" })
            appendLine()
        }
        appendLine("## 各版本解析")
        appendLine()
        appendLine("| 版本 | versionCode | 解析到 | 缺失 | 单点依赖 | 其中仅靠形状 |")
        appendLine("|---|---|---|---|---|---|")
        for ((b, t) in m.tables) {
            val sp = m.margins[b.versionCode].orEmpty().filter { it.singlePoint }
            appendLine("| ${b.label} | ${b.versionCode} | ${t.entries.size} | ${t.missing.size} | ${sp.size} | ${sp.count { it.shapeOnly }} |")
        }
        appendLine()
        appendLine("## 修复")
        appendLine()
        appendLine(if (r.broken == 0) "新版里全部符号都能解析。" else "新版找不到 ${r.broken} 个，给出修复 ${r.fixed} 个（见 `repair.md`，每条经指定构建及额外回归样本验证；合进 Kotlin/spec 目录前再跑一次 `lint` 和 `matrix`）。")
    }
    File(out, "summary.md").writeText(summary)
    println("汇总：${File(out, "summary.md").path}")
    return if ((r.broken == 0 || opts.flag("verify-repairs") && r.code==0) && m.code == 0) 0 else 3
}

private fun importFingerprints(opts: Opts): Int {
    val format = when (opts.need("from")) {
        "revanced" -> io.github.wzgrx.dexadapt.spec.Fingerprints.Format.REVANCED
        "morphe" -> io.github.wzgrx.dexadapt.spec.Fingerprints.Format.MORPHE
        "qauxiliary" -> io.github.wzgrx.dexadapt.spec.Fingerprints.Format.QAUXILIARY
        else -> error("--from 只能是 revanced、morphe、qauxiliary")
    }
    val files = opts.positional.ifEmpty { error("缺少源文件") }.map(::File)
    val group = opts.get("group") ?: "imported"
    val results = files.map { io.github.wzgrx.dexadapt.spec.Fingerprints.import(it.readText(), format, group) }
    val symbols = results.flatMap { it.symbols }.distinctBy { it.id }
    val out = File(opts.need("out"))
    val spec = Spec(null, io.github.wzgrx.dexadapt.Catalog(symbols))
    out.writeText(if (out.name.endsWith(".json")) spec.toJson() + "\n" else spec.toYaml())
    println("${symbols.size} 个指纹 → ${out.path}")
    results.flatMap { it.notes }.forEach { println("  注意：$it") }
    return 0
}

private fun export(opts: Opts): Int {
    if (opts.get("to") == "revanced") {
        val spec = Spec.read(File(opts.need("spec")))
        val methods = spec.catalog.symbols.filterIsInstance<io.github.wzgrx.dexadapt.MethodSymbol>()
        File(opts.need("out")).writeText(io.github.wzgrx.dexadapt.spec.Fingerprints.toRevanced(methods))
        println("${methods.size} 个方法符号 → ${opts.need("out")}")
        return 0
    }
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
