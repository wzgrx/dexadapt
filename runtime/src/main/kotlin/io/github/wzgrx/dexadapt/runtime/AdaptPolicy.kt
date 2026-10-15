package io.github.wzgrx.dexadapt.runtime

import io.github.wzgrx.dexadapt.SymbolTable

/**
 * 三层适配的决策（纯函数，模块 App 和宿主进程各用一半）：内置表 → 本机适配的表 → 降级。
 *
 * 本机表的“身份”：[SymbolTable.catalog]（生成时的目录指纹，模块更新改了查询就变）和 [SymbolTable.apk]
 * （生成时对着的宿主安装包，同版本号换了灰度包 / 渠道包就变）。
 */
object AdaptPolicy {
    /** 宿主进程选表的结果。 */
    data class Choice(val table: SymbolTable?, val source: Source, val needsAdaptation: Boolean) {
        /**
         * 这张表要不要先观察（[ContractMonitor]）：本机 DexKit 适配出的表没在电脑上验证过，带契约的 Hook 点先观察；
         * 内置表在电脑上逐版本验证过，直接用。
         */
        val observe: Boolean get() = source == Source.LOCAL || source == Source.STALE_LOCAL
    }

    enum class Source { LOCAL, BUNDLED, STALE_LOCAL, NONE }

    /**
     * 宿主进程启动时用哪张表：
     * 1. 本机表，且是这一版目录、对着这个安装包生成的（同版本号但代码和内置表不一致时，App 生成它来覆盖内置表）；
     * 2. 内置表；
     * 3. 模块更新前生成、但确实对着这个安装包的本机表（查询旧了、位置还对），比没有强，同时请 App 重扫；
     * 4. 都没有：全部降级，请 App 本机适配。
     * 安装包对不上的本机表一律不用：宁可先降级、等重扫后热替换，也不拿别的包的位置去 Hook。
     * [apkIdentity] 读不到、或旧表没记安装包时不核对安装包。
     */
    fun choose(local: SymbolTable?, bundled: SymbolTable?, catalog: String, apkIdentity: String?): Choice {
        fun sameApk(t: SymbolTable) = apkIdentity == null || t.apk.isEmpty() || t.apk == apkIdentity
        fun current(t: SymbolTable) = t.catalog == catalog && sameApk(t)
        return when {
            local != null && current(local) -> Choice(local, Source.LOCAL, false)
            bundled != null -> Choice(bundled, Source.BUNDLED, false)
            local != null && sameApk(local) -> Choice(local, Source.STALE_LOCAL, true)
            else -> Choice(null, Source.NONE, true)
        }
    }

    /** 模块 App 侧要不要扫描。 */
    sealed interface Decision {
        /** 不用扫；[clearLocal] 为真时本机表已经多余（内置表核对一致），可以清掉。 */
        data class Skip(val clearLocal: Boolean) : Decision
        data class Scan(val reason: String) : Decision
    }

    /**
     * App 侧判断：有内置表时核对安装包的 dex 摘要（[dexDigest] 只在需要时才算，比较慢），一致就用内置表；
     * 不一致（灰度包、渠道包）或没有内置表时，看本机表是否还能用（目录指纹、安装包身份）。
     */
    fun decide(host: String, bundled: SymbolTable?, local: SymbolTable?, catalog: String, apkIdentity: String, dexDigest: () -> String?): Decision {
        val localOk = local != null && local.catalog == catalog && local.apk == apkIdentity
        if (bundled != null) {
            if (dexDigest() == bundled.dexSha256) return Decision.Skip(clearLocal = true)
            return if (localOk) Decision.Skip(false) else Decision.Scan("$host 的代码和内置适配不一致（可能是灰度或渠道包），用本机适配覆盖")
        }
        return when {
            local == null -> Decision.Scan("$host 没有内置适配")
            local.catalog != catalog -> Decision.Scan("模块更新后符号定义变了，重新适配")
            local.apk != apkIdentity -> Decision.Scan("宿主安装包变了，重新适配")
            else -> Decision.Skip(false)
        }
    }

    /** 本机生成的表盖上“身份”：目录指纹、生成方式、模块版本、安装包、用时、原因。 */
    fun stamp(table: SymbolTable, catalog: String, module: String, apkIdentity: String, seconds: Long, reason: String, now: Long = System.currentTimeMillis()) =
        table.copy(catalog = catalog, generator = "dexkit", generatedAt = now, module = module, apk = apkIdentity, seconds = seconds, reason = reason)

    /** 自检：内置表和本机重新解析的结果逐条比较。 */
    data class CheckResult(val same: Int, val different: List<String>, val onlyBundled: List<String>, val onlyLocal: List<String>, val seconds: Long)

    fun compare(bundled: SymbolTable, local: SymbolTable, seconds: Long): CheckResult {
        val ids = bundled.entries.keys + local.entries.keys
        val same = ids.count { bundled.entries[it] != null && bundled.entries[it] == local.entries[it] }
        val different = ids.filter { bundled.entries[it] != null && local.entries[it] != null && bundled.entries[it] != local.entries[it] }.sorted()
        val onlyBundled = bundled.entries.keys.filter { it !in local.entries }.sorted()
        val onlyLocal = local.entries.keys.filter { it !in bundled.entries }.sorted()
        return CheckResult(same, different, onlyBundled, onlyLocal, seconds)
    }
}
