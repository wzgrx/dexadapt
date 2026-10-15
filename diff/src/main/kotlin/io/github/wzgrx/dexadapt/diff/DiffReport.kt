package io.github.wzgrx.dexadapt.diff

import kotlinx.serialization.Serializable

/** 方法体相似度低于这个就提醒复核。 */
private const val BODY_WARN = 0.6

/** 一次升级分析的全部结果（可以序列化成 JSON，也能出 Markdown）。 */
@Serializable
data class DiffReport(
    val pkg: PackageDiff,
    val code: CodeDiff,
    val symbols: SymbolDiff?,
    /** 打包的开源库（新增、去掉、版本变化），由调用方用开源库识别算好填进来。 */
    val libraries: Delta = Delta(),
    /** 原生库的导出符号和依赖变化（[NativeDiff]），每行一个库。 */
    val nativeSymbols: Delta = Delta(),
) {
    fun markdown(limit: Int = 60): String = buildString {
        appendLine("# 升级分析：${pkg.old} → ${pkg.new}")
        appendLine()
        appendLine("## 概要")
        appendLine()
        appendLine("| 项 | 旧 → 新 |")
        appendLine("|---|---|")
        pkg.fields.forEach { (k, v) -> appendLine("| $k | $v |") }
        appendLine("| 签名证书 | ${if (pkg.sameSigner) "相同" else "**不同**（换了签名，或其中一个被重签过）"} |")
        if (!pkg.signatureFiles.empty) appendLine("| 签名文件 | ${delta(pkg.signatureFiles, 6)} |")
        appendLine("| dex | ${pkg.dexCount.first} → ${pkg.dexCount.second} 个，${mb(pkg.dexBytes.first)} → ${mb(pkg.dexBytes.second)} |")
        appendLine("| 类 / 方法 / 字段 | ${code.classes.first} → ${code.classes.second} / ${code.methods.first} → ${code.methods.second} / ${code.fields.first} → ${code.fields.second} |")
        val m = code.mapping
        appendLine("| 混淆映射 | 旧版 ${m.oldClasses} 个类里配上 ${m.matched} 个（同名 ${m.sameName}、稀有字符串 ${m.rareString}、传播 ${m.propagated}、亲属 ${m.relative}、结构 ${m.structure}），方法 ${m.methods} 对 |")
        if (!code.generated.empty) {
            val g = code.generated
            appendLine("| 编译器 / R8 生成的名字 | 新增 ${g.added}、删除 ${g.removed}，其中 ${g.renumbered} 对只是重新编号（R8 生成的 ${g.r8} 个）；不算功能改动，下面的列表里已去掉 |")
        }
        symbols?.counts?.let { c ->
            appendLine("| 模块的符号 | 位置不变 ${c[SymbolDiff.Status.SAME] ?: 0}，改名但跟上了 ${c[SymbolDiff.Status.MOVED] ?: 0}，**新版找不到 ${c[SymbolDiff.Status.LOST] ?: 0}**，新增 ${c[SymbolDiff.Status.GAINED] ?: 0} |")
        }
        appendLine()

        symbols?.let { s ->
            val lost = s.rows.filter { it.status == SymbolDiff.Status.LOST }
            if (lost.isNotEmpty()) {
                appendLine("## 模块：新版找不到的符号")
                appendLine()
                appendLine("| 符号 | 旧版位置 | 失败原因 | 按映射推测的新位置 |")
                appendLine("|---|---|---|---|")
                lost.forEach { r -> appendLine("| `${r.id}` | `${r.old}` | ${cell(r.reason)} | ${r.guess?.let { "`$it`" } ?: "—"} |") }
                appendLine()
            }
        }

        symbols?.let { s ->
            val changed = s.rows.filter { (it.bodySimilarity ?: 1.0) < BODY_WARN }.sortedBy { it.bodySimilarity }
            if (changed.isNotEmpty()) {
                appendLine("## 模块：找到了，但方法体变化大（复核 Hook 的写法）")
                appendLine()
                appendLine("| 符号 | 新版位置 | 方法体相似度 |")
                appendLine("|---|---|---|")
                changed.forEach { r -> appendLine("| `${r.id}` | `${r.new}` | ${"%.0f%%".format((r.bodySimilarity ?: 0.0) * 100)} |") }
                appendLine()
            }
            val risky = s.rows.filter { it.hookRisk != null }
            if (risky.isNotEmpty()) {
                appendLine("## 模块：Hook 可能不生效的点")
                appendLine()
                appendLine("| 符号 | 新版位置 | 风险 |")
                appendLine("|---|---|---|")
                risky.forEach { r -> appendLine("| `${r.id}` | `${r.new}` | ${r.hookRisk} |") }
                appendLine()
            }
        }

        section("清单：申请的权限", pkg.usesPermissions, limit)
        section("清单：自定义权限", pkg.permissions, limit)
        section("清单：组件（Activity / Service / Receiver / Provider）", pkg.components, limit)
        section("清单：外部唤起的链接", pkg.deepLinks, limit)
        section("清单：meta-data", pkg.metaData, limit)
        section("原生库", pkg.nativeLibs, limit)
        section("另外加载的 dex（插件、热修复、加固）", pkg.extraDex, limit)
        section("开源库", libraries, limit)
        section("原生库的导出符号和依赖", nativeSymbols, limit)
        section("网络接口（Retrofit）", code.endpoints, limit)
        section("网址与接口路径字符串", code.urls, limit)
        section("没混淆的类", code.stableClasses, limit)
        section("没混淆的类的成员", code.stableMembers, limit)
        if (code.packages.isNotEmpty()) {
            appendLine("## 没混淆的包（类数变化）")
            appendLine()
            appendLine("| 包 | 旧 → 新 |")
            appendLine("|---|---|")
            code.packages.entries.take(limit).forEach { (k, v) -> appendLine("| `$k` | $v |") }
            if (code.packages.size > limit) appendLine("| … | 共 ${code.packages.size} 个包 |")
            appendLine()
        }
        section("其它新增 / 删除的字符串（日志、埋点、配置键）", code.strings, limit)
    }

    /** 同样的内容写成可交互的 HTML（自包含）：模块符号表可以搜索、按状态筛选、排序，各项差异折叠成列表。 */
    fun html(): String {
        val h = io.github.wzgrx.dexadapt.report.Html
        val m = code.mapping
        val body = buildString {
            val cards = mutableListOf(
                "${m.matched} / ${m.oldClasses}" to "旧版的类配上新版",
                "${m.methods}" to "方法配对",
                "${code.classes.first} → ${code.classes.second}" to "类",
                (if (pkg.sameSigner) "相同" else "不同") to "签名证书",
            )
            symbols?.counts?.let { c ->
                cards += "${c[SymbolDiff.Status.SAME] ?: 0}" to "模块符号：位置不变"
                cards += "${c[SymbolDiff.Status.MOVED] ?: 0}" to "改名但跟上了"
                cards += "${c[SymbolDiff.Status.LOST] ?: 0}" to "新版找不到"
            }
            append(h.cards(cards))
            append("<h2>概要</h2>")
            append(
                h.table(
                    listOf("项", "旧 → 新"),
                    (pkg.fields.entries.map { (k, v) -> k to v } + listOf(
                        "dex" to "${pkg.dexCount.first} → ${pkg.dexCount.second} 个，${mb(pkg.dexBytes.first)} → ${mb(pkg.dexBytes.second)}",
                        "类 / 方法 / 字段" to "${code.classes.first} → ${code.classes.second} / ${code.methods.first} → ${code.methods.second} / ${code.fields.first} → ${code.fields.second}",
                        "混淆映射" to "同名 ${m.sameName}、稀有字符串 ${m.rareString}、传播 ${m.propagated}、亲属 ${m.relative}、结构 ${m.structure}",
                    ) + listOfNotNull(
                        code.generated.takeIf { !it.empty }?.let { g ->
                            "编译器 / R8 生成的名字" to "新增 ${g.added}、删除 ${g.removed}，其中 ${g.renumbered} 对只是重新编号（R8 ${g.r8} 个）；不算功能改动，下面已去掉"
                        },
                    )).map { (k, v) -> io.github.wzgrx.dexadapt.report.Html.Row(listOf(h.td(k), h.td(v))) },
                ),
            )
            symbols?.let { sd ->
                append("<h2>模块的符号</h2>")
                append("<p class=\"muted\">黄色：方法体相似度低于 ${(BODY_WARN * 100).toInt()}%（找到了，但逻辑可能变了，复核 Hook 的写法）；红色：新版找不到，最后一列是按混淆映射推测的新位置。</p>")
                val label = mapOf(
                    SymbolDiff.Status.SAME to "不变", SymbolDiff.Status.MOVED to "改名跟上", SymbolDiff.Status.LOST to "找不到",
                    SymbolDiff.Status.GAINED to "新增", SymbolDiff.Status.ABSENT to "都没有",
                )
                val rows = sd.rows.map { r ->
                    val sim = r.bodySimilarity
                    val kinds = listOfNotNull(
                        r.status.name.lowercase(), "risk".takeIf { r.hookRisk != null }, "body".takeIf { sim != null && sim < BODY_WARN },
                    )
                    val statusCls = when (r.status) {
                        SymbolDiff.Status.LOST -> "bad"; SymbolDiff.Status.MOVED -> "warn"; SymbolDiff.Status.SAME -> "ok"; else -> "na"
                    }
                    io.github.wzgrx.dexadapt.report.Html.Row(
                        listOf(
                            h.td(h.code(r.id), "id", raw = true, sort = r.id),
                            h.td(label.getValue(r.status), "cell $statusCls"),
                            h.td(r.old?.let(h::code) ?: "—", raw = true),
                            h.td(r.new?.let(h::code) ?: (r.reason ?: "—"), raw = r.new != null),
                            h.td(sim?.let { "%.0f%%".format(it * 100) } ?: "—", if (sim != null && sim < BODY_WARN) "cell warn" else "cell", sort = sim?.toString() ?: "2"),
                            h.td(r.hookRisk ?: ""),
                            h.td(r.guess?.let(h::code) ?: "", raw = true),
                        ),
                        kinds,
                    )
                }
                append(
                    h.table(
                        listOf("符号", "状态", "旧位置", "新位置 / 原因", "方法体相似度", "Hook 风险", "推测的新位置"), rows,
                        chips = listOf("lost" to "找不到", "moved" to "改名跟上", "body" to "方法体变化大", "risk" to "Hook 风险", "same" to "不变"),
                        placeholder = "搜索符号、类名",
                    ),
                )
            }
            val sections = listOf(
                "开源库" to libraries, "原生库" to pkg.nativeLibs, "原生库的导出符号和依赖" to nativeSymbols, "清单：申请的权限" to pkg.usesPermissions, "清单：自定义权限" to pkg.permissions,
                "清单：组件" to pkg.components, "清单：外部唤起的链接" to pkg.deepLinks, "清单：meta-data" to pkg.metaData,
                "另外加载的 dex" to pkg.extraDex, "网络接口（Retrofit）" to code.endpoints, "网址与接口路径字符串" to code.urls,
                "没混淆的类" to code.stableClasses, "没混淆的类的成员" to code.stableMembers, "其它新增 / 删除的字符串" to code.strings,
            ).filter { !it.second.empty }
            if (sections.isNotEmpty()) append("<h2>其它变化</h2>")
            for ((title, d) in sections) {
                append("<details><summary>").append(h.esc(title)).append("（新增 ${d.added.size}、删除 ${d.removed.size}、变化 ${d.changed.size}）</summary>")
                fun list(name: String, items: List<String>) {
                    if (items.isEmpty()) return
                    append("<p class=\"muted\">").append(name).append(" ${items.size}</p><ul>")
                    items.take(500).forEach { append("<li>").append(h.code(it)).append("</li>") }
                    if (items.size > 500) append("<li class=\"muted\">…还有 ${items.size - 500} 个（完整列表见 report.json）</li>")
                    append("</ul>")
                }
                list("新增", d.added); list("删除", d.removed); list("变化", d.changed)
                append("</details>")
            }
            if (code.packages.isNotEmpty()) {
                append("<h2>没混淆的包（类数变化）</h2>")
                append(h.table(listOf("包", "旧 → 新"), code.packages.entries.map { (k, v) -> io.github.wzgrx.dexadapt.report.Html.Row(listOf(h.td(h.code(k), raw = true), h.td(v))) }, placeholder = "搜索包名"))
            }
        }
        return h.page("升级分析：${pkg.old} → ${pkg.new}", "DexAdapt diff 生成", body)
    }

    private fun StringBuilder.section(title: String, d: Delta, limit: Int) {
        if (d.empty) return
        appendLine("## $title")
        appendLine()
        if (d.added.isNotEmpty()) { appendLine("新增 ${d.added.size}："); d.added.take(limit).forEach { appendLine("- `$it`") }; more(d.added.size, limit) }
        if (d.removed.isNotEmpty()) { appendLine("删除 ${d.removed.size}："); d.removed.take(limit).forEach { appendLine("- `$it`") }; more(d.removed.size, limit) }
        if (d.changed.isNotEmpty()) { appendLine("变化 ${d.changed.size}："); d.changed.take(limit).forEach { appendLine("- $it") }; more(d.changed.size, limit) }
        appendLine()
    }

    private fun StringBuilder.more(n: Int, limit: Int) { if (n > limit) appendLine("- …还有 ${n - limit} 个（完整列表见 JSON）") }

    private fun delta(d: Delta, n: Int) = (d.added.take(n).map { "+$it" } + d.removed.take(n).map { "−$it" }).joinToString("，")

    private fun mb(bytes: Long) = "%.1f MB".format(bytes / 1048576.0)

    private fun cell(s: String?) = s?.replace("|", "\\|")?.take(160) ?: "—"
}
