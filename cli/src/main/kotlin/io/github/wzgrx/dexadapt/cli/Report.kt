package io.github.wzgrx.dexadapt.cli

import io.github.wzgrx.dexadapt.Resolved
import io.github.wzgrx.dexadapt.SymbolTable
import io.github.wzgrx.dexadapt.apk.ApkInfo
import kotlinx.serialization.Serializable

/** 扁平的名字表：符号 id → 位置（类名 / `类#方法(参数)返回` / `类#字段:类型`），给按名字表工作的模块用。 */
@Serializable
data class NameTable(val versionCode: Long, val dexSha256: String, val names: Map<String, String>, val missing: List<String>) {
    companion object {
        fun of(t: SymbolTable) = NameTable(
            t.versionCode, t.dexSha256,
            t.entries.mapValues { (_, r) ->
                when (r) {
                    is Resolved.Class -> r.name
                    is Resolved.Method -> "${r.owner}#${r.name}(${r.params.joinToString(",")})${r.returns}"
                    is Resolved.Field -> "${r.owner}#${r.name}:${r.type}"
                }
            }.toSortedMap(),
            t.missing.keys.sorted(),
        )
    }
}

object Report {
    fun size(bytes: Long): String = when {
        bytes >= 1L shl 20 -> "%.1f MB".format(bytes / 1048576.0)
        bytes >= 1L shl 10 -> "%.1f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }

    /** 安装包档案的 Markdown。 */
    fun inspect(i: ApkInfo): String = buildString {
        appendLine("# ${i.label}")
        appendLine()
        appendLine("| 项 | 值 |")
        appendLine("|---|---|")
        appendLine("| 文件 | ${i.files.joinToString { "${it.name}（${size(it.size)}）" }} |")
        appendLine("| SDK | min ${i.minSdk ?: "?"} / target ${i.targetSdk ?: "?"} / compile ${i.compileSdk ?: "?"} |")
        appendLine("| 签名 | ${if (i.verified) "校验通过" else "**校验失败**"}（${i.signatureSchemes.joinToString("、")}） |")
        i.signers.forEach { appendLine("| 证书 | ${it.subject}，SHA-256 `${it.sha256}` |") }
        appendLine("| 签名文件 | ${i.signatureFiles.joinToString().ifEmpty { "无" }}${if (i.fromPlay) "（Google Play 下发）" else ""} |")
        appendLine("| dex | ${i.dex.size} 个，共 ${size(i.dex.sumOf { it.size })} |")
        if (i.extraDex.isNotEmpty()) appendLine("| 另外的 dex | ${i.extraDex.joinToString { "${it.name}（${size(it.size)}）" }}（运行时另外加载：插件、热修复或加固壳） |")
        appendLine("| 原生库 | ${i.abis.joinToString("、").ifEmpty { "无" }}：${i.nativeLibs.size} 个，共 ${size(i.nativeLibs.sumOf { it.size })} |")
        appendLine("| 其它文件 | ${i.otherFiles} 个，共 ${size(i.otherBytes)} |")
        val m = i.manifest
        appendLine("| 权限 | 申请 ${m.usesPermissions.size} 个，自定义 ${m.permissions.size} 个 |")
        val byKind = m.components.groupingBy { it.kind }.eachCount()
        appendLine("| 组件 | ${byKind.entries.joinToString { "${it.key} ${it.value}" }}；导出的 ${m.components.count { it.exported == true }} 个 |")
        m.applicationClass?.let { appendLine("| Application | `$it` |") }
        if (m.debuggable) appendLine("| debuggable | **是** |")
        appendLine()
        val launchers = m.components.filter { "android.intent.action.MAIN" in it.actions && "android.intent.category.LAUNCHER" in it.categories }
        if (launchers.isNotEmpty()) appendLine("启动页：" + launchers.joinToString { "`${it.name}`" })
        val schemes = m.components.flatMap { c -> c.data.filter { "://" in it }.map { it.substringBefore("://") } }.distinct().sorted()
        if (schemes.isNotEmpty()) appendLine("\n外部可唤起的 scheme：" + schemes.joinToString("、"))
    }
}
