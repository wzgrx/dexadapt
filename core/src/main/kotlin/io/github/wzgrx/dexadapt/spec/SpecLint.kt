package io.github.wzgrx.dexadapt.spec

import io.github.wzgrx.dexadapt.Catalog
import io.github.wzgrx.dexadapt.ClassQuery
import io.github.wzgrx.dexadapt.ClassSymbol
import io.github.wzgrx.dexadapt.FieldSymbol
import io.github.wzgrx.dexadapt.MethodShape
import io.github.wzgrx.dexadapt.MethodSymbol
import io.github.wzgrx.dexadapt.Resolved
import io.github.wzgrx.dexadapt.Symbol
import io.github.wzgrx.dexadapt.SymbolTable

/**
 * spec 检查器（设计 10.9）：不用 APK 就能发现的写法问题，加上（给了解析结果时）和真实 APK 对不上的地方。
 * 错误会让解析失败或结果不可信；警告是下个版本容易坏、手机上慢；提示是可以写得更稳的地方。
 */
object SpecLint {
    enum class Level(val label: String) { ERROR("错误"), WARNING("警告"), HINT("提示") }

    data class Issue(val level: Level, val id: String, val message: String) {
        override fun toString() = "${level.label} $id：$message"
    }

    /** 混淆后的类名（R8 给的：小写、短，例如 a、ab、c0；真实的短类名像 Tag、Ad 是大写开头的）。 */
    private val OBFUSCATED_CLASS = Regex("[a-z]{1,3}\\d{0,3}")

    /** 混淆后的成员名（单个字母或字母加数字，例如 a、b2、d0；id、ad 这种 Gson 字段名是真名字）。 */
    private val OBFUSCATED_MEMBER = Regex("[a-zA-Z]\\d{0,3}")

    fun check(catalog: Catalog): List<Issue> = buildList {
        catalog.problems().forEach { add(Issue(Level.ERROR, it.substringBefore(':').trim(), it.substringAfter(':', it).trim())) }
        for (s in catalog.symbols) addAll(symbol(s))
        val dupes = catalog.symbols.groupBy { queryKey(it) }.filterValues { it.size > 1 }
        for (group in dupes.values) {
            add(Issue(Level.HINT, group.first().id, "和 ${group.drop(1).joinToString { it.id }} 的查询一模一样（命中同一个成员；分在不同组是故意的就不用管，否则可以共用一个）"))
        }
    }

    /** 加上解析结果（某个版本的表）才能查的：两个符号命中同一个成员、契约和位置对不上。 */
    fun check(catalog: Catalog, table: SymbolTable, label: String = "这个版本"): List<Issue> = buildList {
        val byTarget = table.entries.entries.groupBy({ it.value }, { it.key })
        for ((target, ids) in byTarget) {
            if (ids.size < 2) continue
            // 同一组里两个符号命中同一个成员多半是重复或命中错了；不同组各自定义同一个目标通常是故意的（组之间互不依赖）
            val sameGroup = ids.groupBy { it.substringBefore('.') }.values.any { it.size > 1 }
            add(
                Issue(
                    if (sameGroup) Level.WARNING else Level.HINT, ids.first(),
                    "$label 上和 ${ids.drop(1).joinToString()} 命中同一个成员 ${describe(target)}" +
                        if (sameGroup) "（同一组里重复：留一个，或者有一个查询命中错了）" else "（不同组各自定义，通常是故意的）",
                ),
            )
        }
        for ((id, c) in catalog.contracts) {
            val r = table.entries[id] as? Resolved.Method ?: continue
            c.problems(r).forEach { add(Issue(Level.ERROR, id, "$label 上契约对不上：$it")) }
        }
        for ((id, why) in table.missing) add(Issue(Level.ERROR, id, "$label 上解析不到：$why"))
    }

    private fun symbol(s: Symbol): List<Issue> = buildList {
        when (s) {
            is ClassSymbol -> {
                val q = s.query
                if (q.name != null) {
                    val simple = q.name.substringAfterLast('.').substringAfterLast('$')
                    val pkg = q.name.substringBeforeLast('.', "")
                    if (OBFUSCATED_CLASS.matches(simple) || pkg.split('.').any { it.length == 1 }) {
                        add(Issue(Level.WARNING, s.id, "按混淆名 ${q.name} 找类：下个版本名字多半会变，改用字符串、字段名等特征"))
                    }
                    if (q != ClassQuery(name = q.name)) add(Issue(Level.HINT, s.id, "已经按类名找了，其余条件只用来核对"))
                } else if (anchorsOf(q).isEmpty()) {
                    add(Issue(Level.WARNING, s.id, "只靠形状（父类、接口、方法形状）：最容易被新加的同形状类打破，加一个字符串或字段名"))
                }
                if (q.strings.any { it.length < 4 }) add(Issue(Level.HINT, s.id, "字符串 ${q.strings.filter { it.length < 4 }} 太短，到处都有，不像特征"))
            }
            is MethodSymbol -> {
                val q = s.query
                q.shapesFor(null, "").forEach { (label, sh) ->
                    val branch = if (label == "shape") "" else "$label："
                    if (sh == MethodShape() && q.variants.isNotEmpty()) {
                        add(Issue(Level.ERROR, s.id, branch + "备用签名不能没有形状条件"))
                    }
                    if (q.owner == null && q.packagePrefix == null && sh.strings.isEmpty() && q.calledBy.isEmpty()) {
                        add(Issue(if (q.variants.isEmpty()) Level.WARNING else Level.ERROR, s.id, branch + "没有所属类、包、字符串或调用者做起点：手机上要扫全部方法，又慢又容易多命中"))
                    }
                    val anchors = sh.strings.isNotEmpty() || q.calls.isNotEmpty() || q.callsExact.isNotEmpty() || q.calledBy.isNotEmpty() ||
                        q.usesFields.isNotEmpty() || sh.numbers.isNotEmpty() || sh.annotations.isNotEmpty() || (sh.name != null && !OBFUSCATED_MEMBER.matches(sh.name))
                    if (!anchors && q.owner != null) add(Issue(Level.HINT, s.id, branch + "只靠所属类里的方法形状：类里加一个同形状的方法就会多命中"))
                    if (sh.name != null && OBFUSCATED_MEMBER.matches(sh.name)) {
                        add(Issue(Level.WARNING, s.id, branch + "按混淆的方法名 ${sh.name} 找：下个版本多半会变"))
                    }
                    if (sh.isStatic == true && sh.name == "<init>") add(Issue(Level.ERROR, s.id, branch + "构造方法不可能是静态的"))
                    if (sh.opcodes.size > 30) add(Issue(Level.HINT, s.id, branch + "操作码序列很长（${sh.opcodes.size} 个）：方法体一改就失效，挑几条有特征的就够"))
                    if (sh.strings.any { it.length < 4 }) add(Issue(Level.HINT, s.id, branch + "字符串 ${sh.strings.filter { it.length < 4 }} 太短，到处都有，不像特征"))
                }
            }
            is FieldSymbol -> {
                val q = s.query
                if (q.name == null && q.type == null && q.accessedIn.isEmpty()) add(Issue(Level.ERROR, s.id, "字段查询只有所属类，类里有多个字段时不可能唯一"))
                if (q.name != null && OBFUSCATED_MEMBER.matches(q.name)) {
                    add(Issue(Level.WARNING, s.id, "按混淆的字段名 ${q.name} 找：下个版本多半会变，改用类型或 accessedIn"))
                }
            }
        }
        val declaration=queryKey(s)
        if(declaration.contains("kotlin.Lazy") || declaration.contains("kotlin.jvm.internal.Intrinsics#")) {
            add(Issue(Level.WARNING,s.id,"依赖Kotlin库实现名，R8可能重命名；优先使用业务锚点和调用/字段关系并做多版本验证"))
        }
        if (s.pins.isNotEmpty() && s.absentIn.any { it in s.pins.keys }) add(Issue(Level.ERROR, s.id, "同一个构建既钉了位置又标了“没有”：${s.absentIn.filter { it in s.pins.keys }}"))
    }

    private fun anchorsOf(q: ClassQuery): List<String> = buildList {
        addAll(q.strings); addAll(q.fieldNames); addAll(q.annotations)
        q.sourceFile?.let { add(it) }
        q.instantiatedIn.forEach { add(it.id) }; q.declares.forEach { add(it.id) }
        q.fieldOf?.let { add("fieldOf") }; q.samePackageAs?.let { add("samePackageAs") }
        q.packagePrefix?.let { add(it) }
    }

    private fun queryKey(s: Symbol): String = when (s) {
        is ClassSymbol -> "c:" + s.query
        is MethodSymbol -> "m:" + s.query
        is FieldSymbol -> "f:" + s.query
    }

    private fun describe(r: Resolved): String = when (r) {
        is Resolved.Class -> r.name
        is Resolved.Method -> "${r.owner}#${r.name}(${r.params.joinToString(",")})${r.returns}"
        is Resolved.Field -> "${r.owner}#${r.name}:${r.type}"
    }
}
