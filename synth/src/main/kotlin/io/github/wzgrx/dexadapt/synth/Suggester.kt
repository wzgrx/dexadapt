package io.github.wzgrx.dexadapt.synth

import io.github.wzgrx.dexadapt.ClassQuery
import io.github.wzgrx.dexadapt.ClassSymbol
import io.github.wzgrx.dexadapt.FieldQuery
import io.github.wzgrx.dexadapt.FieldSymbol
import io.github.wzgrx.dexadapt.MethodQuery
import io.github.wzgrx.dexadapt.MethodShape
import io.github.wzgrx.dexadapt.MethodSymbol
import io.github.wzgrx.dexadapt.Resolved
import io.github.wzgrx.dexadapt.Symbol
import io.github.wzgrx.dexadapt.TypeRef
import io.github.wzgrx.dexadapt.diff.Obfuscation
import io.github.wzgrx.dexadapt.resolve.ClassView
import io.github.wzgrx.dexadapt.resolve.MethodView
import io.github.wzgrx.dexadapt.resolve.SymbolResolver
import io.github.wzgrx.dexadapt.resolve.SymbolSource
import io.github.wzgrx.dexadapt.type

/**
 * 自动生成查询：给一个已经找到的目标（jadx 里看到的类、方法、字段），挑出能让它**唯一**的、最稳的特征组合写成查询。
 *
 * 按稳定程度从高到低试：没混淆的名字 → 稀有字符串（两个更好，不是单点依赖）→ 源文件名 → 保留的字段名 →
 * 注解 / 数字常量 / 调用的稳定 API → 形状。每个候选都要在 [source] 里唯一命中目标，并且在 [others]（别的版本）里也唯一命中，
 * 才算数。方法和字段需要所属类时，先给所属类生成一个辅助符号（`<id>__owner`）。
 */
class Suggester(private val source: SymbolSource, private val others: List<SymbolSource> = emptyList()) {
    data class Suggestion(
        /** 生成的查询；null 表示没找到能唯一定位的组合。 */
        val query: Symbol?,
        /** 它依赖的辅助符号（所属类等）。 */
        val extra: List<Symbol>,
        /** 用了哪些特征。 */
        val why: String,
    )

    fun suggest(target: Resolved, id: String): Suggestion = when (target) {
        is Resolved.Class -> forClass(target.name, id)
        is Resolved.Method -> forMethod(target, id)
        is Resolved.Field -> forField(target, id)
    }

    /** 候选在这个版本唯一命中 [target]、在别的版本也唯一命中（[deps] 是候选引用到的辅助符号）。 */
    private fun accepts(candidate: Symbol, target: Resolved): Boolean {
        if (SymbolResolver(source, 0).resolve(candidate).getOrNull() != target) return false
        return others.all { o -> SymbolResolver(o, 0).resolve(candidate).isSuccess }
    }

    // ---- 类 ----

    private fun forClass(name: String, id: String): Suggestion {
        val c = source.classByName(name) ?: return Suggestion(null, emptyList(), "$name 不在这个版本里")
        val target = Resolved.Class(name)
        fun tryQ(q: ClassQuery, why: String): Suggestion? = ClassSymbol(id, q).takeIf { accepts(it, target) }?.let { Suggestion(it, emptyList(), why) }
        if (!Obfuscation.looksObfuscated(name)) tryQ(ClassQuery(name = name), "没混淆的类名")?.let { return it }
        val rare = rareStrings(c)
        // 两个稀有字符串（去掉任何一个都还唯一，不是单点依赖）
        if (rare.size >= 2) tryQ(ClassQuery(strings = rare.take(2)), "两个稀有字符串")?.let { return it }
        for (s in rare.take(8)) tryQ(ClassQuery(strings = listOf(s)), "稀有字符串")?.let { return it }
        val sup = c.superName?.takeIf { !Obfuscation.looksObfuscated(it) }?.let(::type)
        c.sourceFile?.takeIf { it.length > 3 && !it.startsWith("SourceFile") }?.let { sf ->
            tryQ(ClassQuery(sourceFile = sf, superclass = sup ?: type(c.superName ?: "java.lang.Object")), "源文件名 + 父类")?.let { return it }
        }
        val names = c.fields.map { it.name }.filter { !Obfuscation.memberObfuscated(it) }
        if (names.size >= 2) tryQ(ClassQuery(fieldNames = names.take(4)), "保留的字段名")?.let { return it }
        // 形状：父类、接口、稳定类型的方法形状
        val stableShapes = c.methods.filter { m -> m.params.all(::stableType) && stableType(m.returns) }.take(4)
            .map { m -> MethodShape(name = m.name.takeIf { !Obfuscation.memberObfuscated(it) }, params = m.params.map(::type), returns = type(m.returns)) }
        val ifaces = c.interfaces.filter { !Obfuscation.looksObfuscated(it) }.map(::type)
        if (stableShapes.isNotEmpty() || sup != null || ifaces.isNotEmpty()) {
            tryQ(ClassQuery(superclass = sup, interfaces = ifaces, methods = stableShapes), "形状（父类、接口、方法形状），比较脆弱")?.let { return it }
        }
        return Suggestion(null, emptyList(), "没找到能唯一定位 $name 的特征组合")
    }

    /** 类里用到、在全 App 里只出现在这个类的字符串，越长越有特征的越靠前。 */
    private fun rareStrings(c: ClassView): List<String> =
        c.methods.flatMap { it.strings }.distinct()
            .filter { it.length >= 4 && source.methodsUsingString(it).all { m -> m.owner == c.name } }
            .sortedWith(compareByDescending<String> { '(' in it && it.endsWith("=") }.thenByDescending { it.length.coerceAtMost(40) })

    private fun stableType(t: String): Boolean {
        val base = t.removeSuffix("[]").removeSuffix("[]")
        return base in PRIMITIVES || !Obfuscation.looksObfuscated(base)
    }

    // ---- 方法 ----

    private fun forMethod(t: Resolved.Method, id: String): Suggestion {
        val m = source.classByName(t.owner)?.methods?.firstOrNull { it.name == t.name && it.params == t.params && it.returns == t.returns }
            ?: return Suggestion(null, emptyList(), "${t.owner}#${t.name} 不在这个版本里")
        // 不需要所属类：方法里有全 App 只它用的字符串
        val unique = m.strings.filter { it.length >= 4 && source.methodsUsingString(it).size == 1 }.sortedByDescending { it.length.coerceAtMost(40) }
        for (s in unique.take(6)) {
            val q = MethodSymbol(id, MethodQuery(shape = MethodShape(strings = listOf(s))))
            if (accepts(q, t)) return Suggestion(q, emptyList(), "方法里独有的字符串")
        }
        val owner = forClass(t.owner, "${id}__owner")
        val ownerSym = owner.query as? ClassSymbol ?: return Suggestion(null, emptyList(), "所属类 ${t.owner} 定位不了：${owner.why}")
        val o = TypeRef.Of(ownerSym)
        fun tryQ(q: MethodQuery, why: String): Suggestion? = MethodSymbol(id, q).takeIf { accepts(it, t) }?.let { Suggestion(it, listOf(ownerSym), "所属类（${owner.why}）+ $why") }
        if (!Obfuscation.memberObfuscated(m.name)) tryQ(MethodQuery(owner = o, shape = MethodShape(name = m.name)), "方法名")?.let { return it }
        for (s in m.strings.sortedByDescending { it.length.coerceAtMost(40) }.take(6)) tryQ(MethodQuery(owner = o, shape = MethodShape(strings = listOf(s))), "字符串")?.let { return it }
        m.annotations.firstOrNull()?.let { a -> tryQ(MethodQuery(owner = o, shape = MethodShape(annotations = listOf(a.substringAfter(' ', a)))), "注解")?.let { return it } }
        stableCalls(m).take(4).forEach { c -> tryQ(MethodQuery(owner = o, callsExact = listOf(c)), "调用了 $c")?.let { return it } }
        val params = m.params.map { if (stableType(it)) type(it) else TypeRef.Any }
        val ret = m.returns.takeIf(::stableType)?.let(::type)
        tryQ(MethodQuery(owner = o, shape = MethodShape(params = params, returns = ret, isStatic = m.isStatic)), "方法形状，比较脆弱")?.let { return it }
        m.numbers.filter { it !in COMMON_NUMBERS }.take(3).takeIf { it.isNotEmpty() }?.let { n ->
            tryQ(MethodQuery(owner = o, shape = MethodShape(params = params, numbers = n)), "形状 + 数字常量")?.let { return it }
        }
        return Suggestion(null, listOf(ownerSym), "所属类能定位，但类里没有能唯一区分这个方法的特征")
    }

    /** 调用到的没混淆的方法（`owner#name`），框架和 SDK 的调用跨版本很稳。 */
    private fun stableCalls(m: MethodView): List<String> =
        m.calls.map { it.substringBefore('(') }
            .filter { c -> !Obfuscation.looksObfuscated(c.substringBefore('#')) && !c.endsWith("#<init>") && INSTRUMENTATION.none { c.startsWith(it) } }.distinct()
            .sortedBy { c -> if (c.startsWith("java.lang.Object#") || c.startsWith("kotlin.jvm.internal.")) 1 else 0 }

    // ---- 字段 ----

    private fun forField(t: Resolved.Field, id: String): Suggestion {
        val owner = forClass(t.owner, "${id}__owner")
        val ownerSym = owner.query as? ClassSymbol ?: return Suggestion(null, emptyList(), "所属类 ${t.owner} 定位不了：${owner.why}")
        val o = TypeRef.Of(ownerSym)
        fun tryQ(q: FieldQuery, why: String): Suggestion? = FieldSymbol(id, q).takeIf { accepts(it, t) }?.let { Suggestion(it, listOf(ownerSym), "所属类（${owner.why}）+ $why") }
        if (!Obfuscation.memberObfuscated(t.name)) tryQ(FieldQuery(o, name = t.name), "字段名")?.let { return it }
        if (stableType(t.type)) tryQ(FieldQuery(o, type = type(t.type)), "字段类型")?.let { return it }
        return Suggestion(null, listOf(ownerSym), "所属类能定位，但字段只能靠混淆的类型区分；用 accessedIn（被哪个方法读写）手工补")
    }

    private companion object {
        val PRIMITIVES = setOf("void", "boolean", "byte", "short", "char", "int", "long", "float", "double")
        val COMMON_NUMBERS = setOf(-1L, 0L, 1L, 2L, 3L, 4L, 8L, 16L)
        /** 编译器或热修复插桩加进几乎每个方法的调用，不算特征（Robust 的 PatchProxy、Kotlin 的空检查……）。 */
        val INSTRUMENTATION = listOf("com.xingin.robust.", "com.meituan.robust.", "kotlin.jvm.internal.Intrinsics#", "java.lang.Object#<init>")
    }
}
