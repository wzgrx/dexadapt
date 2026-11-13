package io.github.wzgrx.dexadapt.dexkit

import io.github.wzgrx.dexadapt.resolve.DexDigest
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

    private data class Input(val apks: List<File>, val digest: String)
    private constructor(input: Input, warn: (String, Throwable) -> Unit) : this(open(input.apks), input.digest, warn)

    constructor(apks: List<File>, dexSha256: String, warn: (String, Throwable) -> Unit = { _, _ -> }) :
        this(Input(apks, if (apks.count(DexDigest::hasCode) > 1) DexDigest.of(apks) else dexSha256), warn)

    init {
        val sources = bridges.mapIndexed { i, b -> DexKitSource(b, if (i == 0) dexSha256 else "", warn) }
        source = if (sources.size == 1) sources[0] else MultiSource(sources)
    }

    override fun close() {
        bridges.forEach { runCatching { it.close() } }
        bridges.clear()
    }

    companion object {
        /** 运行时严格适配入口：按实际文件验证完整 DEX，不接受未经核验的调用方摘要。 */
        fun verified(apks: List<File>, expectedDexSha256: String, warn: (String, Throwable) -> Unit = { _, _ -> }): DexKitScan {
            val code = apks.filter(DexDigest::hasCode)
            val actual = DexDigest.of(code)
            require(actual == expectedDexSha256) { "DexKit 打开的 APK 与预期 DEX 身份不符" }
            return DexKitScan(Input(code, actual), warn)
        }

        /** 安装快照提供完整摘要；打开前后均重查，避免将旧桥标记为新安装。 */
        fun installed(apks: List<File>, expectedDexSha256: String, currentDigest: () -> String?): DexKitScan {
            require(currentDigest() == expectedDexSha256) { "打开 DexKit 前安装身份已变化" }
            val scan = DexKitScan(Input(apks.filter(DexDigest::hasCode), expectedDexSha256), { _, _ -> })
            try {
                require(currentDigest() == expectedDexSha256) { "打开 DexKit 时安装身份已变化" }
                return scan
            } catch (e: Throwable) {
                scan.close()
                throw e
            }
        }

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
