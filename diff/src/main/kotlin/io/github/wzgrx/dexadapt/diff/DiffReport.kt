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
