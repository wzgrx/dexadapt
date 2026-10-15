package io.github.wzgrx.dexadapt.libs

import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * 从 Maven 仓库取开源库的官方构件（建指纹库用）。只发构件坐标，不带任何 App 或用户的信息；下载的文件缓存在 [cache]。
 */
class Maven(
    private val cache: File = File(System.getProperty("user.home"), ".cache/dexadapt/maven"),
    private val repos: List<String> = listOf("https://repo1.maven.org/maven2", "https://dl.google.com/dl/android/maven2"),
) {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).followRedirects(HttpClient.Redirect.NORMAL).build()

    /** 已发布的版本（按仓库元数据的顺序，旧的在前）。 */
    fun versions(group: String, artifact: String): List<String> {
        for (repo in repos) {
            val text = get("$repo/${group.replace('.', '/')}/$artifact/maven-metadata.xml")?.toString(Charsets.UTF_8) ?: continue
            return VERSION.findAll(text).map { it.groupValues[1] }.toList()
        }
        error("$group:$artifact 在 Maven 仓库里找不到")
    }

    /** 下载（或从缓存取）一个版本的 jar，没有 jar 时取 aar。 */
    fun artifact(group: String, artifact: String, version: String): File {
        val dir = File(cache, "$group/$artifact/$version")
        listOf("jar", "aar").map { File(dir, "$artifact-$version.$it") }.firstOrNull { it.isFile }?.let { return it }
        for (ext in listOf("jar", "aar")) for (repo in repos) {
            val bytes = get("$repo/${group.replace('.', '/')}/$artifact/$version/$artifact-$version.$ext") ?: continue
            dir.mkdirs()
            val f = File(dir, "$artifact-$version.$ext")
            val tmp = File(dir, f.name + ".part")
            tmp.writeBytes(bytes)
            tmp.renameTo(f)
            return f
        }
        error("$group:$artifact:$version 没有 jar 或 aar")
    }

    private fun get(url: String): ByteArray? {
        val req = HttpRequest.newBuilder(URI(url)).timeout(Duration.ofMinutes(2)).GET().build()
        val res = runCatching { http.send(req, HttpResponse.BodyHandlers.ofByteArray()) }.getOrNull() ?: return null
        return res.body().takeIf { res.statusCode() == 200 }
    }

    companion object {
        private val VERSION = Regex("<version>([^<]+)</version>")
        private val STABLE = Regex("\\d+(\\.\\d+)*")

        /** 正式版（不要 alpha、beta、rc、快照）。 */
        fun stable(v: String) = STABLE.matches(v)
    }

    /** 版本号按数字逐段比较（`3.14.9` < `4.0.0`）。 */
    object VersionOrder : Comparator<String> {
        override fun compare(a: String, b: String): Int {
            val x = a.split('.').map { it.toIntOrNull() ?: 0 }
            val y = b.split('.').map { it.toIntOrNull() ?: 0 }
            for (i in 0 until maxOf(x.size, y.size)) {
                val c = (x.getOrElse(i) { 0 }).compareTo(y.getOrElse(i) { 0 })
                if (c != 0) return c
            }
            return 0
        }
    }
}
