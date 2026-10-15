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
class DexKitScan(apks: List<File>, dexSha256: String, warn: (String, Throwable) -> Unit = { _, _ -> }) : Closeable {
    private val bridges = ArrayList<DexKitBridge>()
    val source: SymbolSource

    init {
        require(apks.isNotEmpty()) { "no apk" }
        try {
            apks.forEach { bridges += DexKitBridge.create(it.path) }
        } catch (e: Throwable) {
            close()
            throw e
        }
        val sources = bridges.mapIndexed { i, b -> DexKitSource(b, if (i == 0) dexSha256 else "", warn) }
        source = if (sources.size == 1) sources[0] else MultiSource(sources)
    }

    override fun close() {
        bridges.forEach { runCatching { it.close() } }
        bridges.clear()
    }
}
