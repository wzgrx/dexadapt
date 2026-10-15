package io.github.wzgrx.dexadapt.diff

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** 小红书 9.49.1 → 9.50.0 的原生库：有导出符号变化的库能列出来。需要样本 APK。 */
class NativeDiffTest {
    private val dir = File(System.getenv("DEXADAPT_SAMPLES")?.takeIf { it.isNotBlank() } ?: (System.getProperty("user.home") + "/projects/xhs-apks"))

    @Test
    fun `lists export changes between two versions`() {
        val a = File(dir, "cn-9491803-device.apk")
        val b = File(dir, "cn-9500803-device.apk")
        assumeTrue(a.isFile && b.isFile)
        val d = NativeDiff.of(listOf(a), listOf(b))
        d.changed.take(8).forEach { System.err.println("  $it") }
        System.err.println("${d.changed.size} libraries changed exports or dependencies")
        assertTrue(d.changed.all { "：" in it })
        assertTrue(NativeDiff.of(listOf(b), listOf(b)).empty)
    }
}
