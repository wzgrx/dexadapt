package io.github.wzgrx.dexadapt.runtime

import io.github.wzgrx.dexadapt.SymbolTable
import io.github.wzgrx.dexadapt.TableSignature
import java.io.IOException
import java.net.HttpURLConnection

/**
 * 共享适配表（设计 10.6）：同一个宿主版本，所有手机解析出来都一样。开发者（CI）对新版本在电脑上生成表、
 * 做完全部校验后签名发布（例如 GitHub Releases 的附件），模块先按“版本号 + dex 摘要”下载，比手机上自己扫更快；
 * 下载不到、签名不对、和这一版模块的查询对不上时再本机适配。
 *
 * 请求里只有文件名（版本号和 dex 摘要），不带任何用户数据。
 */
object SharedTables {
    /** 共享表的生成方式（[SymbolTable.generator]）：CI 在电脑上生成、验证过，宿主进程不用观察。 */
    const val GENERATOR = "ci"

    /** 表文件名：`<versionCode>-<dex 摘要前 16 位>.json`，签名在同名 `.sig`。 */
    fun fileName(versionCode: Long, dexSha256: String) = "$versionCode-${dexSha256.take(16)}.json"

    sealed interface Result {
        data class Found(val table: SymbolTable, val text: String) : Result
        /** 没有这张表（还没发布）。 */
        data object Absent : Result
        /** 有，但不能用（签名不对、摘要或查询对不上、下载失败）。 */
        data class Rejected(val reason: String) : Result
    }

    /** 取文件：返回内容，没有（404）返回 null，网络错误抛 IOException。默认 HTTP GET。 */
    fun interface Fetcher {
        fun fetch(url: String): ByteArray?
    }

    fun http(timeoutMs: Int = 15_000, maxBytes: Int = 8 shl 20): Fetcher = Fetcher { url ->
        val c = java.net.URI(url).toURL().openConnection() as HttpURLConnection
        try {
            c.connectTimeout = timeoutMs
            c.readTimeout = timeoutMs
            c.instanceFollowRedirects = true
            c.setRequestProperty("User-Agent", "DexAdapt")
            when (c.responseCode) {
                404 -> null
                in 200..299 -> c.inputStream.use { input ->
                    val out = java.io.ByteArrayOutputStream()
                    val buf = ByteArray(16 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        if (out.size() > maxBytes) throw IOException("表太大（超过 $maxBytes 字节）")
                    }
                    out.toByteArray()
                }
                else -> throw IOException("HTTP ${c.responseCode}")
            }
        } finally {
            c.disconnect()
        }
    }

    /**
     * 从 [baseUrl]（结尾不带 /）取 [versionCode] + [dexSha256] 的表，核对：签名是 [publicKey] 的、表里的版本号和 dex 摘要
     * 就是这个安装包的、查询目录指纹是 [catalog]（这一版模块的）。
     */
    fun fetch(baseUrl: String, versionCode: Long, dexSha256: String, catalog: String, publicKey: String, fetcher: Fetcher = http()): Result {
        val name = fileName(versionCode, dexSha256)
        val body = try {
            fetcher.fetch("$baseUrl/$name") ?: return Result.Absent
        } catch (e: IOException) {
            return Result.Rejected("下载失败：${e.message}")
        }
        val sig = try {
            fetcher.fetch("$baseUrl/$name.sig") ?: return Result.Rejected("没有签名文件")
        } catch (e: IOException) {
            return Result.Rejected("签名下载失败：${e.message}")
        }
        return check(body, String(sig, Charsets.UTF_8), versionCode, dexSha256, catalog, publicKey)
    }

    /** 核对一张下载来的表（[fetch] 用；也给离线导入用）。 */
    fun check(body: ByteArray, signature: String, versionCode: Long, dexSha256: String, catalog: String, publicKey: String): Result {
        if (!TableSignature.verify(body, signature, publicKey)) return Result.Rejected("签名不对")
        val text = String(body, Charsets.UTF_8)
        val table = runCatching { SymbolTable.fromJson(text) }.getOrElse { return Result.Rejected("表格式不对：${it.message}") }
        return when {
            table.versionCode != versionCode -> Result.Rejected("版本号对不上（表是 ${table.versionCode}）")
            !table.dexSha256.equals(dexSha256, ignoreCase = true) -> Result.Rejected("安装包代码对不上（表是 ${table.dexSha256.take(16)}）")
            table.catalog != catalog -> Result.Rejected("表是给另一版模块的查询生成的")
            table.generator != GENERATOR -> Result.Rejected("不是 CI 生成的表")
            else -> Result.Found(table, text)
        }
    }

    /** 电脑上（CI）把解析好的表盖上共享表的身份。 */
    fun stamp(table: SymbolTable, catalog: String, module: String, now: Long = System.currentTimeMillis()) =
        table.copy(catalog = catalog, generator = GENERATOR, generatedAt = now, module = module, apk = "", seconds = 0, reason = "CI 生成的共享适配表")
}
