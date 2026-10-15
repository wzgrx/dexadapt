package io.github.wzgrx.dexadapt.dexkit

import java.io.File

/**
 * 电脑上加载 DexKit 原生库。库在 `$DEXKIT_DESKTOP_HOME/<版本>/libdexkit.so`（默认 `~/tools/dexkit-desktop`），
 * 由 scripts/build-dexkit-desktop.sh 按 libs.versions.toml 的 DexKit 版本构建，和手机上用的版本一致。
 */
object DesktopDexKit {
    /** 期望的原生库位置（不一定存在）。 */
    val library: File
        get() = File(
            System.getenv("DEXKIT_DESKTOP_HOME")?.takeIf { it.isNotBlank() } ?: (System.getProperty("user.home") + "/tools/dexkit-desktop"),
            "${DexKitInfo.VERSION}/libdexkit.so",
        )

    val available: Boolean get() = library.isFile

    @Volatile private var loaded = false

    /** 加载一次；库不存在时抛出说明怎么构建的错误。 */
    @Synchronized
    fun load() {
        if (loaded) return
        check(available) { "没有桌面版 DexKit：先运行 scripts/build-dexkit-desktop.sh（找的是 $library）" }
        System.load(library.absolutePath)
        loaded = true
    }
}
