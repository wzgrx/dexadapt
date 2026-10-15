package io.github.wzgrx.dexadapt.codegen

import io.github.wzgrx.dexadapt.Catalog
import io.github.wzgrx.dexadapt.ClassSymbol
import io.github.wzgrx.dexadapt.FieldSymbol
import io.github.wzgrx.dexadapt.MethodSymbol
import io.github.wzgrx.dexadapt.Symbol
import io.github.wzgrx.dexadapt.TypeRef

/**
 * 从目录生成类型化的访问器（Kotlin 源码）：模块里不再按名字反射（`Reflect.get(feed, "poi")`，名字一变就静默拿到 null），
 * 而是调用 `access.note.poi(feed)`——字段、方法都在目录里，版本矩阵会验证每个版本都找得到；运行时找不到时抛
 * `SymbolUnavailable`（说明哪个符号、为什么），`xxxOrNull` 版本返回 null。
 *
 * 类型：查询里写死的类型（`TypeRef.Exact`）在每个版本都一样，能写成 Kotlin 类型的（基本类型、String、集合、android 的类）
 * 就生成带类型的签名；宿主自己的类、混淆的类一律 `Any?`。生成的类按组分成内部类：`access.<组>.<成员>`。
 */
object Accessors {
    data class Options(
        val packageName: String,
        val className: String = "HostAccess",
        /** 文件头注释里说明怎么重新生成。 */
        val regenerate: String = "dexadapt codegen",
    )

    fun kotlin(catalog: Catalog, options: Options): String = buildString {
        appendLine("// 由 DexAdapt 生成（${options.regenerate}），不要手改。")
        appendLine("@file:Suppress(\"unused\", \"RedundantSuppression\", \"UNCHECKED_CAST\", \"FunctionName\", \"PropertyName\")")
        appendLine()
        appendLine("package ${options.packageName}")
        appendLine()
        appendLine("import io.github.wzgrx.dexadapt.Catalog")
        appendLine("import io.github.wzgrx.dexadapt.ClassSymbol")
        appendLine("import io.github.wzgrx.dexadapt.FieldSymbol")
        appendLine("import io.github.wzgrx.dexadapt.MethodSymbol")
        appendLine("import io.github.wzgrx.dexadapt.runtime.SymbolLinker")
        appendLine("import java.lang.reflect.Constructor")
        appendLine("import java.lang.reflect.Field")
        appendLine("import java.lang.reflect.Method")
        appendLine("import java.lang.reflect.Modifier")
        appendLine()
        appendLine("/**")
        appendLine(" * 目录里 ${catalog.symbols.size} 个符号的类型化访问器。用不了的符号抛 SymbolUnavailable（原因同 SymbolLinker），")
        appendLine(" * `OrNull` 版本返回 null；字段的 `OrNull` 版本在对象不是字段所在类的实例时也返回 null（模型对象可能是别的类）。")
        appendLine(" */")
        appendLine("class ${options.className}(private val linker: SymbolLinker, catalog: Catalog) {")
        appendLine("    private val ids = catalog.symbols.associateBy { it.id }")
        appendLine()
        appendLine("    private fun c(id: String): Class<*> = linker.clazz(ids.getValue(id) as ClassSymbol)")
        appendLine("    private fun f(id: String): Field = linker.field(ids.getValue(id) as FieldSymbol)")
        appendLine("    private fun m(id: String): java.lang.reflect.Executable = linker.method(ids.getValue(id) as MethodSymbol)")
        appendLine()
        appendLine("    private fun read(id: String, target: Any?): Any? = f(id).get(target)")
        appendLine()
        appendLine("    private fun readOrNull(id: String, target: Any?): Any? {")
        appendLine("        val field = runCatching { f(id) }.getOrNull() ?: return null")
        appendLine("        if (!Modifier.isStatic(field.modifiers) && !field.declaringClass.isInstance(target)) return null")
        appendLine("        return runCatching { field.get(target) }.getOrNull()")
        appendLine("    }")
        appendLine()
        appendLine("    private fun call(id: String, receiver: Any?, args: Array<out Any?>): Any? = when (val e = m(id)) {")
        appendLine("        is Method -> try { e.invoke(receiver, *args) } catch (t: java.lang.reflect.InvocationTargetException) { throw t.targetException }")
        appendLine("        is Constructor<*> -> try { e.newInstance(*args) } catch (t: java.lang.reflect.InvocationTargetException) { throw t.targetException }")
        appendLine("        else -> error(\"\$id: \$e\")")
        appendLine("    }")
        val groups = catalog.symbols.groupBy { it.id.substringBefore('.') }
        for ((group, symbols) in groups) {
            appendLine()
            appendLine("    val ${ident(group)}: ${typeName(group)} = ${typeName(group)}()")
            appendLine()
            appendLine("    /** 组 `$group`。 */")
            appendLine("    inner class ${typeName(group)} {")
            val used = HashSet<String>()
            symbols.forEachIndexed { i, s ->
                if (i > 0) appendLine()
                // “class” 做属性名时 getter 是 getClass()，和 Object.getClass 冲突
                val base = ident(memberName(s.id.substringAfter('.', s.id))).trim('`').let { if (it == "class") "clazz" else it }
                member(s, unique(base, used))
            }
            appendLine("    }")
        }
        appendLine("}")
    }

    /** [raw] 是不带反引号的基名：自身是关键字时加反引号，派生的名字（xxxOrNull、setXxx）不是关键字。 */
    private fun StringBuilder.member(s: Symbol, raw: String) {
        val id = s.id
        val name = if (raw in KEYWORDS) "`$raw`" else raw
        when (s) {
            is ClassSymbol -> {
                appendLine("        /** `$id`：类。 */")
                appendLine("        val $name: Class<*> get() = c(\"$id\")")
                appendLine("        val ${raw}OrNull: Class<*>? get() = runCatching { c(\"$id\") }.getOrNull()")
            }
            is FieldSymbol -> {
                val t = s.query.type?.let(::kotlinType) ?: "Any?"
                val primitive = !t.endsWith("?")
                val desc = s.query.type?.let(::describe)?.let { "（$it）" } ?: ""
                appendLine("        /** `$id`：字段$desc。 */")
                appendLine("        fun $name(target: Any?): $t = read(\"$id\", target) as $t")
                appendLine("        fun ${raw}OrNull(target: Any?): ${if (primitive) "$t?" else t} = readOrNull(\"$id\", target) as ${if (primitive) "$t?" else t}")
                appendLine("        fun set${raw.replaceFirstChar(Char::uppercaseChar)}(target: Any?, value: $t) = f(\"$id\").set(target, value)")
            }
            is MethodSymbol -> {
                val shape = s.query.shape
                val ret = shape.returns?.let(::kotlinType) ?: "Any?"
                val ctor = shape.name == "<init>"
                val result = if (ctor) "Any" else if (ret == "Unit") "Unit" else ret
                val params = shape.params
                val desc = buildString {
                    append(if (ctor) "构造方法" else "方法")
                    params?.let { p -> append("（").append(p.joinToString(", ", transform = ::describe)).append("）") }
                    if (!ctor) shape.returns?.let { append(" → ").append(describe(it)) }
                }
                appendLine("        /** `$id`：$desc。${if (ctor) "" else "静态方法 receiver 传 null。"} */")
                val receiver = if (ctor) "" else "receiver: Any?, "
                val recv = if (ctor) "null" else "receiver"
                val cast = when (result) {
                    "Unit" -> ""
                    else -> " as $result"
                }
                if (params != null) {
                    val decl = params.mapIndexed { i, p -> "a$i: ${kotlinType(p).let { if (it == "Unit") "Any?" else it }}" }.joinToString(", ")
                    val pass = params.indices.joinToString(", ") { "a$it" }
                    val sig = (receiver + decl).trimEnd(',', ' ')
                    if (result == "Unit") {
                        appendLine("        fun $name($sig) { call(\"$id\", $recv, arrayOf<Any?>($pass)) }")
                    } else {
                        appendLine("        fun $name($sig): $result = call(\"$id\", $recv, arrayOf<Any?>($pass))$cast")
                    }
                } else {
                    val sig = "${receiver}vararg args: Any?"
                    if (result == "Unit") appendLine("        fun $name($sig) { call(\"$id\", $recv, args) }")
                    else appendLine("        fun $name($sig): $result = call(\"$id\", $recv, args)$cast")
                }
                appendLine("        val ${raw}Available: Boolean get() = linker.available(ids.getValue(\"$id\"))")
            }
        }
    }

    /** 查询里的类型 → Kotlin 类型（写不出来的一律 Any?）。 */
    fun kotlinType(t: TypeRef): String {
        if (t !is TypeRef.Exact) return "Any?"
        val name = t.name
        PRIMITIVES[name]?.let { return it }
        BOXED[name]?.let { return "$it?" }
        if (name.endsWith("[]")) return "Any?"
        GENERIC[name]?.let { return "$it?" }
        if ('$' in name) return "Any?"
        if (name.startsWith("android.") && name.substringAfterLast('.') !in ANDROID_GENERIC) return "$name?"
        if (name in JAVA_PLAIN) return "$name?"
        return "Any?"
    }

    private fun describe(t: TypeRef): String = when (t) {
        is TypeRef.Exact -> t.name
        is TypeRef.Of -> "$" + t.symbol.id
        is TypeRef.SuperOf -> "super($" + t.symbol.id + ")"
        TypeRef.Any -> "*"
    }

    private fun memberName(path: String): String =
        path.split('.', '_', '-').filter { it.isNotEmpty() }.mapIndexed { i, p -> if (i == 0) p else p.replaceFirstChar(Char::uppercaseChar) }.joinToString("")

    private fun typeName(group: String): String = ident(group.replaceFirstChar(Char::uppercaseChar)).let { if (it == "Companion") "CompanionGroup" else it }

    private fun ident(raw: String): String {
        val cleaned = raw.map { if (it.isLetterOrDigit() || it == '_') it else '_' }.joinToString("").ifEmpty { "_" }
        val safe = if (cleaned[0].isDigit()) "_$cleaned" else cleaned
        return if (safe in KEYWORDS) "`$safe`" else safe
    }

    private fun unique(name: String, used: MutableSet<String>): String {
        var n = name
        var i = 2
        // 每个成员会生成 name、nameOrNull、setName、nameAvailable，按基名去重
        while (!used.add(n)) n = "$name$i".also { i++ }
        return n
    }

    private val PRIMITIVES = mapOf(
        "boolean" to "Boolean", "byte" to "Byte", "short" to "Short", "char" to "Char", "int" to "Int",
        "long" to "Long", "float" to "Float", "double" to "Double", "void" to "Unit",
    )
    private val BOXED = mapOf(
        "java.lang.Boolean" to "Boolean", "java.lang.Byte" to "Byte", "java.lang.Short" to "Short", "java.lang.Character" to "Char",
        "java.lang.Integer" to "Int", "java.lang.Long" to "Long", "java.lang.Float" to "Float", "java.lang.Double" to "Double",
        "java.lang.String" to "String", "java.lang.CharSequence" to "CharSequence", "java.lang.Object" to "Any",
    )
    private val GENERIC = mapOf(
        "java.util.List" to "List<*>", "java.util.ArrayList" to "java.util.ArrayList<*>", "java.util.Collection" to "Collection<*>",
        "java.util.Set" to "Set<*>", "java.util.Map" to "Map<*, *>", "java.util.HashMap" to "java.util.HashMap<*, *>",
        "java.lang.Class" to "Class<*>", "kotlin.jvm.functions.Function0" to "Function0<*>", "kotlin.jvm.functions.Function1" to "Function1<*, *>",
    )
    private val JAVA_PLAIN = setOf("java.lang.Runnable", "java.lang.Throwable", "java.lang.Exception", "java.lang.reflect.Method", "java.io.File", "java.lang.Number")
    private val ANDROID_GENERIC = setOf("SparseArray", "ArrayMap", "ArraySet", "LruCache", "Pair", "LongSparseArray", "Property", "AsyncTask", "AdapterView")
    private val KEYWORDS = setOf(
        "as", "break", "class", "continue", "do", "else", "false", "for", "fun", "if", "in", "interface", "is", "null", "object",
        "package", "return", "super", "this", "throw", "true", "try", "typealias", "typeof", "val", "var", "when", "while",
    )
}
