package io.github.wzgrx.dexadapt

import java.io.File
import java.security.MessageDigest

/** 安装集合的快速身份用于失效摘要缓存；真正代码身份使用DexDigest，二者不混用。 */
object HostIdentity {
    fun installation(packageName: String, base: File, splits: List<File>, lastUpdateTime: Long): String {
        require(packageName.isNotBlank()) { "宿主包名为空" }
        val text = buildString {
            append("installed-set-v2\n").append(packageName).append('\n').append(lastUpdateTime).append('\n')
            (listOf(base) + splits).forEach { f ->
                val path = f.absolutePath
                append(path.length).append(':').append(path).append(':').append(f.length()).append(':').append(f.lastModified()).append('\n')
            }
        }
        return "v2:" + Hex.of(MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)))
    }
}
