package io.github.wzgrx.dexadapt

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/** 一个宿主构建的解析结果（符号表、名字表）：电脑上生成后随模块打包，或手机上本机适配现场生成。 */
@Serializable
data class SymbolTable(
    val versionCode: Long,
    /** SHA-256 of the concatenated classes*.dex the table was generated from. */
    val dexSha256: String,
    val entries: Map<String, Resolved>,
    /** Symbols that could not be resolved, with the reason; their features stay off. */
    val missing: Map<String, String> = emptyMap(),
    // ---- 以下只有本机适配生成的表才有（内置表不写，JSON 保持原样） ----
    /** 生成时的符号定义指纹（[Catalog.fingerprint]）；模块更新改了查询后，本机表要重扫。 */
    val catalog: String = "",
    /** 生成方式：`dexkit`（手机上本机适配）。 */
    val generator: String = "",
    val generatedAt: Long = 0,
    /** 生成它的模块版本。 */
    val module: String = "",
    /** 宿主安装包身份（路径:大小:更新时间），变了就要重新核对。 */
    val apk: String = "",
    /** 解析用时（秒）和触发原因，给“版本适配”页和扫描报告看。 */
    val seconds: Long = 0,
    val reason: String = "",
) {
    fun toJson(): String = json.encodeToString(serializer(), this)

    companion object {
        private val json = Json { prettyPrint = true; prettyPrintIndent = "  "; encodeDefaults = false; classDiscriminator = "kind" }

        private val reader = Json { ignoreUnknownKeys = true; classDiscriminator = "kind" }

        /** 认识的条目种类（[Resolved] 各子类的 SerialName）。 */
        private val KINDS = setOf("class", "method", "field")

        /**
         * 读表。条目的 `kind` 认不出（新版模块写的表、旧版读）或格式坏了时，只把这一条挪进 [missing]（R11），
         * 不让整张表解析失败、所有功能都显示“未适配”。
         */
        fun fromJson(text: String): SymbolTable {
            val root = reader.parseToJsonElement(text).jsonObject
            val entries = root["entries"] as? JsonObject ?: return reader.decodeFromJsonElement(serializer(), root)
            val bad = LinkedHashMap<String, String>()
            val good = buildJsonObject {
                for ((id, e) in entries) {
                    val kind = ((e as? JsonObject)?.get("kind") as? JsonPrimitive)?.contentOrNull
                    val ok = kind in KINDS && runCatching { reader.decodeFromJsonElement(Resolved.serializer(), e) }.isSuccess
                    if (ok) put(id, e) else bad[id] = "unreadable table entry (kind=$kind)"
                }
            }
            val table = reader.decodeFromJsonElement(serializer(), JsonObject(root + ("entries" to good)))
            return if (bad.isEmpty()) table else table.copy(missing = table.missing + bad)
        }

        /** Resource path inside the module APK / core:symbols jar. */
        fun resourcePath(versionCode: Long) = "symbols/$versionCode.json"

        /** 本机适配生成的表在 LSPosed 远程文件里的名字（模块 App 写，宿主进程读）。 */
        fun remoteFileName(versionCode: Long) = "symbols-$versionCode.json"

        /** 安装包身份（[SymbolTable.apk]）：路径、大小、更新时间。生成本机表时记下，宿主进程据此认表是不是对着自己生成的。 */
        fun apkIdentity(path: String, size: Long, lastUpdateTime: Long) = "$path:$size:$lastUpdateTime"
    }
}

@Serializable
sealed interface Resolved {
    @Serializable
    @SerialName("class")
    data class Class(val name: String) : Resolved

    /** [params] and [returns] use JVM names (`int`, `java.lang.String`, `a.b.C[]`). */
    @Serializable
    @SerialName("method")
    data class Method(val owner: String, val name: String, val params: List<String>, val returns: String) : Resolved {
        val isConstructor: Boolean get() = name == "<init>"
    }

    @Serializable
    @SerialName("field")
    data class Field(val owner: String, val name: String, val type: String) : Resolved
}
