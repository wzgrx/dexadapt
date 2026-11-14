package io.github.wzgrx.dexadapt.runtime

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** 已认证通道上的取表提示；收到通知仍必须重新读取并核对完整身份和目录。 */
@Serializable
data class StageNotice(val server: String, val sequence: Long, val packageName: String,
                       val versionCode: Long, val dexSha256: String, val catalog: String,
                       val partial: Boolean, val publishedNanos: Long) {
    fun toJson(): String = Json.encodeToString(this)
    companion object {
        fun parse(raw: String): StageNotice? = raw.takeIf { it.length <= 4096 }
            ?.let { runCatching { Json.decodeFromString<StageNotice>(it) }.getOrNull() }
    }
}

class StageNoticeGate(private val identity: HostCodeIdentity, private val catalog: String) {
    private var server: String? = null
    private var sequence = 0L
    @Synchronized fun subscribe(epoch: String) { require(epoch.isNotBlank()); if (server != epoch) { server = epoch; sequence = 0 } }
    @Synchronized fun accept(notice: StageNotice): Boolean {
        if (notice.server != server || notice.sequence <= sequence ||
            notice.packageName != identity.packageName || notice.versionCode != identity.versionCode ||
            notice.dexSha256 != identity.dexSha256 || notice.catalog != catalog) return false
        sequence = notice.sequence
        return true
    }
}

@Serializable
data class StageRegistration(val packageName: String, val versionCode: Long, val dexSha256: String, val catalog: String) {
    fun toJson(): String = Json.encodeToString(this)
    companion object {
        fun parse(raw: String): StageRegistration? = raw.takeIf { it.length <= 4096 }
            ?.let { runCatching { Json.decodeFromString<StageRegistration>(it) }.getOrNull() }
            ?.takeIf { it.packageName.isNotBlank() && it.versionCode > 0 &&
                it.dexSha256.matches(Regex("[a-f0-9]{64}")) && it.catalog.isNotBlank() }
    }
}
