package io.github.wzgrx.dexadapt.resolve

import io.github.wzgrx.dexadapt.ClassQuery
import io.github.wzgrx.dexadapt.ClassSymbol
import io.github.wzgrx.dexadapt.FieldSymbol
import io.github.wzgrx.dexadapt.FieldQuery
import io.github.wzgrx.dexadapt.MethodQuery
import io.github.wzgrx.dexadapt.MethodShape
import io.github.wzgrx.dexadapt.MethodSymbol
import io.github.wzgrx.dexadapt.Pin
import io.github.wzgrx.dexadapt.Resolved
import io.github.wzgrx.dexadapt.Symbol
import io.github.wzgrx.dexadapt.SymbolTable
import io.github.wzgrx.dexadapt.TypeRef

/**
 * 按查询在一个 APK（[source]）里找符号：只有恰好一个成员匹配才算找到。
 * [pinFor] 给出某个已知版本手工钉死的位置（本机适配的新版本没有，返回 null 就按查询找）；
 * [skip] 为真的符号不找（已知版本里确认没有这个功能），依赖它的符号也跟着找不到，不会误命中别的成员。
 *
 * 几条防误判的规则（2026-10-09 审查 R3–R7）：
 *  - 钉死的位置也要符合查询里能核对的条件（返回值、静态、参数、字段类型），不只是“存在”；
 *  - 方法查询命中多个时，去掉编译器生成的桥接 / 合成方法（`invoke(Object)`、`$default`、`access$`）再看是否唯一；
 *  - `callsExact` 先按写明的类找，一个都没有时再认“调用的是它子类上的同名方法”（R8 把引用改到子类上）；
 *  - 依赖成环、引用了后声明的符号（列表里是 null）时给出看得懂的错误，不再栈溢出或空指针。
 */
class SymbolResolver internal constructor(
    private val source: SymbolSource,
    private val versionCode: Long,
    private val pinFor: (Symbol) -> Pin?,
    private val skip: (Symbol) -> Boolean,
    private val log: (String) -> Unit,
    private val build: String?,
) {
    constructor(
        source: SymbolSource,
        versionCode: Long,
        pinFor: (Symbol) -> Pin? = { null },
        skip: (Symbol) -> Boolean = { false },
        log: (String) -> Unit = {},
    ) : this(source, versionCode, pinFor, skip, log, null)

    private val done = HashMap<String, Result<Resolved>>()
    /** 正在解析的符号（依赖链），用来发现成环。 */
    private val resolving = LinkedHashSet<String>()

    class Unresolved(message: String, val dependencyId: String? = null, cause: Throwable? = null) : Exception(message, cause)

    private fun blockedBy(id: String, cause: Throwable): Unresolved =
        Unresolved("depends on $id: ${cause.message}", id, cause)

    companion object {
        /** 标了 [io.github.wzgrx.dexadapt.absentIn] 的符号在这个构建里的“解析失败”原因。 */
        const val ABSENT = "absent in this build (absentIn)"

        /** 超过这么久的符号记一笔，方便找出手机上慢的查询。 */
        private const val SLOW_MS = 1500L
    }

    fun table(symbols: List<Symbol>, onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }): SymbolTable =
        tableWithVerifiedEntries(symbols, emptyMap(), onProgress)

    /**
     * 桌面增量解析。调用方必须保证复用项来自相同的代码、构建、解析器和完整递归查询指纹，
     * 只传成功解析的项。使用一个全新的解析器；失败与缺省仍按当前目录处理。
     */
    fun tableWithVerifiedEntries(
        symbols: List<Symbol>,
        verifiedEntries: Map<String, Resolved>,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): SymbolTable {
        if (verifiedEntries.isNotEmpty()) {
            check(done.isEmpty() && resolving.isEmpty()) { "incremental resolution needs a fresh resolver" }
            val byId = symbols.associateBy { it.id }
            for ((id, value) in verifiedEntries) {
                val symbol = byId[id]
                require(symbol != null && !skip(symbol) && when (symbol) {
                    is ClassSymbol -> value is Resolved.Class
                    is FieldSymbol -> value is Resolved.Field
                    is MethodSymbol -> value is Resolved.Method
                }) { "invalid verified entry: $id" }
            }
            verifiedEntries.forEach { (id, value) -> done[id] = Result.success(value) }
        }
        val entries = sortedMapOf<String, Resolved>()
        val missing = sortedMapOf<String, String>()
        source.prefetchStrings(queryStrings(symbols.filter { !skip(it) && it.id !in verifiedEntries }))
        val absent = symbols.filter(skip).map { it.id }.distinct().sorted()
        symbols.forEachIndexed { i, s ->
            val started = System.nanoTime()
            if (!skip(s)) resolve(s).fold({ entries[s.id] = it }, { missing[s.id] = it.message ?: it.toString() })
            val ms = (System.nanoTime() - started) / 1_000_000
            if (ms >= SLOW_MS) log("  ${s.id}: ${ms}ms")
            onProgress(i + 1, symbols.size)
        }
        return SymbolTable(versionCode, source.dexSha256, entries, missing, absent)
    }

    /**
     * 按需解析（设计 10.8）：按 [stages] 的顺序一批批解析（例如先解析开着的功能要用的符号），每批解析完交出一张
     * 到目前为止的部分表（[SymbolTable.partial]），最后返回完整表。依赖只解析一次（后面的批次直接用结果）。
     *
     * 使用前提：拿到部分表的一方（宿主进程）不能在运行中途才去用部分表里还没有的符号，否则会在完整表到来之前报“未适配”；
     * 适合功能在安装时就取齐全部符号、装不上会在换表后重试的运行时。
     */
    fun tableInStages(stages: List<List<Symbol>>, onStage: (SymbolTable) -> Unit, onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }): SymbolTable {
        val all = stages.flatten().distinctBy { it.id }
        val entries = sortedMapOf<String, Resolved>()
        val missing = sortedMapOf<String, String>()
        source.prefetchStrings(queryStrings(all.filterNot(skip)))
        val absent = all.filter(skip).map { it.id }.sorted()
        var count = 0
        stages.forEachIndexed { i, stage ->
            for (s in stage) {
                if (s.id in entries || s.id in missing) continue
                if (!skip(s)) resolve(s).fold({ entries[s.id] = it }, { missing[s.id] = it.message ?: it.toString() })
                onProgress(++count, all.size)
            }
            if (i < stages.lastIndex) onStage(SymbolTable(versionCode, source.dexSha256, entries.toSortedMap(), missing.toSortedMap(), absent, partial = true))
        }
        return SymbolTable(versionCode, source.dexSha256, entries, missing, absent)
    }

    /** 查询里用来找起点的字符串（类查询的 strings、方法查询的 shape.strings）；类查询里方法形状的字符串只用来核对，不预取。 */
    private fun queryStrings(symbols: List<Symbol>): Set<String> = symbols.flatMapTo(LinkedHashSet()) { s ->
        when (s) {
            is ClassSymbol -> if (s.query.name == null) s.query.strings else emptyList()
            is MethodSymbol -> if (s.query.owner == null) s.query.shapesFor(build, source.dexSha256).flatMap { it.second.strings } else emptyList()
            is FieldSymbol -> emptyList()
        }
    }

    fun resolve(symbol: Symbol): Result<Resolved> {
        done[symbol.id]?.let { return it }
        if (symbol.id in resolving) {
            // 成环：不缓存，交给链上最外层的符号记成失败。
            return Result.failure(Unresolved("dependency cycle: " + (resolving.dropWhile { it != symbol.id } + symbol.id).joinToString(" -> ")))
        }
        resolving += symbol.id
        val result = try {
            runCatching {
                if (skip(symbol)) throw Unresolved(ABSENT)
                SymbolGraph.nullReference(symbol)?.let { throw Unresolved(it) }
                when (val pin = pinFor(symbol)) {
                    null -> when (symbol) {
                        is ClassSymbol -> Resolved.Class(single(symbol, findClasses(symbol.query)).name)
                        is MethodSymbol -> resolveMethod(symbol).let { Resolved.Method(it.owner, it.name, it.params, it.returns) }
                        is FieldSymbol -> single(symbol, findFields(symbol.query)).let { Resolved.Field(it.owner, it.name, it.type) }
                    }
                    else -> checkPin(symbol, pin)
                }
            }
        } finally {
            resolving -= symbol.id
        }
        // 成环时链上的中间符号不缓存（它们的失败原因取决于从哪个符号进来）。
        if (resolving.isEmpty() || result.isSuccess || (result.exceptionOrNull()?.message?.contains("dependency cycle") != true)) done[symbol.id] = result
        return result
    }

    /** 分级回退（设计 10.8）推测出的位置和推测方法。 */
    data class Guess(val resolved: Resolved, val how: String)

    /**
     * [symbol] 按查询解析不到时推测位置（只推测、不当真）：
     * 1. 逐个去掉一个条件（先去形状条件，最后才去字符串、调用关系这些锚），去掉后唯一的；
     * 2. [previous]（别的版本解析出的位置）在这个安装包里原样还在的。
     * 都没有返回 null。结果放进 [SymbolTable.guesses]，运行时只在有契约、观察通过后才用（见 SymbolLinker）。
     */
    fun guess(symbol: Symbol, previous: List<Resolved> = emptyList()): Guess? {
        if (skip(symbol) || pinFor(symbol) != null || resolve(symbol).isSuccess) return null
        // 不放宽多签名查询：这可能把签名间的冲突误当成可恢复的旧位置。
        if (symbol is MethodSymbol && symbol.query.variants.isNotEmpty()) return null
        val variants = SymbolMargin.variants(symbol).sortedBy { (label, _) -> if (SymbolMargin.isAnchor(label)) 1 else 0 }
        for ((label, q) in variants) {
            val found: List<Resolved> = runCatching {
                when (q) {
                    is ClassQuery -> findClasses(q).map { Resolved.Class(it.name) }
                    is MethodQuery ->
                        if (q.owner == null && q.shape.strings.isEmpty() && q.packagePrefix == null && q.calledBy.isEmpty()) emptyList()
                        else preferReal(findMethods(q)).map { Resolved.Method(it.owner, it.name, it.params, it.returns) }
                    is FieldQuery -> findFields(q).map { Resolved.Field(it.owner, it.name, it.type) }
                    else -> emptyList()
                }
            }.getOrDefault(emptyList())
            if (found.size == 1) return Guess(found[0], "去掉条件 $label 后唯一")
        }
        previous.distinct().firstOrNull(::exists)?.let { return Guess(it, "别的版本解析出的位置在这个安装包里还在") }
        return null
    }

    /** 给 [table] 里解析不到的符号做推测（[previous] 给出某个符号在别的版本的位置）。 */
    fun withGuesses(table: SymbolTable, symbols: List<Symbol>, previous: (String) -> List<Resolved> = { emptyList() }): SymbolTable {
        val guesses = sortedMapOf<String, SymbolTable.Guessed>()
        for (s in symbols) {
            if (s.id !in table.missing) continue
            guess(s, previous(s.id))?.let { g -> guesses[s.id] = SymbolTable.Guessed(g.resolved, g.how) }
        }
        return if (guesses.isEmpty()) table else table.copy(guesses = guesses)
    }

    private fun exists(r: Resolved): Boolean = when (r) {
        is Resolved.Class -> source.classByName(r.name) != null
        is Resolved.Method -> source.classByName(r.owner)?.methods?.any { it.name == r.name && it.params == r.params && it.returns == r.returns } == true
        is Resolved.Field -> source.classByName(r.owner)?.fields?.any { it.name == r.name && it.type == r.type } == true
    }

    /**
     * [symbol] 的余量（见 [SymbolMargin]）：逐个去掉一个条件后的候选数。钉死、跳过、没解析出来、按类名找的符号
     * 返回 null。只在电脑上用（去掉起点条件后会扫全部类）。
     */
    fun margin(symbol: Symbol): SymbolMargin? {
        if (pinFor(symbol) != null || skip(symbol) || resolve(symbol).isFailure) return null
        val variants = SymbolMargin.marginVariants(symbol)
        if (variants.isEmpty()) return null
        return SymbolMargin(symbol.id, variants.map { (label, q) -> SymbolMargin.Drop(label, count(q)) })
    }

    private fun count(q: Any): Int = runCatching {
        when (q) {
            is ClassQuery -> findClasses(q, allowScan = true).size
            is MethodQuery ->
                if (q.owner == null && q.packagePrefix == null && q.calledBy.isEmpty() &&
                    q.shapesFor(build, source.dexSha256).any { it.second.strings.isEmpty() }) SymbolMargin.UNBOUNDED
                else methodMatches(q).flatMap { it.second }.distinctBy { it.signature }.size
            is FieldQuery -> findFields(q).size
            else -> SymbolMargin.UNKNOWN
        }
    }.getOrDefault(SymbolMargin.UNKNOWN)

    /** 多个命中时去掉桥接 / 合成方法；只剩一个就用它，否则原样交给 [single] 报“多个命中”。 */
    private fun preferReal(found: List<MethodView>): List<MethodView> {
        if (found.size <= 1) return found
        val real = found.filter { it.flags and (AccessFlag.BRIDGE or AccessFlag.SYNTHETIC) == 0 }
        return if (real.isEmpty()) found else real
    }

    private fun <T> single(symbol: Symbol, found: List<T>): T = when (found.size) {
        1 -> found[0]
        0 -> throw Unresolved("no match")
        else -> throw Unresolved("${found.size} matches: " + found.take(4).joinToString { describe(it) })
    }.also { if (found.size != 1) log("  ${symbol.id}: ${found.size} matches") }

    private fun describe(item: Any?): String = when (item) {
        is ClassView -> item.name
        is MethodView -> item.signature
        is FieldView -> "${item.owner}#${item.name}"
        else -> item.toString()
    }

    /**
     * 钉死的位置：成员必须存在，并且符合查询里不依赖“唯一”的条件（R4）——类要满足类查询，方法要满足形状
     * （名字、参数、返回值、静态）和所属类，字段要满足名字、类型、静态。钉子只用来在多个候选里选一个，
     * 不能把一个形状对不上的成员塞进来。
     */
    private fun checkPin(symbol: Symbol, pin: Pin): Resolved = when (pin) {
        is Pin.Class -> {
            val c = source.classByName(pin.name) ?: throw Unresolved("pinned class ${pin.name} missing")
            if (symbol is ClassSymbol && !matches(c, symbol.query.copy(name = null))) throw Unresolved("pinned class ${pin.name} does not match the query")
            Resolved.Class(c.name)
        }
        is Pin.Method -> {
            val candidates = source.classByName(pin.owner)?.methods.orEmpty().filter { it.name == pin.name && it.params == pin.params }
            if (candidates.isEmpty()) throw Unresolved("pinned method ${pin.owner}#${pin.name}${pin.params} missing")
            val m = if (symbol is MethodSymbol) {
                val q = symbol.query
                val ownerOk = q.owner?.let { typeName(it) == pin.owner } ?: (q.packagePrefix == null || pin.owner.startsWith(q.packagePrefix + "."))
                val shapes = q.shapesFor(build, source.dexSha256)
                if (q.variants.isNotEmpty()) shapes.forEach { (_, s) -> checkShapeDependencies(s) }
                val compatible = if (!ownerOk) emptyList() else candidates.filter { m -> shapes.any { (_, s) -> matches(m, s) } }
                if (compatible.isEmpty()) throw Unresolved("pinned method ${candidates.first().signature} does not match the query")
                single(symbol, preferReal(compatible))
            } else throw Unresolved("method pin requires a method symbol")
            Resolved.Method(m.owner, m.name, m.params, m.returns)
        }
        is Pin.Field -> {
            val f = source.classByName(pin.owner)?.fields?.firstOrNull { it.name == pin.name }
                ?: throw Unresolved("pinned field ${pin.owner}#${pin.name} missing")
            if (symbol is FieldSymbol && (typeName(symbol.query.owner) != f.owner || !fieldMatches(f, symbol.query))) throw Unresolved("pinned field ${f.owner}#${f.name} does not match the query")
            Resolved.Field(f.owner, f.name, f.type)
        }
    }

    private fun typeName(ref: TypeRef): String = when (ref) {
        TypeRef.Any -> throw Unresolved("TypeRef.Any is only allowed in method parameter lists")
        is TypeRef.Exact -> ref.name
        is TypeRef.Of -> (resolve(ref.symbol).getOrElse { throw blockedBy(ref.symbol.id, it) } as Resolved.Class).name
        is TypeRef.SuperOf -> {
            val child = typeName(TypeRef.Of(ref.symbol))
            source.classByName(child)?.superName ?: throw Unresolved("$child has no known superclass")
        }
    }

    private fun methodSignature(symbol: MethodSymbol): String {
        val m = resolve(symbol).getOrElse { throw blockedBy(symbol.id, it) } as Resolved.Method
        return MethodView.signature(m.owner, m.name, m.params, m.returns)
    }

    /** 已解析方法在数据源里的样子（要用它的调用、字段引用）。 */
    private fun methodView(symbol: MethodSymbol): MethodView {
        val m = resolve(symbol).getOrElse { throw blockedBy(symbol.id, it) } as Resolved.Method
        return source.classByName(m.owner)?.methods?.firstOrNull { it.name == m.name && it.params == m.params && it.returns == m.returns }
            ?: throw Unresolved("depends on ${symbol.id}: ${m.owner}#${m.name} not in the source", symbol.id)
    }

    /** [pattern] 按顺序出现在 [ops] 里（中间可以有别的）。 */
    private fun isSubsequence(pattern: List<String>, ops: List<String>): Boolean {
        var i = 0
        for (op in ops) if (i < pattern.size && op == pattern[i]) i++
        return i == pattern.size
    }

    /** [caller] 的方法体调用了 [m]（引用可能写在 m 所属类的子类上，R8 member rebinding）。 */
    private fun invokes(caller: MethodView, m: MethodView): Boolean {
        if (m.signature in caller.calls) return true
        val rest = m.signature.substring(m.owner.length)
        return caller.calls.any { it.endsWith(rest) && isSubclassOf(it.dropLast(rest.length), m.owner) }
    }

    private fun fieldKey(symbol: FieldSymbol): String {
        val f = resolve(symbol).getOrElse { throw blockedBy(symbol.id, it) } as Resolved.Field
        return "${f.owner}#${f.name}"
    }

    /** 同时用到 [strings] 全部字符串的方法（按签名求交集，两种数据源每次返回的对象不一定是同一个）。 */
    private fun methodsUsingAll(strings: List<String>): Collection<MethodView> {
        var acc: LinkedHashMap<String, MethodView>? = null
        for (s in strings) {
            val next = LinkedHashMap<String, MethodView>()
            source.methodsUsingString(s).forEach { m -> if (acc == null || m.signature in acc) next[m.signature] = m }
            acc = next
            if (next.isEmpty()) break
        }
        return acc?.values.orEmpty()
    }

    private fun findClasses(q: ClassQuery, allowScan: Boolean = false): List<ClassView> {
        q.name?.let { name -> return listOfNotNull(source.classByName(name)).filter { matches(it, q) } }
        val candidates: Sequence<ClassView> = if (q.declares.isNotEmpty()) {
            sequenceOf(methodView(q.declares.first()).owner).mapNotNull { source.classByName(it) }
        } else if (q.instantiatedIn.isNotEmpty()) {
            methodView(q.instantiatedIn.first()).calls.asSequence()
                .filter { it.substringAfter('#').startsWith("<init>(") }
                .map { it.substringBefore('#') }.distinct().mapNotNull { source.classByName(it) }
        } else if (q.fieldOf != null) {
            val holder = typeName(q.fieldOf)
            val owner = source.classByName(holder) ?: throw Unresolved("field holder $holder missing")
            owner.fields.asSequence().map { it.type }.distinct().mapNotNull { source.classByName(it) }
        } else if (q.strings.isNotEmpty()) {
            q.strings.map { s -> source.methodsUsingString(s).mapTo(HashSet()) { it.owner } }
                .reduce { a, b -> a.apply { retainAll(b) } }
                .asSequence().mapNotNull { source.classByName(it) }
        } else if (q.samePackageAs != null) {
            source.classesInExactPackage(typeName(q.samePackageAs).substringBeforeLast('.'))
        } else {
            require(allowScan || q.packagePrefix != null || q.superclass != null || q.fieldNames.isNotEmpty() || q.methods.isNotEmpty() || q.fieldTypes.isNotEmpty()) {
                "class query needs a name, strings, package, superclass, field names or methods"
            }
            source.candidateClasses(q, ::typeName)
        }
        return candidates.filter { matches(it, q) }.distinctBy { it.name }.toList()
    }

    private fun matches(c: ClassView, q: ClassQuery): Boolean {
        q.packagePrefix?.let { if (!c.name.startsWith("$it.")) return false }
        q.samePackageAs?.let { if (c.name.substringBeforeLast('.') != typeName(it).substringBeforeLast('.')) return false }
        q.fieldOf?.let { holder -> if (source.classByName(typeName(holder))?.fields?.none { it.type == c.name } != false) return false }
        if (q.isInterface != null && c.isInterface != q.isInterface) return false
        if (q.isAbstract != null && c.isAbstract != q.isAbstract) return false
        q.superclass?.let { if (c.superName != typeName(it)) return false }
        if (q.interfaces.any { typeName(it) !in c.interfaces }) return false
        if (q.fieldTypes.isNotEmpty()) {
            val types = c.fields.mapTo(HashSet()) { it.type }
            if (q.fieldTypes.any { typeName(it) !in types }) return false
        }
        if (q.fieldNames.isNotEmpty()) {
            val names = c.fields.mapTo(HashSet()) { it.name }
            if (!names.containsAll(q.fieldNames)) return false
        }
        if (q.strings.isNotEmpty()) {
            val all = c.methods.flatMapTo(HashSet()) { it.strings }
            if (!all.containsAll(q.strings)) return false
        }
        if (!q.methods.all { shape -> c.methods.any { matches(it, shape) } }) return false
        if (!q.declares.all { methodView(it).owner == c.name }) return false
        q.sourceFile?.let { if (c.sourceFile != it) return false }
        if (q.annotations.isNotEmpty() && !q.annotations.all { a -> c.annotations.any { it.contains(a) } }) return false
        return q.instantiatedIn.all { m -> methodView(m).calls.any { it.startsWith(c.name + "#<init>(") } }
    }

    private fun matches(m: MethodView, s: MethodShape): Boolean {
        if (s.name != null && m.name != s.name) return false
        if (s.isStatic != null && m.isStatic != s.isStatic) return false
        s.params?.let { p ->
            if (m.params.size != p.size) return false
            if (p.indices.any { i -> p[i] != TypeRef.Any && m.params[i] != typeName(p[i]) }) return false
        }
        s.returns?.let { if (m.returns != typeName(it)) return false }
        if (!m.strings.containsAll(s.strings)) return false
        if (s.numbers.isNotEmpty() && !m.numbers.containsAll(s.numbers)) return false
        if (s.annotations.isNotEmpty() && !s.annotations.all { a -> m.annotations.any { it.contains(a) } }) return false
        return s.opcodes.isEmpty() || isSubsequence(s.opcodes, m.opcodes)
    }

    private fun checkShapeDependencies(s: MethodShape) {
        s.params?.filterNot { it == TypeRef.Any }?.forEach(::typeName)
        s.returns?.let(::typeName)
    }

    private fun methodMatches(q: MethodQuery): List<Pair<String, List<MethodView>>> =
        q.shapesFor(build, source.dexSha256).map { (label, shape) ->
            if (q.variants.isNotEmpty()) checkShapeDependencies(shape)
            label to preferReal(findMethods(q.copy(shape = shape, variants = emptyList())))
        }

    private fun resolveMethod(symbol: MethodSymbol): MethodView {
        val q = symbol.query
        if (q.variants.isEmpty()) return single(symbol, preferReal(findMethods(q)))
        val branches = methodMatches(q)
        val found = branches.flatMap { it.second }.distinctBy { it.signature }.sortedBy { it.signature }
        if (found.size == 1) return found.single()
        val summary = branches.joinToString("; ") { (label, methods) ->
            "$label=${methods.size}" + methods.map { it.signature }.sorted().take(4)
                .joinToString(prefix = "[", postfix = "]")
        }
        val reason = if (found.isEmpty()) "no match" else "${found.size} matches"
        throw Unresolved("$reason across method shapes: $summary")
    }

    private fun findMethods(q: MethodQuery): List<MethodView> {
        val owner = q.owner
        val prefix = q.packagePrefix
        val calls = q.calls.map(::methodSignature)
        val fields = q.usesFields.map(::fieldKey)
        val excluded = q.excludes.mapTo(HashSet(), ::methodSignature)
        val callers = q.calledBy.map(::methodView)
        val candidates: Sequence<MethodView> = when {
            owner != null -> source.classByName(typeName(owner))?.methods?.asSequence() ?: throw Unresolved("owner ${typeName(owner)} missing")
            q.shape.strings.isNotEmpty() -> methodsUsingAll(q.shape.strings).asSequence()
            prefix != null -> source.candidateMethodsInPackage(prefix, q, ::typeName, calls, fields)
            callers.isNotEmpty() -> callers.first().calls.asSequence().mapNotNull { sig ->
                val cls = source.classByName(sig.substringBefore('#')) ?: return@mapNotNull null
                cls.methods.firstOrNull { it.signature == sig }
            }
            else -> throw Unresolved("method query needs an owner, strings, package or callers")
        }
        return candidates.filter { m ->
            (q.packagePrefix == null || m.owner.startsWith(q.packagePrefix + ".")) &&
                m.signature !in excluded &&
                matches(m, q.shape) &&
                m.calls.containsAll(calls) &&
                m.fieldRefs.containsAll(fields) &&
                (!q.onlyTheseFields || m.fieldRefs.size == fields.toSet().size) &&
                callers.all { invokes(it, m) }
        }.distinctBy { it.signature }.toList().let { found ->
            if (q.callsExact.isEmpty()) return@let found
            // 先按写明的类找；一个都没有时，再认调用到它子类上的同名方法（R8 member rebinding，R6）。
            found.filter { m -> q.callsExact.all { exact -> m.calls.any { it.startsWith("$exact(") } } }
                .ifEmpty { found.filter { m -> q.callsExact.all { exact -> m.calls.any { callsViaSubclass(it, exact) } } } }
        }
    }

    /** [call]（`owner#name(…)…`）是不是 [exact]（`owner#name`）那个方法、只是引用写在了 owner 的子类上。 */
    private fun callsViaSubclass(call: String, exact: String): Boolean {
        val owner = exact.substringBefore('#')
        val name = exact.substringAfter('#')
        val callOwner = call.substringBefore('#')
        if (call.substring(callOwner.length + 1).substringBefore('(') != name) return false
        return callOwner == owner || isSubclassOf(callOwner, owner)
    }

    private fun isSubclassOf(child: String, ancestor: String): Boolean {
        var cur: String? = child
        val seen = HashSet<String>()
        while (cur != null && seen.add(cur)) {
            val c = source.classByName(cur) ?: return false
            if (c.superName == ancestor || ancestor in c.interfaces) return true
            if (c.interfaces.any { isSubclassOf(it, ancestor) }) return true
            cur = c.superName
        }
        return false
    }

    private fun findFields(q: FieldQuery): List<FieldView> {
        val owner = source.classByName(typeName(q.owner)) ?: throw Unresolved("owner ${typeName(q.owner)} missing")
        return owner.fields.filter { fieldMatches(it, q) }
    }

    private fun fieldMatches(f: FieldView, q: FieldQuery): Boolean =
        (q.name == null || f.name == q.name) &&
            (q.type?.let { f.type == typeName(it) } ?: true) &&
            (q.isStatic == null || f.isStatic == q.isStatic) &&
            q.accessedIn.all { m -> "${f.owner}#${f.name}" in methodView(m).fieldRefs }
}

/**
 * 解析一个构建的常用写法：[build] 是已知构建的标识时用它的钉子（[io.github.wzgrx.dexadapt.Symbol.pins]）和
 * “没有”的标注（[io.github.wzgrx.dexadapt.Symbol.absentIn]）；为 null 时按未知版本解析（本机适配的做法：只看查询）。
 */
fun SymbolResolver.Companion.forBuild(source: SymbolSource, versionCode: Long, build: String?, log: (String) -> Unit = {}): SymbolResolver =
    if (build == null) SymbolResolver(source, versionCode, log = log)
    else SymbolResolver(source, versionCode, pinFor = { it.pins[build] }, skip = { build in it.absentIn }, log = log, build = build)

/** 每条查询的余量（逐个去掉一个条件后的候选数）；钉死、跳过、没解析出来、按类名找的不算。 */
fun SymbolResolver.margins(symbols: List<io.github.wzgrx.dexadapt.Symbol>): List<SymbolMargin> = symbols.mapNotNull(::margin)
