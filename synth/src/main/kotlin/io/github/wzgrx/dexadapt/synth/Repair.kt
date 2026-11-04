package io.github.wzgrx.dexadapt.synth

import io.github.wzgrx.dexadapt.Catalog
import io.github.wzgrx.dexadapt.ClassQuery
import io.github.wzgrx.dexadapt.ClassSymbol
import io.github.wzgrx.dexadapt.FieldQuery
import io.github.wzgrx.dexadapt.FieldSymbol
import io.github.wzgrx.dexadapt.MethodQuery
import io.github.wzgrx.dexadapt.MethodSymbol
import io.github.wzgrx.dexadapt.Resolved
import io.github.wzgrx.dexadapt.Symbol
import io.github.wzgrx.dexadapt.pin
import io.github.wzgrx.dexadapt.absentIn
import io.github.wzgrx.dexadapt.resolve.SymbolMargin
import io.github.wzgrx.dexadapt.resolve.SymbolResolver
import io.github.wzgrx.dexadapt.resolve.SymbolSource
import io.github.wzgrx.dexadapt.resolve.forBuild
import io.github.wzgrx.dexadapt.spec.Spec
import kotlinx.serialization.Serializable

/**
 * 修复：符号在新版本解析不到时，找出是哪个条件不满足，并给出改法。按代价从小到大试：
 *
 * 1. **放宽**：逐个去掉查询里的一个条件（和余量分析同一套变体），去掉后在新版唯一命中、而且在旧版仍然命中原来那个，
 *    说明就是这个条件变了——去掉它就是修复（9.50.0 的 attachBrowser 就是参数变了，去掉参数条件即可）；
 * 2. **按映射找到新位置**（[guess] 由调用方根据混淆映射给出），以它为目标重新生成查询（[Suggester]），
 *    要求在新旧两版都唯一命中。
 *
 * 每条建议都在两个版本上验证过才给出。
 */
class Repair(
    private val oldSource: SymbolSource,
    private val newSource: SymbolSource,
    private val oldVersionCode: Long = 0,
    private val newVersionCode: Long = 0,
    private val oldBuild: String? = null,
    private val newBuild: String? = null,
    private val regression: List<Sample> = emptyList(),
    private val catalog: Catalog? = null,
) {
    data class Sample(val source: SymbolSource, val versionCode: Long, val build: String?)
    @Serializable
    data class Fix(
        val id: String,
        /** 诊断：哪个条件不满足。 */
        val diagnosis: String,
        /** 修复方式：去掉某个条件 / 按新位置重新生成。 */
        val action: String,
        /** 修好后在新版命中的位置。 */
        val newLocation: String?,
        /** 修好的查询（YAML 片段，只有这一个符号）。 */
        val yaml: String?,
        val checkedBuilds: List<String> = emptyList(),
        @kotlinx.serialization.Transient val candidate: Symbol? = null,
        @kotlinx.serialization.Transient val extra: List<Symbol> = emptyList(),
    )

    private val oldResolver = SymbolResolver.forBuild(oldSource, oldVersionCode, oldBuild)
    private val newResolver = SymbolResolver.forBuild(newSource, newVersionCode, newBuild)

    fun repair(symbol: Symbol, guess: Resolved? = null): Fix {
        val before = oldResolver.resolve(symbol).getOrNull()
        val failure = newResolver.resolve(symbol).exceptionOrNull()?.message ?: return Fix(symbol.id, "新版能解析", "不用改", null, null)
        if (before == null && oldBuild !in symbol.absentIn) return Fix(symbol.id, failure, "旧版目标未确认，不自动放宽", null, null)
        // 1. 放宽：逐个去掉一个条件
        for ((label, variant) in SymbolMargin.variants(symbol)) {
            val relaxed = withQuery(symbol, variant) ?: continue
            val inNew = checkedTarget(relaxed, newSource, newVersionCode, newBuild) ?: continue
            if (guess != null && inNew != guess) continue
            if (!preserves(symbol, relaxed, emptyList())) continue
            return Fix(symbol.id, "新版里“$label”这个条件不满足（$failure）", "去掉条件 $label", text(inNew), yamlOf(relaxed), checked(), relaxed)
        }
        // 2. 按推测的新位置重新生成
        if (guess != null) {
            val s = Suggester(newSource, others = listOf(oldSource)).suggest(guess, symbol.id)
            if (s.query != null && before != null) {
                val replacement = metadata(symbol, s.query)
                if (SymbolResolver.forBuild(newSource, newVersionCode, newBuild).resolve(rebound(replacement, s.extra)).getOrNull() == guess && preserves(symbol, replacement, s.extra)) return Fix(symbol.id, "新版解析失败（$failure），按混淆映射找到了对应位置", "重新生成查询：${s.why}", text(guess), yamlOf(replacement, s.extra), checked(), replacement, s.extra)
            }
            return Fix(symbol.id, "新版解析失败（$failure），映射推测在 ${text(guess)}", "没生成出两版都唯一的查询，需要人看", text(guess), null)
        }
        return Fix(symbol.id, "新版解析失败（$failure）", "去掉任何一个条件都不行，也没有映射线索；可能功能被删了或重写了", null, null)
    }

    private fun checked() = (listOf(oldBuild ?: "old:$oldVersionCode", newBuild ?: "new:$newVersionCode") + regression.map { it.build ?: "unknown:${it.versionCode}" }).distinct()

    private fun rebound(symbol: Symbol, extra: List<Symbol> = emptyList()): Symbol {
        val all = catalog ?: return symbol
        return Spec(null, all).withSymbols(extra + symbol).catalog.symbols.single { it.id == symbol.id }
    }
    private fun checkedTarget(symbol: Symbol, source: SymbolSource, code: Long, build: String?): Resolved? =
        SymbolResolver.forBuild(source, code, build).resolve(rebound(symbol)).getOrNull()

    private fun preserves(original: Symbol, replacement: Symbol, extra: List<Symbol>): Boolean {
        val candidate = rebound(replacement, extra)
        val samples = listOf(Sample(oldSource, oldVersionCode, oldBuild)) + regression
        return samples.all { sample ->
            if (sample.build in original.absentIn) true else {
                val expected = SymbolResolver.forBuild(sample.source, sample.versionCode, sample.build).resolve(original).getOrNull()
                expected != null && SymbolResolver.forBuild(sample.source, sample.versionCode, sample.build).resolve(candidate).getOrNull() == expected
            }
        }
    }
    private fun metadata(original: Symbol, replacement: Symbol): Symbol = replacement.apply {
        original.pins.forEach { (build,position)->pin(build,position) };absentIn(*original.absentIn.toTypedArray())
    }
    private fun withQuery(s: Symbol, q: Any): Symbol? = when {
        s is ClassSymbol && q is ClassQuery -> metadata(s, ClassSymbol(s.id, q))
        s is MethodSymbol && q is MethodQuery -> metadata(s, MethodSymbol(s.id, q))
        s is FieldSymbol && q is FieldQuery -> metadata(s, FieldSymbol(s.id, q))
        else -> null
    }

    /** 只写修好的这个符号（和它新生成的辅助符号）；引用的其它符号在 YAML 里是 `$id`，沿用原 spec 里的定义。 */
    private fun yamlOf(s: Symbol, extra: List<Symbol> = emptyList()): String =
        io.github.wzgrx.dexadapt.spec.Spec(null, Catalog(extra + s)).toYaml().lines().dropWhile { !it.startsWith("symbols:") }.joinToString("\n")

    companion object {
        fun text(r: Resolved): String = when (r) {
            is Resolved.Class -> r.name
            is Resolved.Method -> "${r.owner}#${r.name}(${r.params.joinToString(",")})${r.returns}"
            is Resolved.Field -> "${r.owner}#${r.name}:${r.type}"
        }
    }
}
