package io.github.wzgrx.dexadapt.resolve

import io.github.wzgrx.dexadapt.ClassQuery
import io.github.wzgrx.dexadapt.ClassSymbol
import io.github.wzgrx.dexadapt.FieldQuery
import io.github.wzgrx.dexadapt.FieldSymbol
import io.github.wzgrx.dexadapt.MethodQuery
import io.github.wzgrx.dexadapt.MethodSymbol
import io.github.wzgrx.dexadapt.Symbol
import io.github.wzgrx.dexadapt.TypeRef

/**
 * 一条查询的“余量”：逐个去掉一个条件，看还剩几个候选。去掉某个条件后不再唯一，说明唯一性只靠这一个条件撑着
 * （“单点依赖”）：下个版本这个条件一变（多了个同形状的兄弟、字段换了类型），查询就会多命中或找不到。
 *
 * 名字（类名、方法名、字段名）和所属类不算可去掉的条件：查询里写名字的都是没混淆、跨版本稳定的身份，
 * 所属类本身的余量在它自己的查询里算。
 */
data class SymbolMargin(val id: String, val drops: List<Drop>) {
    /** 去掉 [constraint] 后的候选数；[UNBOUNDED] 表示没有起点（要扫全部成员），[UNKNOWN] 表示没算出来。 */
    data class Drop(val constraint: String, val count: Int) {
        override fun toString() = "$constraint→" + when (count) {
            UNBOUNDED -> "∞"
            UNKNOWN -> "?"
            else -> count.toString()
        }
    }

    /** 去掉后不再唯一的条件。 */
    val critical: List<Drop> get() = drops.filter { it.count > 1 }
    val singlePoint: Boolean get() = critical.isNotEmpty()

    /**
     * 唯一性靠的是一个纯形状条件（类型、参数、返回值、静态、父类、包……），不是字符串、调用关系这类“锚”：
     * 这种最容易在下个版本被一个新加的同形状成员打破。
     */
    val shapeOnly: Boolean get() = critical.any { d -> ANCHORS.none { d.constraint.startsWith(it) } }

    companion object {
        const val UNBOUNDED = Int.MAX_VALUE
        const val UNKNOWN = -1

        /** 算作“锚”的条件（字符串、调用 / 被调用、创建、字段读写、Gson 字段名、所属关系）。 */
        private val ANCHORS = listOf(
            "strings[", "calls[", "callsExact[", "calledBy[", "instantiatedIn[", "declares[", "accessedIn[", "usesFields[",
            "fieldNames[", "fieldOf", "samePackageAs", "excludes[", "sourceFile", "annotations[", "numbers[",
        )

        /** [symbol] 的查询逐个去掉一个条件后的变体（条件说明 to 变体查询）。 */
        fun variants(symbol: Symbol): List<Pair<String, Any>> = when (symbol) {
            is ClassSymbol -> classVariants(symbol.query)
            is MethodSymbol -> methodVariants(symbol.query)
            is FieldSymbol -> fieldVariants(symbol.query)
        }

        private fun ref(t: TypeRef?): String = when (t) {
            null -> "null"
            TypeRef.Any -> "*"
            is TypeRef.Exact -> t.name
            is TypeRef.Of -> t.symbol.id
            is TypeRef.SuperOf -> "super(${t.symbol.id})"
        }

        private fun <T> List<T>.without(i: Int): List<T> = filterIndexed { j, _ -> j != i }

        private fun classVariants(q: ClassQuery): List<Pair<String, Any>> = buildList {
            if (q.name != null) return@buildList // 按类名找：身份本身，没有可去掉的条件
            q.packagePrefix?.let { add("packagePrefix" to q.copy(packagePrefix = null)) }
            q.superclass?.let { add("superclass" to q.copy(superclass = null)) }
            q.interfaces.forEachIndexed { i, t -> add("interfaces[${ref(t)}]" to q.copy(interfaces = q.interfaces.without(i))) }
            q.strings.forEachIndexed { i, s -> add("strings[\"$s\"]" to q.copy(strings = q.strings.without(i))) }
            q.fieldTypes.forEachIndexed { i, t -> add("fieldTypes[${ref(t)}]" to q.copy(fieldTypes = q.fieldTypes.without(i))) }
            q.fieldNames.forEachIndexed { i, n -> add("fieldNames[$n]" to q.copy(fieldNames = q.fieldNames.without(i))) }
            q.methods.forEachIndexed { i, _ -> add("methods[$i]" to q.copy(methods = q.methods.without(i))) }
            q.isInterface?.let { add("isInterface" to q.copy(isInterface = null)) }
            q.isAbstract?.let { add("isAbstract" to q.copy(isAbstract = null)) }
            q.samePackageAs?.let { add("samePackageAs" to q.copy(samePackageAs = null)) }
            q.fieldOf?.let { add("fieldOf" to q.copy(fieldOf = null)) }
            q.instantiatedIn.forEachIndexed { i, m -> add("instantiatedIn[${m.id}]" to q.copy(instantiatedIn = q.instantiatedIn.without(i))) }
            q.declares.forEachIndexed { i, m -> add("declares[${m.id}]" to q.copy(declares = q.declares.without(i))) }
            q.sourceFile?.let { add("sourceFile" to q.copy(sourceFile = null)) }
            q.annotations.forEachIndexed { i, a -> add("annotations[\"$a\"]" to q.copy(annotations = q.annotations.without(i))) }
        }

        private fun methodVariants(q: MethodQuery): List<Pair<String, Any>> = buildList {
            val s = q.shape
            if (q.owner == null) q.packagePrefix?.let { add("packagePrefix" to q.copy(packagePrefix = null)) }
            s.params?.let { add("params" to q.copy(shape = s.copy(params = null))) }
            s.returns?.let { add("returns" to q.copy(shape = s.copy(returns = null))) }
            s.isStatic?.let { add("isStatic" to q.copy(shape = s.copy(isStatic = null))) }
            s.strings.forEachIndexed { i, str -> add("strings[\"$str\"]" to q.copy(shape = s.copy(strings = s.strings.without(i)))) }
            s.numbers.forEachIndexed { i, n -> add("numbers[$n]" to q.copy(shape = s.copy(numbers = s.numbers.without(i)))) }
            s.annotations.forEachIndexed { i, a -> add("annotations[\"$a\"]" to q.copy(shape = s.copy(annotations = s.annotations.without(i)))) }
            if (s.opcodes.isNotEmpty()) add("opcodes" to q.copy(shape = s.copy(opcodes = emptyList())))
            q.calls.forEachIndexed { i, m -> add("calls[${m.id}]" to q.copy(calls = q.calls.without(i))) }
            q.callsExact.forEachIndexed { i, c -> add("callsExact[$c]" to q.copy(callsExact = q.callsExact.without(i))) }
            q.usesFields.forEachIndexed { i, f -> add("usesFields[${f.id}]" to q.copy(usesFields = q.usesFields.without(i))) }
            if (q.onlyTheseFields) add("onlyTheseFields" to q.copy(onlyTheseFields = false))
            q.excludes.forEachIndexed { i, m -> add("excludes[${m.id}]" to q.copy(excludes = q.excludes.without(i))) }
            q.calledBy.forEachIndexed { i, m -> add("calledBy[${m.id}]" to q.copy(calledBy = q.calledBy.without(i))) }
        }

        private fun fieldVariants(q: FieldQuery): List<Pair<String, Any>> = buildList {
            q.type?.let { add("type" to q.copy(type = null)) }
            q.isStatic?.let { add("isStatic" to q.copy(isStatic = null)) }
            q.accessedIn.forEachIndexed { i, m -> add("accessedIn[${m.id}]" to q.copy(accessedIn = q.accessedIn.without(i))) }
        }
    }
}

/**
 * 已知版本的余量报告（例如生成成 `symbols/margin.json`）：每个版本里单点依赖的查询和它的关键条件。
 * 只是提示，不影响解析；新版本缺符号时，先看是不是这些查询先坏。
 */
@kotlinx.serialization.Serializable
data class MarginReport(
    /** 生成时的目录指纹（[io.github.wzgrx.dexadapt.Catalog.fingerprint]）。 */
    val catalog: String,
    /** 版本号 → (符号 id → 去掉后不再唯一的条件，形如 `type→7`)。 */
    val builds: Map<String, Map<String, List<String>>>,
) {
    /** 任一内置版本里单点依赖的查询。 */
    val singlePoint: Set<String> get() = builds.values.flatMapTo(sortedSetOf()) { it.keys }

    fun toJson(): String = json.encodeToString(serializer(), this)

    companion object {
        const val RESOURCE = "symbols/margin.json"
        private val json = kotlinx.serialization.json.Json { prettyPrint = true; prettyPrintIndent = "  "; ignoreUnknownKeys = true }

        fun fromJson(text: String): MarginReport = json.decodeFromString(serializer(), text)

        fun of(catalog: String, margins: Map<Long, List<SymbolMargin>>) = MarginReport(
            catalog,
            margins.entries.sortedBy { it.key }.associate { (vc, list) ->
                vc.toString() to list.filter { it.singlePoint }.sortedBy { it.id }.associate { m -> m.id to m.critical.map { it.toString() } }
            },
        )
    }
}
