package io.github.wzgrx.dexadapt.matrix

import io.github.wzgrx.dexadapt.Resolved
import io.github.wzgrx.dexadapt.SymbolTable
import io.github.wzgrx.dexadapt.resolve.SymbolMargin

/** 矩阵里的一个构建：[channel] 相同的按 [versionCode] 从旧到新比较漂移（例如“国内版”“Play 版”）。 */
data class Build(val id: String, val channel: String, val versionCode: Long, val versionName: String) {
    val label: String get() = "$versionName $channel"
}

/**
 * 版本矩阵报告：每个符号在各构建里解析到哪里，同一渠道前后两版之间哪些被混淆改了名（“漂移”，查询仍然跟上了），
 * 哪些查询的唯一性只靠一个条件（下个版本最容易失效）。纯函数，输出稳定（可以提交进仓库、比较是否过期）。
 * [channelOrder] 决定渠道的先后（没列的排在后面）。
 */
object Matrix {
    fun render(
        tables: Map<Build, SymbolTable>,
        margins: Map<Long, List<SymbolMargin>>,
        channelOrder: List<String> = emptyList(),
        regenerate: String = "",
    ): String = buildString {
        // 列：按渠道、再按版本从旧到新。
        fun rank(c: String) = channelOrder.indexOf(c).let { if (it < 0) Int.MAX_VALUE else it }
        val builds = tables.keys.sortedWith(compareBy<Build>({ rank(it.channel) }, { it.channel }).thenBy { it.versionCode })
        val ids = tables.values.flatMap { it.entries.keys + it.missing.keys }.toSortedSet()
        appendLine("# 版本矩阵")
        appendLine()
        if (regenerate.isNotEmpty()) {
            appendLine(regenerate)
            appendLine()
        }
        appendLine("## 概览")
        appendLine()
        appendLine("| 版本 | versionCode | 解析到 | 缺失 | 单点依赖 | 其中仅靠形状 |")
        appendLine("|---|---|---|---|---|---|")
        for (b in builds) {
            val t = tables.getValue(b)
            val m = margins[b.versionCode].orEmpty().filter { it.singlePoint }
            appendLine("| ${b.label} | ${b.versionCode} | ${t.entries.size} | ${t.missing.size} | ${m.size} | ${m.count { it.shapeOnly }} |")
        }
        appendLine()

        appendLine("## 漂移（同一渠道相邻两版）")
        appendLine()
        appendLine("混淆后的类名、方法名每版都可能变；查询按字符串、调用关系、方法形状等稳定特征描述，所以名字变了也能跟上。")
        appendLine("“变了”的越多，说明靠名字写死的方案越容易失效。")
        appendLine()
        val pairs = builds.map { it.channel }.distinct().flatMap { ch -> builds.filter { it.channel == ch }.zipWithNext() }
        appendLine("| 渠道 | 从 → 到 | 位置不变 | 变了 | 只有一边有 |")
        appendLine("|---|---|---|---|---|")
        val drifted = LinkedHashMap<Pair<Build, Build>, List<String>>()
        for ((a, b) in pairs) {
            val ta = tables.getValue(a).entries
            val tb = tables.getValue(b).entries
            val both = ta.keys.intersect(tb.keys)
            val changed = both.filter { ta[it] != tb[it] }.sorted()
            drifted[a to b] = changed
            appendLine("| ${a.channel} | ${a.versionName} → ${b.versionName} | ${both.size - changed.size} | ${changed.size} | ${(ta.keys + tb.keys).size - both.size} |")
        }
        appendLine()
        for ((pair, changed) in drifted) {
            if (changed.isEmpty()) continue
            val (a, b) = pair
            appendLine("<details><summary>${a.channel} ${a.versionName} → ${b.versionName}：${changed.size} 个符号位置变了</summary>")
            appendLine()
            appendLine("| 符号 | ${a.versionName} | ${b.versionName} |")
            appendLine("|---|---|---|")
            changed.forEach { id ->
                val from = cell(tables.getValue(a).entries[id])
                val to = cell(tables.getValue(b).entries[id])
                // 名字和参数个数都没变时，变的是参数或返回值的类型（类型本身被混淆改了名）。
                appendLine("| `$id` | $from | ${if (from == to) "$to（参数或返回类型改名）" else to} |")
            }
            appendLine()
            appendLine("</details>")
            appendLine()
        }

        appendLine("## 单点依赖（下个版本最容易失效的查询）")
        appendLine()
        appendLine("逐个去掉查询里的一个条件，候选不再唯一的，说明唯一性只靠这一个条件撑着。标 ⚠ 的靠的是纯形状条件")
        appendLine("（参数、返回值、父类等），最容易被新加的同形状成员打破，优先给它们加字符串、调用关系这类“锚”。")
        appendLine()
        val single = builds.flatMap { b -> margins[b.versionCode].orEmpty().filter { it.singlePoint }.map { it.id to b } }
            .groupBy({ it.first }, { it.second })
        if (single.isEmpty()) {
            appendLine("没有。")
        } else {
            appendLine("| 符号 | 出现在 | 关键条件 |")
            appendLine("|---|---|---|")
            for ((id, where) in single.toSortedMap()) {
                val m = builds.firstNotNullOf { b -> margins[b.versionCode]?.firstOrNull { it.id == id && it.singlePoint } }
                val mark = if (m.shapeOnly) "⚠ " else ""
                appendLine("| $mark`$id` | ${where.joinToString(" / ") { it.versionName }} | ${m.critical.joinToString(", ") { it.toString().replace("|", "\\|") }} |")
            }
        }
        appendLine()

        val missing = builds.flatMap { b -> tables.getValue(b).missing.map { (id, why) -> Triple(id, b, why) } }
        if (missing.isNotEmpty()) {
            appendLine("## 缺失")
            appendLine()
            appendLine("| 符号 | 版本 | 原因 |")
            appendLine("|---|---|---|")
            missing.sortedBy { it.first }.forEach { (id, b, why) -> appendLine("| `$id` | ${b.label} | ${why.replace("|", "\\|")} |") }
            appendLine()
        }
        appendLine("共 ${ids.size} 个符号。")
    }

    /**
     * 同样的数据画成热力图（HTML，自包含）：行是符号、列是构建；绿色和同渠道上一版位置相同，黄色是改了名（查询跟上了），
     * 红色是解析不到，灰色是这个构建没有；⚠ 是单点依赖。可以搜索、按“漂移 / 缺失 / 单点”筛选、点表头排序。
     */
    fun html(
        tables: Map<Build, SymbolTable>,
        margins: Map<Long, List<SymbolMargin>>,
        channelOrder: List<String> = emptyList(),
        title: String = "版本矩阵",
    ): String {
        fun rank(c: String) = channelOrder.indexOf(c).let { if (it < 0) Int.MAX_VALUE else it }
        val builds = tables.keys.sortedWith(compareBy<Build>({ rank(it.channel) }, { it.channel }).thenBy { it.versionCode })
        val ids = tables.values.flatMap { it.entries.keys + it.missing.keys }.toSortedSet()
        val prev = builds.associateWith { b -> builds.lastOrNull { it.channel == b.channel && it.versionCode < b.versionCode } }
        val single = builds.associate { b -> b.versionCode to margins[b.versionCode].orEmpty().filter { it.singlePoint }.associateBy { it.id } }
        var drifted = 0
        val rows = ids.map { id ->
            val kinds = LinkedHashSet<String>()
            val cells = builds.map { b ->
                val t = tables.getValue(b)
                val r = t.entries[id]
                val sp = single[b.versionCode]?.get(id)
                val mark = if (sp != null) (if (sp.shapeOnly) " ⚠" else " ·") else ""
                if (sp != null) kinds += "single"
                when {
                    id in t.missing -> { kinds += "missing"; io.github.wzgrx.dexadapt.report.Html.td("缺$mark", "cell bad", t.missing[id]) }
                    r == null -> io.github.wzgrx.dexadapt.report.Html.td("—", "cell na", "这个构建没有")
                    else -> {
                        val before = prev[b]?.let { tables.getValue(it).entries[id] }
                        val changed = before != null && before != r
                        if (changed) kinds += "drift"
                        val text = describe(r) + (if (changed) "\n上一版：" + describe(before) else "") + (sp?.let { "\n单点依赖：" + it.critical.joinToString() } ?: "")
                        io.github.wzgrx.dexadapt.report.Html.td((if (changed) "变" else "✓") + mark, if (changed) "cell warn" else "cell ok", text)
                    }
                }
            }
            if ("drift" in kinds) drifted++
            io.github.wzgrx.dexadapt.report.Html.Row(
                listOf(io.github.wzgrx.dexadapt.report.Html.td(io.github.wzgrx.dexadapt.report.Html.code(id), "id", raw = true, sort = id)) + cells,
                kinds.toList(),
            )
        }
        val missingTotal = builds.sumOf { tables.getValue(it).missing.size }
        val singleIds = single.values.flatMap { it.keys }.toSet()
        val body = buildString {
            append(
                io.github.wzgrx.dexadapt.report.Html.cards(
                    listOf(
                        "${ids.size}" to "符号",
                        "${builds.size}" to "构建",
                        "$drifted" to "有改名的符号（查询跟上了）",
                        "$missingTotal" to "解析不到（各构建合计）",
                        "${singleIds.size}" to "单点依赖的符号",
                    ),
                ),
            )
            append("<h2>热力图</h2>")
            append("<div class=\"legend\"><span><i class=\"ok\"></i>和上一版位置相同</span><span><i class=\"warn\"></i>改了名，查询跟上了</span>")
            append("<span><i class=\"bad\"></i>解析不到</span><span><i class=\"na\"></i>这个构建没有</span><span>⚠ 单点依赖且只靠形状　· 单点依赖</span><span>鼠标停在格子上看位置</span></div>")
            append(
                io.github.wzgrx.dexadapt.report.Html.table(
                    listOf("符号") + builds.map { it.label }, rows,
                    chips = listOf("drift" to "有改名", "missing" to "有缺失", "single" to "单点依赖"),
                    placeholder = "搜索符号 id",
                ),
            )
        }
        return io.github.wzgrx.dexadapt.report.Html.page(title, builds.joinToString("、") { "${it.label}（${it.versionCode}）" }, body)
    }

    private fun describe(r: Resolved): String = when (r) {
        is Resolved.Class -> r.name
        is Resolved.Method -> "${r.owner}#${r.name}(${r.params.joinToString(",")})${r.returns}"
        is Resolved.Field -> "${r.owner}#${r.name}:${r.type}"
    }

    private fun cell(r: Resolved?): String = when (r) {
        null -> "—"
        is Resolved.Class -> "`${r.name}`"
        is Resolved.Method -> "`${r.owner}#${r.name}(${r.params.size})`"
        is Resolved.Field -> "`${r.owner}.${r.name}`"
    }
}
