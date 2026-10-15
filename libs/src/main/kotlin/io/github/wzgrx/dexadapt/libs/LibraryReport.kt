package io.github.wzgrx.dexadapt.libs

/** 开源库识别结果写成报告和 jadx 能加载的映射。 */
object LibraryReport {
    fun markdown(title: String, found: List<LibraryScanner.Detection>, limit: Int = 40): String = buildString {
        appendLine("# 开源库：$title")
        appendLine()
        if (found.isEmpty()) {
            appendLine("指纹库里的库一个都没认出来。")
            return@buildString
        }
        appendLine("| 库 | 版本 | 可信度 | 特征字符串 | 配上的类 | 包名 |")
        appendLine("|---|---|---|---|---|---|")
        for (d in found) {
            val names = when {
                d.classes.isEmpty() -> "—"
                d.namesKept == d.classes.size -> "没混淆"
                d.namesKept == 0 -> "全部改名"
                else -> "部分改名（${d.classes.size - d.namesKept} / ${d.classes.size}）"
            }
            appendLine(
                "| ${d.name}（`${d.id}`） | ${d.versionText} | ${d.confidence} | ${d.hits} / ${d.total}（%.0f%%）%s | ${d.classes.size} | $names |"
                    .format(d.recall * 100, if (d.against > 0) "，${d.against} 个只在别的版本有" else ""),
            )
        }
        appendLine()
        appendLine("版本按字符串判断：分数并列时给出一段版本（这几个版本的字符串完全相同）。可信度看特征字符串的命中比例，")
        appendLine("R8 删掉没用到的代码后比例会下降，“低”也可能是真的，只是 App 只用了库的一小部分。")
        for (d in found.filter { it.packages.isNotEmpty() }) {
            appendLine()
            appendLine("## ${d.name}：包被改到了哪里")
            appendLine()
            d.packages.entries.take(limit).forEach { (k, v) -> appendLine("- `$k` → `${v.ifEmpty { "（默认包）" }}`") }
            if (d.packages.size > limit) appendLine("- …共 ${d.packages.size} 个")
        }
    }

    /**
     * ProGuard 格式：库里的类名 → APK 里的类名。jadx 打开 APK 时加载（“加载映射”），混淆的库类显示回原名。
     * 名字没变的不写；同一个 APK 类被几个库类配上（R8 合并了类）时只写票数最高的。
     */
    fun proguard(found: List<LibraryScanner.Detection>): String = buildString {
        val byApk = found.flatMap { it.classes }.filter { it.library != it.apk }.groupBy { it.apk }.mapValues { (_, ms) -> ms.maxBy { it.votes } }
        for (m in byApk.values.sortedBy { it.library }) appendLine("${m.library} -> ${m.apk}:")
    }
}
