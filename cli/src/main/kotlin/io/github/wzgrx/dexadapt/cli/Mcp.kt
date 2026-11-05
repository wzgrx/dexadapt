package io.github.wzgrx.dexadapt.cli

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * `dexadapt mcp`：把命令行的各个命令作为 MCP 工具（标准输入输出上的 JSON-RPC，一行一条消息），
 * 让 Claude 这类 Agent 直接调用：新版本来了 → inspect / diff 出报告 → repair 修查询 → resolve / matrix 验证。
 * 每个工具就是对应的命令行命令，参数一一对应；命令打印的内容作为结果返回，写了报告文件的再附上报告正文。
 */
object Mcp {
    private const val PROTOCOL = "2025-06-18"
    private val json = Json { ignoreUnknownKeys = true }

    private class Param(val name: String, val type: String, val description: String, val required: Boolean = false, val flag: String = name.replace('_', '-'))

    private class Tool(
        val name: String,
        val description: String,
        val params: List<Param>,
        /** 位置参数（按顺序）。 */
        val positional: List<String> = emptyList(),
        /** 跑完后附上的报告文件（相对 out 目录）。 */
        val report: String? = null,
        val defaultOut: String? = null,
    )

    private val TOOLS = listOf(
        Tool(
            "inspect", "安装包档案：版本、签名（是否被重签）、清单（权限、组件、链接）、dex、原生库。安装包可以是 .apk、拆分包目录、.apks / .xapk。",
            listOf(Param("apk", "string", "安装包路径", true)), positional = listOf("apk"),
        ),
        Tool(
            "resolve", "按 spec 解析一个安装包，得到符号表；列出解析不到的符号和原因。",
            listOf(
                Param("spec", "string", "spec 文件（YAML / JSON）", true), Param("apk", "string", "安装包路径", true),
                Param("build", "string", "构建标识（用这个构建的钉子和“没有”的标注）"), Param("engine", "string", "dexlib（默认）或 dexkit"),
                Param("out", "string", "符号表输出路径"),
            ),
        ),
        Tool(
            "matrix", "多个版本一起解析：符号表、余量（单点依赖的查询）、版本矩阵（漂移）；parity 时再用 DexKit 解析要求逐条一致。",
            listOf(
                Param("spec", "string", "spec 文件", true), Param("apk", "array", "安装包路径（多个）", true),
                Param("channel", "array", "安装包=渠道"), Param("parity", "boolean", "再用 DexKit 核对"), Param("incremental","boolean","复用身份/引擎/spec完全一致的矩阵结果，parity仍重新运行"), Param("out", "string", "输出目录"),
            ),
            report = "matrix.md", defaultOut = "dexadapt-matrix",
        ),
        Tool(
            "diff", "升级分析：包、签名、清单、原生库、开源库、代码、接口、字符串，旧类到新类的混淆映射；给了 spec 时列出模块每个符号的新位置、找不到的推测位置、方法体变化和 Hook 风险。比较慢（几分钟）。",
            listOf(
                Param("old", "string", "旧安装包", true), Param("new", "string", "新安装包", true), Param("spec", "string", "模块的 spec"),
                Param("old_build", "string", "旧版构建标识"), Param("new_build", "string", "新版构建标识"), Param("out", "string", "输出目录"),
            ),
            positional = listOf("old", "new"), report = "report.md", defaultOut = "dexadapt-diff",
        ),
        Tool(
            "suggest", "给一个目标（类名、类#方法(参数)返回、类#字段）自动写查询，要求在 other 给的别的版本里也唯一命中；输出 YAML。",
            listOf(
                Param("apk", "string", "安装包", true), Param("target", "string", "目标", true),
                Param("other", "array", "别的版本的安装包"), Param("id", "string", "符号 id"),
            ),
        ),
        Tool(
            "repair", "新版找不到的符号：诊断哪个条件不满足，给出修好的查询（在新旧两版都验证过）；输出 YAML。",
            listOf(
                Param("spec", "string", "spec 文件", true), Param("old", "string", "旧安装包", true), Param("new", "string", "新安装包", true),
                Param("old_build", "string", "旧版构建标识"), Param("new_build", "string", "新版构建标识"),
                Param("id", "array", "只修这些符号"),Param("apk","array","额外回归安装包"),Param("channel","array","安装包=渠道"),
                Param("json","boolean","输出结构化修复证据"),Param("out","string","候选补丁与报告目录"),
            ),report="repair.md",defaultOut="dexadapt-repair",
        ),
        Tool(
            "upgrade", "新版本一条龙：diff → matrix（旧版、新版和 apk 给的别的样本）→ repair，返回 summary.md（可当更新 spec 的 PR 说明）；详细报告在 out 目录。很慢（十分钟级）。",
            listOf(
                Param("spec", "string", "spec 文件", true), Param("old", "string", "旧安装包", true), Param("new", "string", "新安装包", true),
                Param("apk", "array", "别的样本安装包"), Param("channel", "array", "安装包=渠道"),
                Param("parity","boolean","矩阵逐项做DexKit复验"),Param("incremental","boolean","严格身份匹配后复用矩阵结果"),
                Param("verify_repairs","boolean","对生成的候选spec重新跑全版本矩阵，保留原spec"),
                Param("old_build", "string", "旧版构建标识"), Param("new_build", "string", "新版构建标识"), Param("out", "string", "输出目录"),
            ),
            report = "summary.md", defaultOut = "dexadapt-upgrade",
        ),
        Tool(
            "libs", "认出打包的开源库（OkHttp、Retrofit、RxJava、Gson、协程……）：版本、混淆后的类对应库里的哪个类、包被改到哪里。",
            listOf(Param("apk", "string", "安装包", true), Param("db", "string", "指纹库（默认用内置的）"), Param("out", "string", "输出目录")),
            positional = listOf("apk"), report = "libs.md", defaultOut = "dexadapt-libs",
        ),
        Tool(
            "decompile", "用 jadx 反编译指定的类或整个包，返回 Java 源码（写 Hook、确认类的用途时看）。类名用安装包里的原始名字；整包很大时给 out 目录。",
            listOf(
                Param("apk", "string", "安装包", true), Param("class", "array", "类名（可多个）"), Param("package", "array", "包名前缀（可多个）"),
                Param("string", "array", "按方法里用到的字符串找类（包含即可）"),
                Param("calls", "array", "按调用了哪个方法找类（类#方法，包含即可）"),
                Param("out", "string", "输出目录（不给时直接返回源码）"), Param("limit", "string", "最多几个类（默认 200）"),
            ),
            positional = listOf("apk"),
        ),
        Tool(
            "lint", "检查 spec 的写法（引用、成环、只靠形状、按混淆名找、手机上要全量扫描的查询）；给了安装包再查同组重复命中、契约、解析不到。",
            listOf(Param("spec", "string", "spec 文件", true), Param("apk", "array", "安装包（可多个）")),
        ),
        Tool(
            "export", "spec 转换格式（YAML ⇄ JSON）。",
            listOf(Param("spec", "string", "输入 spec", true), Param("out", "string", "输出文件（.yaml / .json）", true)),
        ),
    )

    /** 常驻客户端关闭输入时取消任务；批量嵌入调用可显式等待队列处理完毕。 */
    fun serve(input: java.io.InputStream = System.`in`, output: PrintStream = System.out, drainOnEof: Boolean = false) =
        serveSession(input, output, drainOnEof, ::runIsolated)

    private data class RunResult(val code: Int, val text: String, val truncated: Boolean = false)

    internal class Cancellation {
        private val cancelled = AtomicBoolean()
        private val process = AtomicReference<Process?>()
        val isCancelled: Boolean get() = cancelled.get()
        fun check() { if (isCancelled) throw CancellationException("任务已取消") }
        fun attach(value: Process) { process.set(value); if (isCancelled) stop(value) }
        fun detach(value: Process) { process.compareAndSet(value, null) }
        fun cancel() { if (cancelled.compareAndSet(false, true)) process.get()?.let(::stop) }
        private fun stop(value: Process) {
            Thread({
                runCatching {
                    val children = value.toHandle().descendants().use { it.toList() }
                    children.forEach { it.destroy() }
                    value.destroy()
                    if (!value.waitFor(1, TimeUnit.SECONDS)) value.destroyForcibly()
                    children.filter { it.isAlive }.forEach { it.destroyForcibly() }
                }
            }, "dexadapt-cancel").apply { isDaemon = true; start() }
        }
    }

    internal fun serveForTest(input: java.io.InputStream, output: PrintStream, runner: (List<String>, Cancellation, (Long) -> Unit) -> Pair<Int, String>) =
        serveSession(input, output, true) { argv, token, tick ->
            val (code, text) = runner(argv, token, tick)
            RunResult(code, text)
        }

    private fun serveSession(input: java.io.InputStream, output: PrintStream, drainOnEof: Boolean, runner: (List<String>, Cancellation, (Long) -> Unit) -> RunResult) {
        val jobs = ConcurrentHashMap<JsonElement, Cancellation>()
        val worker = Executors.newSingleThreadExecutor { task -> Thread(task, "dexadapt-mcp-job").apply { isDaemon = true } }
        val shutdown = Thread({ jobs.values.forEach { it.cancel() } }, "dexadapt-mcp-shutdown")
        Runtime.getRuntime().addShutdownHook(shutdown)
        try {
            val reader = input.bufferedReader()
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isBlank()) continue
                val msg = runCatching { json.parseToJsonElement(line).jsonObject }.getOrElse {
                    send(output, error(JsonNull, -32700, "无法解析的 JSON")); continue
                }
                val method = (msg["method"] as? JsonPrimitive)?.contentOrNull
                val params = msg["params"] as? JsonObject ?: JsonObject(emptyMap())
                if (method == "notifications/cancelled") {
                    params["requestId"]?.let { jobs[it]?.cancel() }; continue
                }
                val id = msg["id"] ?: continue
                if (method == "tools/call") {
                    if (jobs.size >= 8) { send(output, error(id, -32001, "适配任务队列已满")); continue }
                    val token = Cancellation()
                    if (jobs.putIfAbsent(id, token) != null) { send(output, error(id, -32600, "请求编号重复")); continue }
                    worker.execute {
                        val reply = try {
                            token.check()
                            result(id, call(params, output, token, runner))
                        } catch (_: CancellationException) {
                            error(id, -32800, "任务已取消")
                        } catch (e: Exception) {
                            error(id, -32603, e.message ?: e.toString())
                        } finally { jobs.remove(id, token) }
                        send(output, reply)
                    }
                } else {
                    val reply = runCatching {
                        when (method) {
                            "initialize" -> result(id, initialize(params))
                            "ping" -> result(id, JsonObject(emptyMap()))
                            "tools/list" -> result(id, buildJsonObject { put("tools", JsonArray(TOOLS.map(::describe))) })
                            else -> error(id, -32601, "不支持的方法 $method")
                        }
                    }.getOrElse { e -> error(id, -32603, e.message ?: e.toString()) }
                    send(output, reply)
                }
            }
        } finally {
            worker.shutdown()
            if (drainOnEof) {
                while (!worker.awaitTermination(1, TimeUnit.SECONDS)) { /* 嵌入调用等待实际句柄。 */ }
            } else {
                jobs.values.forEach { it.cancel() }
                worker.shutdownNow()
                worker.awaitTermination(3, TimeUnit.SECONDS)
            }
            runCatching { Runtime.getRuntime().removeShutdownHook(shutdown) }
        }
    }

    private fun initialize(params: JsonObject): JsonObject = buildJsonObject {
        put("protocolVersion", params["protocolVersion"]?.jsonPrimitive?.contentOrNull?.takeIf { it == PROTOCOL } ?: PROTOCOL)
        putJsonObject("capabilities") { putJsonObject("tools") {} }
        putJsonObject("serverInfo") {
            put("name", "dexadapt")
            put("version", Mcp::class.java.`package`?.implementationVersion ?: "dev")
        }
        put("instructions", "DexAdapt：LSPosed / Xposed 模块的版本适配工具。路径都是本机的绝对路径。diff、matrix 比较慢（几分钟），一次只跑一个。")
    }

    private fun describe(t: Tool): JsonObject = buildJsonObject {
        put("name", t.name)
        put("description", t.description)
        putJsonObject("outputSchema") {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("tool") { put("type", "string") }
                putJsonObject("exitCode") { put("type", "integer") }
                putJsonObject("status") { put("type", "string") }
                putJsonObject("output") { put("type", "string") }
                putJsonObject("outputTruncated") { put("type", "boolean") }
                putJsonObject("outputDirectory") { put("type", "string") }
                putJsonObject("reportPath") { put("type", "string") }
                put("data", JsonObject(emptyMap()))
            }
            putJsonArray("required") { listOf("tool", "exitCode", "status", "output", "outputTruncated").forEach { add(JsonPrimitive(it)) } }
        }
        putJsonObject("inputSchema") {
            put("type", "object")
            putJsonObject("properties") {
                for (p in t.params) putJsonObject(p.name) {
                    put("type", p.type)
                    if (p.type == "array") putJsonObject("items") { put("type", "string") }
                    put("description", p.description)
                }
            }
            putJsonArray("required") { t.params.filter { it.required }.forEach { add(JsonPrimitive(it.name)) } }
        }
    }

    /** 跑一个工具：拼成命令行参数，捕获它打印的内容（标准输出是 MCP 的通道，不能让命令直接写）。 */
    private fun call(params: JsonObject, channel: PrintStream, cancellation: Cancellation, runner: (List<String>, Cancellation, (Long) -> Unit) -> RunResult): JsonObject {
        val name = params["name"]?.jsonPrimitive?.contentOrNull ?: error("缺少工具名")
        val tool = TOOLS.firstOrNull { it.name == name } ?: error("没有工具 $name")
        val args = params["arguments"] as? JsonObject ?: JsonObject(emptyMap())
        val argv = ArrayList<String>()
        argv += tool.name
        for (p in tool.positional) argv += args[p]?.jsonPrimitive?.contentOrNull ?: error("缺少参数 $p")
        var out: String? = null
        for (p in tool.params) {
            if (p.name in tool.positional) continue
            val v = args[p.name] ?: continue
            when (p.type) {
                "boolean" -> if ((v as? JsonPrimitive)?.booleanOrNull == true) argv += "--${p.flag}"
                "array" -> (v as? JsonArray ?: buildJsonArray { add(v) }).forEach { argv += "--${p.flag}"; argv += it.jsonPrimitive.content }
                else -> {
                    argv += "--${p.flag}"; argv += v.jsonPrimitive.content
                    if (p.name == "out") out = v.jsonPrimitive.content
                }
            }
        }
        if (out == null && tool.defaultOut != null) {
            out = File(System.getProperty("java.io.tmpdir"), "${tool.defaultOut}-${System.currentTimeMillis()}").path
            argv += "--out"; argv += out!!
        }
        for (p in tool.params) if (p.required && p.name !in args) error("缺少参数 ${p.name}")

        val progressToken = (params["_meta"] as? JsonObject)?.get("progressToken")
        val run = runner(argv, cancellation) { seconds ->
            // 客户端给了进度令牌时，长任务（diff、matrix）每 10 秒报一次进度，免得客户端当它卡死超时
            if (progressToken != null) {
                send(
                    channel,
                    buildJsonObject {
                        put("jsonrpc", "2.0")
                        put("method", "notifications/progress")
                        putJsonObject("params") {
                            put("progressToken", progressToken)
                            put("progress", seconds)
                            put("message", "${tool.name} 运行中（$seconds 秒）")
                        }
                    },
                )
            }
        }
        cancellation.check()
        val code = run.code
        val captured = run.text
        val printed = captured.take(CAPTURE_LIMIT)
        val report = tool.report?.let { r -> out?.let { File(it, r) } }?.takeIf { it.isFile }
        val text = buildString {
            append(printed)
            if (report != null) {
                val body = report.bufferedReader().use { reader ->
                    val buffer = CharArray(REPORT_LIMIT + 1)
                    var count = 0
                    while (count < buffer.size) { val n = reader.read(buffer, count, buffer.size - count); if (n < 0) break; count += n }
                    String(buffer, 0, count)
                }
                append("\n---\n").append(report.path).append("：\n\n")
                append(if (body.length > REPORT_LIMIT) body.take(REPORT_LIMIT) + "\n…（截断，完整内容见文件）" else body)
            }
        }
        val structured = buildJsonObject {
            put("tool", tool.name); put("exitCode", code); put("status", if (code == 0) "completed" else "failed")
            put("output", printed); put("outputTruncated", run.truncated || captured.length > CAPTURE_LIMIT)
            out?.let { put("outputDirectory", File(it).absolutePath) }
            report?.let { put("reportPath", it.absolutePath) }
            runCatching { json.parseToJsonElement(printed.trim()) }.getOrNull()?.let { put("data", it) }
        }
        return buildJsonObject {
            putJsonArray("content") { add(buildJsonObject { put("type", "text"); put("text", text) }) }
            put("structuredContent", structured)
            put("isError", code != 0)
        }
    }

    private const val REPORT_LIMIT = 60_000
    private const val CAPTURE_LIMIT = 1_048_576

    /**
     * 在子进程里跑命令行（同一个 JVM 程序、同样的类路径和内存上限），拿回它打印的内容和退出码。
     * 为什么不在本进程里跑：标准输出是 MCP 的通道，DexKit 这类原生库会直接往标准输出写日志（绕过 System.out），
     * 混进通道里客户端就解析不了；子进程也让一次调用内存爆掉、崩溃时不连累整个服务。索引有磁盘记录，子进程启动只多一两秒。
     * 子进程的标准错误转到本进程的标准错误（客户端一般记进日志）。[tick] 每 10 秒调用一次（参数是已运行秒数）。
     */
    private fun runIsolated(argv: List<String>, cancellation: Cancellation, tick: (Long) -> Unit): RunResult {
        val javaBin = ProcessHandle.current().info().command().orElse("java")
        val cmd = listOf(
            javaBin, "-Xmx${Runtime.getRuntime().maxMemory() / 1048576}m", "--enable-native-access=ALL-UNNAMED",
            "-cp", System.getProperty("java.class.path"), "io.github.wzgrx.dexadapt.cli.MainKt",
        ) + argv
        val p = ProcessBuilder(cmd).redirectError(ProcessBuilder.Redirect.INHERIT).start()
        cancellation.attach(p)
        p.outputStream.close()
        val out = ByteArrayOutputStream()
        var truncated = false
        val reader = Thread({
            p.inputStream.use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    val n = input.read(buffer); if (n < 0) break
                    val remaining = CAPTURE_LIMIT - out.size()
                    if (remaining > 0) out.write(buffer, 0, n.coerceAtMost(remaining))
                    if (n > remaining) truncated = true
                }
            }
        }, "dexadapt-mcp-out").apply { isDaemon = true; start() }
        try {
            val started = System.nanoTime()
            while (!p.waitFor(1, TimeUnit.SECONDS)) {
                cancellation.check()
                val seconds = (System.nanoTime() - started) / 1_000_000_000
                if (seconds > 0 && seconds % 10 == 0L) tick(seconds)
            }
            reader.join()
            cancellation.check()
            val text = out.toString(Charsets.UTF_8).lines().filterNot { NATIVE_LOG.matches(it) }.joinToString("\n")
            return RunResult(p.exitValue(), text, truncated)
        } finally {
            if (p.isAlive) cancellation.cancel()
            cancellation.detach(p)
        }
    }

    private val NATIVE_LOG = Regex("^[VDIWEF]/\\w+: .*")

    private fun result(id: JsonElement, result: JsonObject) = buildJsonObject {
        put("jsonrpc", "2.0"); put("id", id); put("result", result)
    }

    private fun error(id: JsonElement, code: Int, message: String) = buildJsonObject {
        put("jsonrpc", "2.0"); put("id", id)
        putJsonObject("error") { put("code", code); put("message", message) }
    }

    private fun send(output: PrintStream, msg: JsonObject) {
        synchronized(output) { output.println(msg.toString()); output.flush() }
    }

    /** 测试用：参数表里的工具名。 */
    internal val toolNames: List<String> get() = TOOLS.map { it.name }
}
