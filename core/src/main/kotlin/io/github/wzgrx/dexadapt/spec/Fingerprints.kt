package io.github.wzgrx.dexadapt.spec

import io.github.wzgrx.dexadapt.MethodQuery
import io.github.wzgrx.dexadapt.MethodShape
import io.github.wzgrx.dexadapt.MethodSymbol
import io.github.wzgrx.dexadapt.TypeRef

/**
 * 别的生态的指纹 ⇄ DexAdapt 查询（设计 11.2 第 1 点）。读的是它们的 Kotlin 源码（按写法匹配，不编译）：
 *
 * - **ReVanced**：新写法 `val xFingerprint = fingerprint { returns(...); parameters(...); opcodes(...); strings(...); custom { ... } }`，
 *   旧写法 `object XFingerprint : MethodFingerprint(returnType = ..., parameters = listOf(...), ...)`；
 * - **Morphe**：`Fingerprint(returnType = ..., parameters = ..., filters = listOf(string(...), literal(...), methodCall(...)))`；
 * - **QAuxiliary**：`object X : DexKitTarget.UsingStr() { traitString = arrayOf(...); declaringClass = "..." }`。
 *
 * 差别：ReVanced 的 opcodes 是连续的一段（null 是通配），DexAdapt 的是“按顺序出现”（中间可以夹别的），更宽；
 * 参数描述符 ReVanced 按前缀匹配（`L` 表示任意对象），转成 `*`；QAuxiliary 的多个特征字符串是“或”，DexAdapt 只能取一个。
 * 表达不了的条件写进 [Imported.notes]，生成的查询一定要用 `dexadapt resolve` 在真实 APK 上验证。
 */
object Fingerprints {
    data class Imported(val symbols: List<MethodSymbol>, val notes: List<String>)

    enum class Format { REVANCED, MORPHE, QAUXILIARY }

    fun import(source: String, format: Format, group: String): Imported = when (format) {
        Format.REVANCED, Format.MORPHE -> revanced(source, group)
        Format.QAUXILIARY -> qauxiliary(source, group)
    }

    // ---- ReVanced / Morphe ----

    private val NEW_DSL = Regex("""va[lr]\s+(\w+)\s*(?::\s*[\w.<>]+\s*)?(?:=|by)\s*(?:lazy\s*\{\s*)?fingerprint\s*(?:\([^)]*\))?\s*\{""")
    private val OLD_DSL = Regex("""object\s+(\w+)\s*:\s*(?:MethodFingerprint|Fingerprint)\s*\(""")
    private val VAL_CTOR = Regex("""va[lr]\s+(\w+)\s*(?::\s*[\w.<>]+\s*)?=\s*(?:MethodFingerprint|Fingerprint)\s*\(""")

    private fun revanced(source: String, group: String): Imported {
        val text = stripComments(source)
        val out = ArrayList<MethodSymbol>()
        val notes = ArrayList<String>()
        for (m in NEW_DSL.findAll(text)) {
            val body = block(text, m.range.last, '{', '}') ?: continue
            convert(m.groupValues[1], body, dsl = true, group, out, notes)
        }
        for (m in OLD_DSL.findAll(text) + VAL_CTOR.findAll(text)) {
            val body = block(text, m.range.last, '(', ')') ?: continue
            convert(m.groupValues[1], body, dsl = false, group, out, notes)
        }
        return Imported(out.distinctBy { it.id }, notes)
    }

    private fun convert(rawName: String, body: String, dsl: Boolean, group: String, out: MutableList<MethodSymbol>, notes: MutableList<String>) {
        val id = "$group.${symbolName(rawName)}"
        fun call(name: String): String? = Regex("""\b$name\s*\(""").find(body)?.let { block(body, it.range.last, '(', ')') }
        fun arg(name: String): String? = Regex("""\b$name\s*=\s*""").find(body)?.let { m ->
            val rest = body.substring(m.range.last + 1).trimStart()
            when {
                rest.startsWith("\"") -> strings(rest).firstOrNull()?.let { "\"$it\"" }
                Regex("""^(listOf|arrayOf|setOf)\s*\(""").containsMatchIn(rest) -> block(rest, rest.indexOf('('), '(', ')')
                else -> rest.substringBefore(',').substringBefore('\n')
            }
        }
        val returns = (if (dsl) call("returns") else arg("returnType"))?.let(::strings)?.firstOrNull()
        val params = (if (dsl) call("parameters") else arg("parameters"))?.let(::strings)
        val strs = ((if (dsl) call("strings") else arg("strings"))?.let(::strings).orEmpty() +
            Regex("""\bstring\s*\(\s*"((?:[^"\\]|\\.)*)"""").findAll(body).map { unescape(it.groupValues[1]) }).distinct()
        val opcodes = (if (dsl) call("opcodes") else arg("opcodes"))?.let { o ->
            Regex("""Opcode\.([A-Z0-9_]+)""").findAll(o).map { it.groupValues[1].lowercase().replace('_', '-').replace("-jumbo", "/jumbo") }.toList()
        }.orEmpty()
        val numbers = Regex("""\bliteral\s*\(\s*(?:\{\s*)?(-?(?:0x[0-9a-fA-F]+|\d+))L?""").findAll(body).mapNotNull { parseLong(it.groupValues[1]) }.toList()
        val flags = (if (dsl) call("accessFlags") else arg("accessFlags")).orEmpty()
        val isStatic = if ("STATIC" in flags) true else null
        val ctor = "CONSTRUCTOR" in flags
        // custom { method, classDef -> method.name == "x" && classDef.type == "Lx;" }
        val name = Regex("""(?:method|methodDef)\.name\s*==\s*"([^"]+)"""").find(body)?.groupValues?.get(1)
        val classExact = Regex("""classDef\.(?:type|toString\(\))\s*==\s*"(L[^"]+;)"""").find(body)?.groupValues?.get(1)
        val classSuffix = Regex("""classDef\.(?:type\.)?endsWith\(\s*"([^"]+)"""").find(body)?.groupValues?.get(1)
        val definingClass = Regex("""definingClass\s*\(?\s*=?\s*"(L[^"]+;)"""").find(body.substringBefore("methodCall"))?.groupValues?.get(1)
        val calls = Regex("""methodCall\s*\(([^)]*)\)""").findAll(body).mapNotNull { c ->
            val owner = Regex("""definingClass\s*=\s*"(L[^"]+;)"""").find(c.groupValues[1])?.groupValues?.get(1)
            val n = Regex("""name\s*=\s*"([^"]+)"""").find(c.groupValues[1])?.groupValues?.get(1)
            if (owner != null && n != null) "${jvm(owner)}#$n" else null
        }.toList()
        val owner = (classExact ?: definingClass)?.let { TypeRef.Exact(jvm(it)) }
        if (classSuffix != null && owner == null) notes += "$id：类名按后缀匹配（$classSuffix），DexAdapt 写不了，没带上"
        if (Regex("""\bcustom\s*\{|customFingerprint""").containsMatchIn(body) && name == null && classExact == null && classSuffix == null) {
            notes += "$id：custom 里的条件没认出来，没带上"
        }
        if (opcodes.isNotEmpty()) notes += "$id：opcodes 按“按顺序出现”匹配（ReVanced 是连续的一段），比原来宽"
        val shape = MethodShape(
            name = if (ctor) "<init>" else name,
            params = params?.map(::paramType),
            returns = returns?.takeIf { complete(it) }?.let { TypeRef.Exact(jvm(it)) },
            strings = strs,
            isStatic = isStatic,
            numbers = numbers,
            opcodes = opcodes,
        )
        if (shape == MethodShape() && owner == null && calls.isEmpty()) {
            notes += "$id：一个条件都没认出来，跳过"
            return
        }
        out += MethodSymbol(id, MethodQuery(owner = owner, shape = shape, callsExact = calls))
    }

    // ---- QAuxiliary ----

    private val QA_TARGET = Regex("""object\s+(\w+)\s*:\s*DexKitTarget\.\w+\s*\(\s*\)\s*\{""")

    private fun qauxiliary(source: String, group: String): Imported {
        val text = stripComments(source)
        val out = ArrayList<MethodSymbol>()
        val notes = ArrayList<String>()
        for (m in QA_TARGET.findAll(text)) {
            val body = block(text, m.range.last, '{', '}') ?: continue
            val id = "$group.${symbolName(m.groupValues[1])}"
            val trait = Regex("""traitString\s*(?::\s*[\w<>]+\s*)?=\s*arrayOf\s*\(""").find(body)?.let { block(body, it.range.last, '(', ')') }?.let(::strings).orEmpty()
            val declaring = Regex("""declaringClass\s*(?::\s*\w+\s*)?=\s*"([^"]+)"""").find(body)?.groupValues?.get(1)?.replace('/', '.')?.removePrefix("L")?.removeSuffix(";")
            val findMethod = Regex("""findMethod\s*(?::\s*\w+\s*)?=\s*(true|false)""").find(body)?.groupValues?.get(1) != "false"
            if (!findMethod) notes += "$id：找的是类（findMethod = false），DexAdapt 里应写成类符号，这里按方法转"
            if (trait.size > 1) notes += "$id：QAuxiliary 的 ${trait.size} 个特征字符串是“或”，只用了第一个（其余：${trait.drop(1).joinToString("、")}）"
            if (trait.isEmpty()) { notes += "$id：没有特征字符串，跳过"; continue }
            // declaringClass 在 QAuxiliary 里常是混淆前的名字、只用于缓存，不一定能直接用：没混淆的才当所属类
            val owner = declaring?.takeIf { d -> d.split('.').all { it.length > 2 } }?.let { TypeRef.Exact(it) }
            out += MethodSymbol(id, MethodQuery(owner = owner, shape = MethodShape(strings = listOf(trait.first()))))
        }
        return Imported(out, notes)
    }

    // ---- 导出成 ReVanced ----

    /** 把方法符号写成 ReVanced 的 `fingerprint { }`；表达不了的条件写成注释。 */
    fun toRevanced(symbols: List<MethodSymbol>): String = buildString {
        appendLine("// 由 DexAdapt 导出（dexadapt export --to revanced），opcodes 在 ReVanced 里是连续的一段，比 DexAdapt 严，导出后要验证。")
        for (s in symbols) {
            val q = s.query
            val sh = q.shape
            val name = s.id.substringAfterLast('.').replaceFirstChar(Char::lowercaseChar) + "Fingerprint"
            appendLine()
            appendLine("internal val $name = fingerprint {")
            sh.isStatic?.let { if (it) appendLine("    accessFlags(AccessFlags.STATIC)") }
            sh.returns?.let { t -> descriptor(t)?.let { appendLine("    returns(\"$it\")") } }
            sh.params?.let { ps -> appendLine("    parameters(${ps.joinToString { "\"${descriptor(it) ?: "L"}\"" }})") }
            if (sh.opcodes.isNotEmpty()) appendLine("    opcodes(${sh.opcodes.joinToString { "Opcode." + it.uppercase().replace('-', '_').replace('/', '_') }})")
            if (sh.strings.isNotEmpty()) appendLine("    strings(${sh.strings.joinToString { "\"${escape(it)}\"" }})")
            val custom = buildList {
                sh.name?.let { add("method.name == \"$it\"") }
                (q.owner as? TypeRef.Exact)?.let { add("classDef.type == \"${descriptor(it)}\"") }
            }
            if (custom.isNotEmpty()) appendLine("    custom { method, classDef -> ${custom.joinToString(" && ")} }")
            val lost = buildList {
                if (q.owner != null && q.owner !is TypeRef.Exact) add("所属类是另一个符号")
                if (q.calls.isNotEmpty() || q.callsExact.isNotEmpty()) add("调用关系")
                if (q.calledBy.isNotEmpty()) add("被谁调用")
                if (q.usesFields.isNotEmpty()) add("字段读写")
                if (sh.numbers.isNotEmpty()) add("数字常量 ${sh.numbers}")
                if (sh.annotations.isNotEmpty()) add("注解")
                if (q.packagePrefix != null) add("包 ${q.packagePrefix}")
            }
            if (lost.isNotEmpty()) appendLine("    // ${s.id}：ReVanced 写不了的条件：${lost.joinToString("、")}")
            appendLine("}")
        }
    }

    // ---- 工具 ----

    /** 从 [open] 位置（开括号）取到配对的闭括号之间的内容；字符串里的括号不算。 */
    private fun block(text: String, open: Int, l: Char, r: Char): String? {
        val start = text.indexOf(l, open).takeIf { it >= 0 } ?: return null
        var depth = 0
        var i = start
        var inString = false
        while (i < text.length) {
            val c = text[i]
            if (inString) {
                if (c == '\\') i++ else if (c == '"') inString = false
            } else when (c) {
                '"' -> inString = true
                l -> depth++
                r -> if (--depth == 0) return text.substring(start + 1, i)
            }
            i++
        }
        return null
    }

    private fun strings(text: String): List<String> = Regex(""""((?:[^"\\]|\\.)*)"""").findAll(text).map { unescape(it.groupValues[1]) }.toList()

    private fun unescape(s: String) = s.replace("\\\"", "\"").replace("\\\\", "\\").replace("\\n", "\n").replace("\\t", "\t").replace("\\$", "$")

    private fun escape(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("$", "\\$")

    private fun stripComments(s: String) = s.replace(Regex("""/\*[\s\S]*?\*/"""), "").lines().joinToString("\n") { line ->
        // 去掉行尾 // 注释（不在字符串里的）
        var inString = false
        var cut = line.length
        var i = 0
        while (i < line.length - 1) {
            val c = line[i]
            if (inString) { if (c == '\\') i++ else if (c == '"') inString = false } else if (c == '"') inString = true else if (c == '/' && line[i + 1] == '/') { cut = i; break }
            i++
        }
        line.substring(0, cut)
    }

    private fun symbolName(raw: String) = raw.removeSuffix("Fingerprint").removeSuffix("Target").replaceFirstChar(Char::lowercaseChar).ifEmpty { raw }

    private fun parseLong(s: String): Long? = if (s.startsWith("0x") || s.startsWith("-0x")) s.replace("0x", "").toLongOrNull(16) else s.toLongOrNull()

    /** 完整的描述符（不是 ReVanced 的前缀写法）。 */
    private fun complete(d: String): Boolean {
        val base = d.trimStart('[')
        return base.length == 1 && base[0] in "VZBSCIJFD" || (base.startsWith("L") && base.endsWith(";"))
    }

    private fun paramType(d: String): TypeRef = if (complete(d)) TypeRef.Exact(jvm(d)) else TypeRef.Any

    /** 描述符 → JVM 名（`Ljava/lang/String;` → `java.lang.String`，`[I` → `int[]`）。 */
    fun jvm(descriptor: String): String {
        var dims = 0
        while (descriptor.getOrNull(dims) == '[') dims++
        val base = when (val c = descriptor[dims]) {
            'V' -> "void"; 'Z' -> "boolean"; 'B' -> "byte"; 'S' -> "short"; 'C' -> "char"
            'I' -> "int"; 'J' -> "long"; 'F' -> "float"; 'D' -> "double"
            'L' -> descriptor.substring(dims + 1).removeSuffix(";").replace('/', '.')
            else -> error("bad descriptor $descriptor ($c)")
        }
        return base + "[]".repeat(dims)
    }

    /** JVM 名 → 描述符；不是写死的类型返回 null。 */
    fun descriptor(t: TypeRef): String? {
        if (t !is TypeRef.Exact) return null
        var name = t.name
        var dims = 0
        while (name.endsWith("[]")) { dims++; name = name.removeSuffix("[]") }
        val base = when (name) {
            "void" -> "V"; "boolean" -> "Z"; "byte" -> "B"; "short" -> "S"; "char" -> "C"
            "int" -> "I"; "long" -> "J"; "float" -> "F"; "double" -> "D"
            else -> "L" + name.replace('.', '/') + ";"
        }
        return "[".repeat(dims) + base
    }
}
