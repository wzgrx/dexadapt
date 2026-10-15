package io.github.wzgrx.dexadapt.dexkit

import io.github.wzgrx.dexadapt.resolve.MultiSource
import io.github.wzgrx.dexadapt.resolve.SymbolSource
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.DexKitCacheBridge
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * DexKit 2.3 的 DexKitCacheBridge（实验性 API，只在这里用，API 变了只改这一个文件）：同一个安装包的桥在几次扫描之间复用
 * （自检完接着全量扫描、定时检查接着手动扫描），空闲一段时间后由 DexKit 自己释放，不用每次重新打开几百 MB 的 dex。
 *
 * DexKit 的查询结果缓存用不上（DexAdapt 缓存的是整张符号表，按目录指纹和安装包作废），给它一个只在内存里的缓存。
 * 用不了（初始化冲突、API 变了）时退回 [DexKitScan]（每次打开、用完关掉）。调用前同样要先加载原生库。
 */
@OptIn(org.luckypray.dexkit.annotations.DexKitExperimentalApi::class)
object RecyclableScan {
    private val bridges = ConcurrentHashMap<String, DexKitCacheBridge.RecyclableBridge>()

    @Volatile
    private var ready: Boolean? = null

    /** 桥空闲多久后释放（毫秒）。 */
    var idleMillis: Long = 60_000
        set(value) {
            field = value
            runCatching { DexKitCacheBridge.idleTimeoutMillis = value }
        }

    /** 用 [apks]（base 在前）的数据源跑 [block]；[block] 的异常原样抛出。 */
    fun <T> run(tag: String, apks: List<File>, dexSha256: String, warn: (String, Throwable) -> Unit = { _, _ -> }, block: (SymbolSource) -> T): T {
        require(apks.isNotEmpty()) { "no apk" }
        val recyclable = if (init(warn)) runCatching { apks.map { bridgeFor(tag, it) } }.onFailure { warn("DexKitCacheBridge unavailable, falling back", it) }.getOrNull() else null
        if (recyclable == null) return DexKitScan(apks, dexSha256, warn).use { block(it.source) }
        // 嵌套 withBridge：每个 APK 的桥在 block 期间都不会被回收
        val opened = ArrayList<DexKitBridge>()
        var result: Result<T>? = null
        fun open(i: Int) {
            if (i == recyclable.size) {
                val sources = opened.mapIndexed { k, b -> DexKitSource(b, if (k == 0) dexSha256 else "", warn) }
                result = runCatching { block(if (sources.size == 1) sources[0] else MultiSource(sources)) }
                return
            }
            recyclable[i].withBridge { b: DexKitBridge -> opened += b; open(i + 1) }
        }
        open(0)
        return (result ?: error("DexKitCacheBridge did not run the scan")).getOrThrow()
    }

    /** 释放全部桥（例如宿主升级后旧安装包的桥用不上了）。 */
    fun releaseAll() {
        bridges.values.forEach { runCatching { it.destroy() } }
        bridges.clear()
    }

    private fun bridgeFor(tag: String, apk: File): DexKitCacheBridge.RecyclableBridge {
        val key = "${apk.path}:${apk.length()}:${apk.lastModified()}"
        bridges[key]?.takeIf { !it.isRetired() }?.let { return it }
        // 同一路径换了文件（宿主升级）：旧的不用了
        bridges.keys.filter { it.startsWith(apk.path + ":") && it != key }.forEach { k -> bridges.remove(k)?.let { runCatching { it.destroy() } } }
        return DexKitCacheBridge.create(tag, apk.path).also { bridges[key] = it }
    }

    @Synchronized
    private fun init(warn: (String, Throwable) -> Unit): Boolean {
        ready?.let { return it }
        ready = runCatching {
            DexKitCacheBridge.init(MemoryCache())
            DexKitCacheBridge.idleTimeoutMillis = idleMillis
        }.recoverCatching { e ->
            // 别人（同一进程里的别的代码）已经初始化过也能用
            if (e !is IllegalStateException || e.message?.contains("only be called once") != true) throw e
        }.onFailure { warn("DexKitCacheBridge.init failed", it) }.isSuccess
        return ready!!
    }

    @OptIn(org.luckypray.dexkit.annotations.DexKitExperimentalApi::class)
    private class MemoryCache : DexKitCacheBridge.Cache {
        private val strings = ConcurrentHashMap<String, String>()
        private val lists = ConcurrentHashMap<String, List<String>>()
        override fun getString(key: String, default: String?): String? = strings[key] ?: default
        override fun putString(key: String, value: String) { strings[key] = value }
        override fun getStringList(key: String, default: List<String>?): List<String>? = lists[key] ?: default
        override fun putStringList(key: String, value: List<String>) { lists[key] = value }
        override fun remove(key: String) { strings.remove(key); lists.remove(key) }
        override fun getAllKeys(): Collection<String> = strings.keys + lists.keys
        override fun clearAll() { strings.clear(); lists.clear() }
    }
}
