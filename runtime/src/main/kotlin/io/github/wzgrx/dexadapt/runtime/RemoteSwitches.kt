package io.github.wzgrx.dexadapt.runtime

import io.github.wzgrx.dexadapt.TableSignature
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 远程开关（设计 10.8）：已知宿主某个版本上某个功能会坏，开发者签名发一份“停用这个功能”的配置，不用等发新版模块。
 * 和共享适配表同一把密钥（[TableSignature]）；模块 App 和宿主进程都验签，签名不对整份不用。
 */
object RemoteSwitches {
    @Serializable
    data class Config(
        /** 签发时间（毫秒），新的覆盖旧的。 */
        val issued: Long,
        val rules: List<Rule>,
    )

    /**
     * 一条规则：宿主版本号在 [versionCodes] 里（空表示所有版本），并且（给了 [dexSha256] 时）代码摘要对得上，
     * 就停用 [features]；[until] 之后（毫秒，0 表示不过期）不再生效。
     */
    @Serializable
    data class Rule(
        val features: List<String>,
        val reason: String,
        val versionCodes: List<Long> = emptyList(),
        val dexSha256: String = "",
        val until: Long = 0,
    )

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; prettyPrintIndent = "  " }

    fun toJson(config: Config): String = json.encodeToString(Config.serializer(), config)

    /** 验签并读出配置；签名不对、格式不对返回 null。 */
    fun read(body: ByteArray, signature: String, publicKey: String): Config? {
        if (!TableSignature.verify(body, signature, publicKey)) return null
        return runCatching { json.decodeFromString(Config.serializer(), String(body, Charsets.UTF_8)) }.getOrNull()
    }

    /** 对这个宿主（版本号、代码摘要）停用的功能：功能 id → 原因。 */
    fun disabled(config: Config?, versionCode: Long, dexSha256: String?, now: Long = System.currentTimeMillis()): Map<String, String> {
        if (config == null) return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (r in config.rules) {
            if (r.until in 1 until now) continue
            if (r.versionCodes.isNotEmpty() && versionCode !in r.versionCodes) continue
            if (r.dexSha256.isNotEmpty() && (dexSha256 == null || !r.dexSha256.equals(dexSha256, ignoreCase = true))) continue
            r.features.forEach { out.putIfAbsent(it, r.reason) }
        }
        return out
    }
}
