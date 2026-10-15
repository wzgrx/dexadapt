package io.github.wzgrx.dexadapt.diff

import io.github.wzgrx.dexadapt.Resolved
import io.github.wzgrx.dexadapt.SymbolTable
import io.github.wzgrx.dexadapt.dexlib.DexIndex
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * 混淆映射的基准：红薯助手的符号表就是现成的标注——同一个符号在旧版、新版各解析到哪个类，这一对就是“正确答案”。
 * 拿小红书国内版 9.49.1 → 9.50.0 跑一遍映射，看这些类配得对不对（精度）、配上了多少（召回）。
 * 需要样本 APK（DEXADAPT_SAMPLES，默认 ~/projects/xhs-apks）和红薯助手的表（REDBOOK_SYMBOLS）。
 */
class MatcherBenchmarkTest {
    private val samples = File(System.getenv("DEXADAPT_SAMPLES")?.takeIf { it.isNotBlank() } ?: (System.getProperty("user.home") + "/projects/xhs-apks"))
    private val tables = File(System.getenv("REDBOOK_SYMBOLS") ?: (System.getProperty("user.home") + "/projects/redbook-helper-offline/core/symbols/src/main/resources/symbols"))

    @Test
    fun `mapping recovers the known renames between 9_49_1 and 9_50_0`() {
        val oldApk = File(samples, "cn-9491803-device.apk")
        val newApk = File(samples, "cn-9500803-device.apk")
        assumeTrue("no samples", oldApk.isFile && newApk.isFile && File(tables, "9491803.json").isFile)
        val oldTable = SymbolTable.fromJson(File(tables, "9491803.json").readText())
        val newTable = SymbolTable.fromJson(File(tables, "9500803.json").readText())
        val truth = groundTruth(oldTable, newTable)
        val started = System.currentTimeMillis()
        val result = ClassMatcher(DexIndex.load(oldApk), DexIndex.load(newApk)).run()
        val seconds = (System.currentTimeMillis() - started) / 1000
        val predicted = result.byOld
        val answered = truth.keys.filter { it in predicted }
        val correct = answered.count { predicted[it] == truth[it] }
        val wrong = answered.filter { predicted[it] != truth[it] }
        val obf = truth.keys.filter { Obfuscation.looksObfuscated(it) }
        val obfCorrect = obf.count { predicted[it] == truth[it] }
        System.err.println(
            "mapping ${seconds}s: ${result.classes.size} class pairs (same ${result.count(ClassPair.How.SAME_NAME)}, rare ${result.count(ClassPair.How.RARE_STRING)}, " +
                "propagated ${result.count(ClassPair.How.PROPAGATED)}, relative ${result.count(ClassPair.How.RELATIVE)}, structure ${result.count(ClassPair.How.STRUCTURE)}), ${result.methods.size} method pairs",
        )
        System.err.println("ground truth ${truth.size} classes (${obf.size} obfuscated): answered ${answered.size}, correct $correct, wrong ${wrong.size}; obfuscated correct $obfCorrect/${obf.size}")
        System.err.println("precision %.3f recall %.3f".format(correct.toDouble() / answered.size.coerceAtLeast(1), correct.toDouble() / truth.size))
        wrong.take(10).forEach { System.err.println("  wrong $it -> ${predicted[it]} (truth ${truth[it]}, ${result.classes.first { p -> p.old == it }.how})") }
        truth.keys.filter { it !in predicted }.take(10).forEach { System.err.println("  missed $it -> ${truth[it]}") }
        assertTrue("precision too low", correct >= answered.size * 0.95)
    }

    /** 两张表里同一个符号的所属类（类符号本身、方法和字段的所属类）配成对；一个旧类对应多个新类的丢掉。 */
    private fun groundTruth(a: SymbolTable, b: SymbolTable): Map<String, String> {
        fun owner(r: Resolved) = when (r) {
            is Resolved.Class -> r.name
            is Resolved.Method -> r.owner
            is Resolved.Field -> r.owner
        }
        val pairs = HashMap<String, MutableSet<String>>()
        for ((id, x) in a.entries) {
            val y = b.entries[id] ?: continue
            pairs.getOrPut(owner(x)) { HashSet() } += owner(y)
        }
        return pairs.filterValues { it.size == 1 }.mapValues { it.value.single() }
    }
}
