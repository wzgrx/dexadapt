package io.github.wzgrx.dexadapt.runtime

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** 身份数据不是签名；调用方必须从已认证的模块私有通道读取。不可接收来自宿主声称或公共存储的数据。 */
@Serializable
data class VerifiedDigestTicket(
    val schema: Int = 1,
    val packageName: String,
    val versionCode: Long,
    val installation: String,
    val dexSha256: String,
) {
    fun toJson(): String = Json.encodeToString(this)
    fun matches(pkg: String, version: Long, stamp: String): Boolean =
        schema == 1 && packageName == pkg && versionCode == version && installation == stamp &&
            installation.matches(HEX) && dexSha256.matches(HEX)
    companion object {
        private val HEX = Regex("[a-f0-9]{64}")
        fun fileName(stamp: String): String { require(stamp.matches(HEX)); return "identity-ticket-v1-$stamp.json" }
        fun pendingName(stamp: String): String { require(stamp.matches(HEX)); return "identity-pending-v1-$stamp.json" }
        fun parse(raw: String): VerifiedDigestTicket? =
            raw.takeIf { it.length <= 2048 }?.let { runCatching { Json.decodeFromString<VerifiedDigestTicket>(it) }.getOrNull() }
    }
}


/** 只用于合并同一快照的在途读取；到期或票据不匹配时回退完整校验，不把租约当作内容身份。 */
@Serializable
data class DigestReadLease(val installation: String, val expiresNanos: Long) {
    fun toJson(): String = Json.encodeToString(this)
    companion object {
        fun parse(raw: String): DigestReadLease? = raw.takeIf { it.length <= 1024 }
            ?.let { runCatching { Json.decodeFromString<DigestReadLease>(it) }.getOrNull() }
    }
}
