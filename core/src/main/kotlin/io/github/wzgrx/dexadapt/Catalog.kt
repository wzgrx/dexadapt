package io.github.wzgrx.dexadapt

import io.github.wzgrx.dexadapt.resolve.SymbolGraph
import java.security.MessageDigest

/**
 * 一组相关符号的定义。id 是 `<组>.<名字>`，在整个目录里唯一。
 *
 * ```
 * object SplashSymbols : SymbolGroup("splash") {
 *     val activity = cls("activity", ClassQuery(name = "com.example.SplashActivity"))
 * }
 * ```
 *
 * 同一个组里只能引用**先声明**的符号（Kotlin 按顺序初始化，后声明的拿到的是 null）；[SymbolGraph.problems] 会查出来。
 */
abstract class SymbolGroup(val group: String) {
    private val own = mutableListOf<Symbol>()
    val symbols: List<Symbol> get() = own

    protected fun cls(name: String, query: ClassQuery) = ClassSymbol("$group.$name", query).also(own::add)
    protected fun method(name: String, query: MethodQuery) = MethodSymbol("$group.$name", query).also(own::add)
    protected fun field(name: String, query: FieldQuery) = FieldSymbol("$group.$name", query).also(own::add)
}

/**
 * 一个模块的全部符号（目录）。[fingerprint] 是全部定义（id、查询、钉死的位置、“某版本没有”）的摘要：
 * 本机适配生成的表记下它，模块更新改了查询，旧表就算过期、要重扫。
 */
class Catalog(val symbols: List<Symbol>) {
    constructor(vararg groups: SymbolGroup) : this(groups.flatMap { it.symbols })

    init {
        val dupes = symbols.groupBy { it.id }.filterValues { it.size > 1 }.keys
        require(dupes.isEmpty()) { "duplicate symbol ids: $dupes" }
    }

    /** 依赖图的问题（前向引用、引用了目录外的符号、成环）；空表示没问题。 */
    fun problems(): List<String> = SymbolGraph.problems(symbols)

    /**
     * 目录指纹（16 位十六进制）。[engine] 是会影响解析结果、但不在查询里的东西（解析器版本、解析代码摘要、DexKit 版本……），
     * 由使用方拼好传进来；变了旧表同样过期。
     */
    fun fingerprint(engine: String = ""): String {
        val text = engine + symbols.joinToString("\n") { s ->
            val query = when (s) {
                is ClassSymbol -> s.query.toString()
                is MethodSymbol -> s.query.toString()
                is FieldSymbol -> s.query.toString()
            }
            "${s.id}|$query|${s.pins.entries.sortedBy { it.key }}|${s.absentIn.sorted()}"
        }
        return Hex.of(MessageDigest.getInstance("SHA-256").digest(text.toByteArray())).take(16)
    }
}
