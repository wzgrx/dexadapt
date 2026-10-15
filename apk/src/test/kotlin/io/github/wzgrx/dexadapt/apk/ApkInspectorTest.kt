package io.github.wzgrx.dexadapt.apk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * 用真实的小红书安装包核对（数字和 aapt2 dump badging / xmltree 对过）。没有样本时跳过：
 * 样本目录由 `DEXADAPT_SAMPLES` 指定（每个 App 一个子目录），默认 `~/projects/xhs-apks`。
 */
class ApkInspectorTest {
    private val samples = File(System.getenv("DEXADAPT_SAMPLES") ?: (System.getProperty("user.home") + "/projects/xhs-apks"))

    @Test
    fun `single apk with genuine signature`() {
        val apk = File(samples, "cn-9500803-device.apk")
        assumeTrue("no sample $apk", apk.isFile)
        val info = ApkInspector.inspect(ApkSet.open(apk))
        assertEquals("com.xingin.xhs", info.packageName)
        assertEquals(9500803L, info.versionCode)
        assertEquals("9.50.0", info.versionName)
        assertEquals(36, info.targetSdk)
        assertEquals(36, info.compileSdk)
        assertTrue(info.verified)
        assertEquals("f375f0f6af7c94c364b35cd6f6a66d64aefae66e32f935b48773c0faad04c121", info.signers.single().sha256)
        assertTrue(info.signers.single().subject.contains("CN=xiaohongshu"))
        // aapt2 xmltree 数到 1042 个，其中 2 个是 <queries> 里的 provider（包可见性声明，不是组件）。
        assertEquals(1040, info.manifest.components.size)
        assertTrue(info.manifest.usesPermissions.contains("android.permission.INTERNET"))
        assertTrue(info.dex.size > 10)
        assertTrue("arm64-v8a" in info.abis)
        // 组件名是完整类名；启动页有 MAIN / LAUNCHER。
        val launcher = info.manifest.components.filter { "android.intent.action.MAIN" in it.actions && "android.intent.category.LAUNCHER" in it.categories }
        assertTrue(launcher.isNotEmpty() && launcher.all { it.name.startsWith("com.") })
    }

    @Test
    fun `split directory puts base first and reads play signature files`() {
        val dir = File(samples, "play-9492803")
        assumeTrue("no sample $dir", dir.isDirectory)
        val set = ApkSet.open(dir)
        assertEquals("base.apk", set.base.name)
        val info = ApkInspector.inspect(set)
        assertEquals(9492803L, info.versionCode)
        assertEquals("9.49.2", info.versionName)
        assertEquals(3, info.files.size)
        assertTrue(info.verified)
        assertTrue(info.signers.single().subject.contains("OU=xingin"))
        // 原生库在 arm64 拆分包里。
        assertTrue("arm64-v8a" in info.abis)
    }
}
