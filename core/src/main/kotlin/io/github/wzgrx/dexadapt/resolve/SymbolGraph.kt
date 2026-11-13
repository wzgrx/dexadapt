package io.github.wzgrx.dexadapt.resolve

import io.github.wzgrx.dexadapt.ClassSymbol
import io.github.wzgrx.dexadapt.FieldSymbol
import io.github.wzgrx.dexadapt.MethodShape
import io.github.wzgrx.dexadapt.MethodSymbol
import io.github.wzgrx.dexadapt.Symbol
import io.github.wzgrx.dexadapt.TypeRef

/**
 * 符号之间的依赖（查询里引用的其它符号），以及目录的结构检查（R7）：
 *  - 引用了同一个 SymbolGroup 里**后声明**的符号时，Kotlin 初始化到这里拿到的是 null（`calls`、`usesFields`、
 *    `excludes` 这些列表不会报错），解析时会变成看不懂的空指针；
 *  - 依赖成环会无限递归。
 * 解析器遇到这两种情况给出明确的错误；[problems] 在单测里对整个目录检查一遍，不需要 APK。
 */
object SymbolGraph {
    /** [symbol] 的查询直接引用的符号；列表里的 null 表示前向引用。 */
    fun dependencies(symbol: Symbol): List<Pair<String, Symbol?>> = buildList {
        fun ref(where: String, t: TypeRef?) {
            when (t) {
                is TypeRef.Of -> add(where to (t.symbol as Symbol?))
                is TypeRef.SuperOf -> add(where to (t.symbol as Symbol?))
                else -> {}
            }
        }
        fun shape(where: String, s: MethodShape) {
            s.params?.forEachIndexed { i, p -> ref("$where.params[$i]", p) }
            ref("$where.returns", s.returns)
        }
        when (symbol) {
            is ClassSymbol -> with(symbol.query) {
                ref("superclass", superclass)
                interfaces.forEachIndexed { i, t -> ref("interfaces[$i]", t) }
                fieldTypes.forEachIndexed { i, t -> ref("fieldTypes[$i]", t) }
                methods.forEachIndexed { i, m -> shape("methods[$i]", m) }
                ref("samePackageAs", samePackageAs)
                ref("fieldOf", fieldOf)
                instantiatedIn.forEachIndexed { i, m -> add("instantiatedIn[$i]" to (m as Symbol?)) }
                declares.forEachIndexed { i, m -> add("declares[$i]" to (m as Symbol?)) }
            }
            is MethodSymbol -> with(symbol.query) {
                ref("owner", owner)
                shape("shape", shape)
                variants.forEachIndexed { i, v -> shape("variants[$i]", v.shape) }
                calls.forEachIndexed { i, m -> add("calls[$i]" to (m as Symbol?)) }
                usesFields.forEachIndexed { i, f -> add("usesFields[$i]" to (f as Symbol?)) }
                excludes.forEachIndexed { i, m -> add("excludes[$i]" to (m as Symbol?)) }
                calledBy.forEachIndexed { i, m -> add("calledBy[$i]" to (m as Symbol?)) }
            }
            is FieldSymbol -> with(symbol.query) {
                ref("owner", owner)
                ref("type", type)
                accessedIn.forEachIndexed { i, m -> add("accessedIn[$i]" to (m as Symbol?)) }
            }
        }
    }

    /** 第一个前向引用（null）的说明；没有为 null。 */
    fun nullReference(symbol: Symbol): String? = dependencies(symbol).firstOrNull { it.second == null }?.let { (where, _) ->
        "forward reference: ${symbol.id} uses a symbol declared later in its group ($where is null)"
    }

    /** 整个目录的问题：前向引用、引用了不在目录里的符号、依赖成环。空列表表示没问题。 */
    fun problems(symbols: List<Symbol>): List<String> {
        val out = ArrayList<String>()
        val ids = symbols.mapTo(HashSet()) { it.id }
        for (s in symbols) {
            nullReference(s)?.let { out += it }
            dependencies(s).forEach { (where, dep) ->
                if (dep != null && dep.id !in ids) out += "${s.id}.$where refers to ${dep.id}, which is not in the catalog"
            }
        }
        // 深度优先找环：0 未访问，1 在栈上，2 已完成。
        val state = HashMap<String, Int>()
        val byId = symbols.associateBy { it.id }
        val stack = ArrayList<String>()
        fun visit(s: Symbol) {
            when (state[s.id]) {
                2 -> return
                1 -> {
                    out += "dependency cycle: " + (stack.dropWhile { it != s.id } + s.id).joinToString(" -> ")
                    return
                }
            }
            state[s.id] = 1
            stack += s.id
            dependencies(s).forEach { (_, dep) -> if (dep != null) visit(byId[dep.id] ?: dep) }
            stack.removeAt(stack.size - 1)
            state[s.id] = 2
        }
        symbols.forEach(::visit)
        return out
    }
}
