package io.github.wzgrx.dexadapt.dexkit

import io.github.wzgrx.dexadapt.resolve.MultiSource
import io.github.wzgrx.dexadapt.resolve.SymbolSource
import org.luckypray.dexkit.DexKitBridge
import java.io.Closeable
import java.io.File

/**
 * 用 DexKit 打开一个宿主安装包（base.apk 加带代码的拆分包），合成一个数据源：每个 APK 一个桥，类以 base 为准
 * （和类加载器一样）。用完关掉（桥持有原生资源）。调用前要先加载原生库（手机上 `System.loadLibrary("dexkit")`，
 * 电脑上 `DesktopDexKit.load()`）。[dexSha256] 是 base 的 dex 摘要（记进生成的表）。
 */
class DexKitScan private constructor(private val bridges: ArrayList<DexKitBridge>, dexSha256: String, warn: (String, Throwable) -> Unit) : Closeable {
    val source: SymbolSource

    constructor(apks: List<File>, dexSha256: String, warn: (String, Throwable) -> Unit = { _, _ -> }) : this(open(apks), dexSha256, warn)

    init {
        val sources = bridges.mapIndexed { i, b -> DexKitSource(b, if (i == 0) dexSha256 else "", warn) }
        source = if (sources.size == 1) sources[0] else MultiSource(sources)
    }

    override fun close() {
        bridges.forEach { runCatching { it.close() } }
        bridges.clear()
    }

    companion object {
        private fun open(apks: List<File>): ArrayList<DexKitBridge> {
            require(apks.isNotEmpty()) { "no apk" }
            val out = ArrayList<DexKitBridge>()
            try {
                apks.forEach { out += DexKitBridge.create(it.path) }
            } catch (e: Throwable) {
                out.forEach { runCatching { it.close() } }
                throw e
            }
            return out
        }

        /**
         * 加固 / 动态加载（设计 10.10）：在宿主进程里，从运行中的 [loader] 读代码。加固包的安装包里只有壳，真正的 dex 解密后
         * 才在内存里；插件、热修复的 dex 也是运行时才加载。[useMemoryDexFile] 为真时读内存里的 dex（加固包要这样），
         * 否则读 ClassLoader 指向的文件。摘要记成 `classloader`（没有文件可算），按它生成的表只在这个进程里用。
         */
        fun ofClassLoader(loader: ClassLoader, useMemoryDexFile: Boolean = true, warn: (String, Throwable) -> Unit = { _, _ -> }): DexKitScan =
            DexKitScan(arrayListOf(DexKitBridge.create(loader, useMemoryDexFile)), "classloader", warn)

        /** 导出的 dex 字节（例如脱壳工具从内存里导出的），按顺序；摘要按字节算，和 [io.github.wzgrx.dexadapt.resolve.DexDigest] 同一种拼法。 */
        fun ofDexBytes(dexes: List<ByteArray>, warn: (String, Throwable) -> Unit = { _, _ -> }): DexKitScan {
            require(dexes.isNotEmpty()) { "no dex" }
            val md = java.security.MessageDigest.getInstance("SHA-256")
            dexes.forEach(md::update)
            val sha = io.github.wzgrx.dexadapt.Hex.of(md.digest())
            return DexKitScan(arrayListOf(DexKitBridge.create(dexes.toTypedArray())), sha, warn)
        }
    }
}
