package io.github.wzgrx.dexadapt.diff

import io.github.wzgrx.dexadapt.dexlib.DexIndex
import kotlinx.serialization.Serializable

/** 代码这一层的差异：规模、按包的增删、没混淆的类的接口变化、字符串和网络接口的增删、混淆映射。 */
@Serializable
data class CodeDiff(
    val classes: Pair<Int, Int>,
    val methods: Pair<Int, Int>,
    val fields: Pair<Int, Int>,
    /** 没混淆的包：包名 → “旧类数 → 新类数”（只列有变化的）。 */
    val packages: Map<String, String>,
    /** 没混淆的类：增删。 */
    val stableClasses: Delta,
    /** 两边都有的没混淆的类里，方法、字段的增删（`类#成员`）。 */
    val stableMembers: Delta,
    /** 网络接口（Retrofit 注解上的路径）。 */
    val endpoints: Delta,
    /** 看起来像接口路径或网址的字符串。 */
    val urls: Delta,
    /** 其它字符串（只列不太长的，日志、埋点、配置键）。 */
    val strings: Delta,
    /** 混淆映射的统计。 */
    val mapping: MappingStats,
) {
    @Serializable
    data class MappingStats(
        val oldClasses: Int,
        val newClasses: Int,
        val sameName: Int,
        val rareString: Int,
        val propagated: Int,
        val structure: Int,
        val relative: Int,
        val methods: Int,
    ) {
        val matched: Int get() = sameName + rareString + propagated + structure + relative
    }

    companion object {
        fun of(a: DexIndex, b: DexIndex, matched: ClassMatcher.Result, listLimit: Int = 400): CodeDiff {
            fun stable(i: DexIndex) = i.classes.keys.filterTo(HashSet()) { !Obfuscation.looksObfuscated(it) }
            val sa = stable(a)
            val sb = stable(b)
            fun pkgCounts(names: Set<String>) = names.groupingBy { it.substringBefore('$').substringBeforeLast('.') }.eachCount()
            val pa = pkgCounts(sa)
            val pb = pkgCounts(sb)
            val packages = (pa.keys + pb.keys).filter { pa[it] != pb[it] }.sorted().associateWith { "${pa[it] ?: 0} → ${pb[it] ?: 0}" }
            val memberAdded = ArrayList<String>()
            val memberRemoved = ArrayList<String>()
            for (name in sa.intersect(sb)) {
                val x = a.classes.getValue(name)
                val y = b.classes.getValue(name)
                fun members(c: io.github.wzgrx.dexadapt.dexlib.ClassInfo) =
                    c.methods.map { it.signature.substringAfter('#') }.toSet() + c.fields.map { "${it.name}:${it.type}" }
                val mx = members(x)
                val my = members(y)
                (my - mx).forEach { memberAdded += "$name#$it" }
                (mx - my).forEach { memberRemoved += "$name#$it" }
            }
            fun endpoints(i: DexIndex) = i.classes.values.asSequence().flatMap { it.methods.asSequence() }
                .flatMap { m -> m.annotations.asSequence().filter { it.startsWith("retrofit2.http.") } }
                .map { it.removePrefix("retrofit2.http.").replace(" value=", " ") }.toSet()
            val strA = a.methodsByString.keys
            val strB = b.methodsByString.keys
            fun isUrl(s: String) = s.startsWith("http://") || s.startsWith("https://") || (s.startsWith("/api/") || s.contains("/v1/") || s.contains("/v2/")) && ' ' !in s
            val addedStrings = (strB - strA).filter { it.length in 4..120 && !it.isBlank() }
            val removedStrings = (strA - strB).filter { it.length in 4..120 && !it.isBlank() }
            fun limit(l: List<String>) = l.sorted().take(listLimit)
            return CodeDiff(
                classes = a.classes.size to b.classes.size,
                methods = a.classes.values.sumOf { it.methods.size } to b.classes.values.sumOf { it.methods.size },
                fields = a.classes.values.sumOf { it.fields.size } to b.classes.values.sumOf { it.fields.size },
                packages = packages,
                stableClasses = Delta(limit((sb - sa).toList()), limit((sa - sb).toList())),
                stableMembers = Delta(limit(memberAdded), limit(memberRemoved)),
                endpoints = Delta.of(endpoints(a), endpoints(b)),
                urls = Delta(limit(addedStrings.filter(::isUrl)), limit(removedStrings.filter(::isUrl))),
                strings = Delta(limit(addedStrings.filterNot(::isUrl)), limit(removedStrings.filterNot(::isUrl))),
                mapping = MappingStats(
                    matched.oldClasses, matched.newClasses, matched.count(ClassPair.How.SAME_NAME), matched.count(ClassPair.How.RARE_STRING),
                    matched.count(ClassPair.How.PROPAGATED), matched.count(ClassPair.How.STRUCTURE), matched.count(ClassPair.How.RELATIVE), matched.methods.size,
                ),
            )
        }
    }
}
