package io.github.wzgrx.dexadapt.libs

import io.github.wzgrx.dexadapt.resolve.MethodView
import java.security.MessageDigest

/**
 * 方法的“抗混淆指纹”：混淆和 R8 改不动的部分——静态与否、参数和返回值里的平台类型（库自己的类型一律记成 `?`）、
 * 字符串常量、调用了哪些平台方法。同一个库方法，在官方 jar 里和在 App 混淆后的 dex 里算出来一样。
 *
 * 只给“有特征”的方法算（有字符串，或调用了至少 [MIN_CALLS] 个平台方法）；getter、空方法这类到处都一样，算了也只会误配。
 * R8 内联、删参数会让一部分方法对不上，所以识别只看命中的比例，不要求全中。
 */
object MethodPrint {
    const val MIN_CALLS = 3

    /** 平台（Android 系统和 Java 标准库）的包：混淆不会改它们的名字。 */
    private val PLATFORM = listOf("java.", "javax.", "android.", "dalvik.", "org.json.", "org.w3c.dom.", "org.xml.sax.")
    private val PRIMITIVES = setOf("void", "boolean", "byte", "short", "char", "int", "long", "float", "double")

    /** 编译器自己插的调用，jar 和 dex 两边可能不一样（字符串拼接、invokedynamic），不算特征。 */
    private val NOISE = listOf("java.lang.StringBuilder#", "java.lang.Object#<init>", "java.lang.invoke.")

    fun isPlatform(type: String): Boolean = PLATFORM.any { type.startsWith(it) }

    fun mask(type: String): String {
        val base = type.substringBefore('[')
        return if (base in PRIMITIVES || isPlatform(base)) type else "?" + type.substring(base.length)
    }

    /** 指纹的明文（调试用）；没有特征时为 null。 */
    fun text(m: MethodView): String? {
        val strings = m.strings.filter { it.isNotEmpty() }.sorted()
        val calls = m.calls.asSequence()
            .filter { c -> isPlatform(c) && NOISE.none { c.startsWith(it) } }
            .map { it.substringBefore('(') }.toSortedSet()
        if (strings.isEmpty() && calls.size < MIN_CALLS) return null
        val kind = when {
            m.name == "<init>" -> "C"
            m.name == "<clinit>" -> "I"
            m.isStatic -> "S"
            else -> "V"
        }
        return buildString {
            append(kind).append('|').append(m.params.joinToString(",") { mask(it) }).append('|').append(mask(m.returns))
            append('|').append(strings.joinToString("\u0000"))
            append('|').append(calls.joinToString(","))
        }
    }

    fun of(m: MethodView): Long? = text(m)?.let(::hash)

    fun hash(text: String): Long {
        val d = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
        var h = 0L
        for (i in 0 until 8) h = (h shl 8) or (d[i].toLong() and 0xff)
        return h
    }
}
