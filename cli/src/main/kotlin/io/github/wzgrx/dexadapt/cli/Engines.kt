package io.github.wzgrx.dexadapt.cli

import io.github.wzgrx.dexadapt.apk.ApkInfo
import io.github.wzgrx.dexadapt.apk.ApkInspector
import io.github.wzgrx.dexadapt.apk.ApkSet
import io.github.wzgrx.dexadapt.dexkit.DesktopDexKit
import io.github.wzgrx.dexadapt.dexkit.DexKitSource
import io.github.wzgrx.dexadapt.dexlib.DexIndex
import io.github.wzgrx.dexadapt.matrix.Build
import io.github.wzgrx.dexadapt.resolve.DexDigest
import io.github.wzgrx.dexadapt.resolve.MultiSource
import io.github.wzgrx.dexadapt.resolve.SymbolSource
import org.luckypray.dexkit.DexKitBridge
import java.io.Closeable
import java.io.File
import java.util.zip.ZipFile

/** 解析引擎：电脑上的全量索引（dexlib2），或和手机上同一套的 DexKit。 */
enum class Engine { DEXLIB, DEXKIT }

/** 打开的一个安装包：档案、数据源，用完关掉（DexKit 持有原生资源）。 */
class Opened(val set: ApkSet, val info: ApkInfo, val source: SymbolSource, private val closer: () -> Unit, val indices: List<DexIndex> = emptyList()) : Closeable {
    override fun close() = closer()
    val codeIdentity: String get() = source.dexSha256
    fun diffIndex():DexIndex = (source as? DexIndex) ?: DexIndex.combine(indices,codeIdentity)
}

object Engines {
    fun open(path: File, engine: Engine): Opened {
        val set = ApkSet.open(path)
        val info = ApkInspector.inspect(set)
        // 带代码的拆分包也要算（类以 base 为准，和类加载器一样）。
        val withCode = set.files.filter { f -> ZipFile(f).use { z -> z.getEntry("classes.dex") != null } }
        return when (engine) {
            Engine.DEXLIB -> {
                val sources = withCode.map { DexIndex.load(it) }
                Opened(set, info, combineSource(sources), {}, sources)
            }
            Engine.DEXKIT -> {
                DesktopDexKit.load()
                val bridges = withCode.map { DexKitBridge.create(it.path) }
                val sources = bridges.mapIndexed { i, b -> DexKitSource(b, DexDigest.of(withCode[i])) }
                Opened(set, info, combineSource(sources), { bridges.forEach { runCatching { it.close() } } })
            }
        }
    }

    /** 桌面完整拆包身份不能只认base；旧的base-only运行时将拒绝不匹配的表，避免错误复用。 */
    private fun combineSource(sources:List<SymbolSource>):SymbolSource {
        require(sources.isNotEmpty()) { "安装包没有代码" }
        if(sources.size==1)return sources.single()
        val delegate=MultiSource(sources)
        val identity=DexDigest.combine(sources.map { it.dexSha256 })
        return object:SymbolSource by delegate { override val dexSha256=identity }
    }

    /**
     * 安装包对应的构建：渠道按签名文件猜（带 Google Play 签名文件或 source stamp 的算 `play`，否则 `default`），
     * 可以用 [channels]（文件路径 → 渠道名）指定。构建标识是 `<渠道>_<versionCode>`。
     */
    fun build(path: File, info: ApkInfo, channels: Map<String, String>): Build {
        val channel = channels[path.path] ?: channels[path.absolutePath] ?: if (info.fromPlay) "play" else "default"
        return Build("${channel.uppercase()}_${info.versionCode}", channel, info.versionCode, info.versionName)
    }
}
