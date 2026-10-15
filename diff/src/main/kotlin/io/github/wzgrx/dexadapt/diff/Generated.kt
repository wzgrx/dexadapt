package io.github.wzgrx.dexadapt.diff

import kotlinx.serialization.Serializable

/**
 * 编译器和 R8 生成的名字（设计 11.2 第 7 点）：R8 的 `$$ExternalSyntheticLambda0`、外提（Outline）、兼容（Backport）、
 * `$r8$lambda$…`，Kotlin / javac 的 `$lambda$12`、`$$inlined$`、匿名类 `$1`、`access$000`、`lambda$foo$3`。
 * 这些名字里的编号随代码前后增删而变（重新编号），不代表功能改动；diff 把它们单独计数，不放进“新增 / 删除”。
 */
object Generated {
    private val R8 = Regex("""[$][$]ExternalSynthetic\w*|[$]r8[$]|-[$][$]Lambda[$]""")
    private val COMPILER = Regex("""[$]lambda[$]\d+|[$][$]inlined[$]|[$]\d+(?=[$]|\z)|(?:^|#|[$])access[$]\d+|(?:^|#)lambda[$][\w$]*[$]\d+|[$]default(?=[$]|\z|\()""")
    private val NUMBER = Regex("""(?<=[$]|lambda|Lambda|Outline|Backport)\d+""")

    /** 名字（类名、`类#成员`、字符串）里有编译器或 R8 生成的部分。 */
    fun isGenerated(name: String): Boolean = R8.containsMatchIn(name) || COMPILER.containsMatchIn(name)

    /** 是 R8 生成的（不是编译器）。 */
    fun isR8(name: String): Boolean = R8.containsMatchIn(name)

    /** 去掉编号：重新编号前后的同一个东西得到同一个结果。 */
    fun normalize(name: String): String = NUMBER.replace(name, "#")

    /**
     * 把一组新增、删除分成“真的”和“生成的”：生成的名字去掉编号后两边都有的，算重新编号（抵消掉）；
     * 剩下的生成名字单独计数。
     */
    fun split(added: Collection<String>, removed: Collection<String>): Pair<Delta, Stats> {
        val (genAdded, realAdded) = added.partition(::isGenerated)
        val (genRemoved, realRemoved) = removed.partition(::isGenerated)
        val na = genAdded.groupingBy(::normalize).eachCount()
        val nr = genRemoved.groupingBy(::normalize).eachCount()
        val renumbered = (na.keys intersect nr.keys).sumOf { minOf(na.getValue(it), nr.getValue(it)) }
        val stats = Stats(
            added = genAdded.size, removed = genRemoved.size, renumbered = renumbered,
            r8 = (genAdded + genRemoved).count(::isR8),
        )
        return Delta(realAdded.sorted(), realRemoved.sorted()) to stats
    }

    @Serializable
    data class Stats(
        /** 新增 / 删除的生成名字。 */
        val added: Int = 0,
        val removed: Int = 0,
        /** 其中去掉编号后两边都有的对数（只是重新编号）。 */
        val renumbered: Int = 0,
        /** 其中 R8 生成的个数（其余是 Kotlin / javac）。 */
        val r8: Int = 0,
    ) {
        operator fun plus(o: Stats) = Stats(added + o.added, removed + o.removed, renumbered + o.renumbered, r8 + o.r8)
        val empty: Boolean get() = added == 0 && removed == 0
    }
}
