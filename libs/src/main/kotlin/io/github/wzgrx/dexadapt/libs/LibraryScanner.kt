package io.github.wzgrx.dexadapt.libs

import io.github.wzgrx.dexadapt.dexlib.DexIndex
import kotlinx.serialization.Serializable

/**
 * 认出 APK 里打包的开源库：哪个库、哪个版本、混淆后的类对应库里的哪个类。
 *
 * - **有没有**：库的特征字符串在 APK 里出现的比例（召回）。不相干的 App 偶尔也会有几个同样的字符串，所以要求命中数和比例都够。
 * - **哪个版本**：每个版本打分 = 这个版本的字符串在 APK 里出现的个数 − 只有别的版本才有、却出现在 APK 里的个数；
 *   分数最高的那些版本（一般是字符串完全相同的一段修订版）作为结果。
 * - **类对应**：库里每个类的特征字符串在 APK 里被哪些类用到，投票；票数够、且明显领先第二名才算。
 *   R8 会把小方法内联到调用者、把类合并，所以有的库类配不上或配到合并后的类上，报告里带票数。
 * - 两个库命中的字符串大部分相同（RxJava 2 和 3、Okio 1 和 2 这类同源的库）时只留分高的那个。
 */
class LibraryScanner(private val db: LibraryDb, private val minHits: Int = MIN_HITS) {
    @Serializable
    data class Detection(
        val id: String,
        val name: String,
        /** 最可能的版本（分数并列的都列出，旧的在前）。 */
        val versions: List<String>,
        /** 最可能版本的特征字符串在 APK 里出现的比例。 */
        val recall: Double,
        val hits: Int,
        val total: Int,
        /** 只有别的版本才有、却出现在 APK 里的字符串数（越少越确定）。 */
        val against: Int,
        val classes: List<ClassMatch>,
        /** 配上的类里名字没变的个数（库没被混淆时等于配上的类数）。 */
        val namesKept: Int,
        /** 库的包 → APK 里的包（按配上的类投票；包名没变的不列）。 */
        val packages: Map<String, String>,
    ) {
        val confidence: String get() = when {
            recall >= 0.6 -> "高"
            recall >= 0.4 -> "中"
            else -> "低"
        }

        val versionText: String get() = if (versions.size == 1) versions[0] else "${versions.first()} ～ ${versions.last()}"
    }

    @Serializable
    data class ClassMatch(
        val library: String,
        val apk: String,
        /** 投给 [apk] 的字符串数。 */
        val votes: Int,
        /** 库类的特征字符串里在 APK 中还在的个数。 */
        val strings: Int,
    )

    fun scan(index: DexIndex): List<Detection> {
        val pool = index.methodsByString
        val found = db.libraries.mapNotNull { detect(it, index, pool) }.sortedByDescending { it.hits }
        // 同源库（命中的字符串大多相同）只留分高的
        val kept = ArrayList<Detection>()
        val hitSets = HashMap<String, Set<String>>()
        for (d in found) {
            val lib = db.library(d.id)!!
            val mine = lib.features(d.versions.last()).map { it.s }.filter { it in pool }.toSet()
            val dup = kept.any { k -> hitSets.getValue(k.id).let { other -> mine.count { it in other } > mine.size / 2 } }
            if (!dup) {
                kept += d
                hitSets[d.id] = mine
            }
        }
        return kept
    }

    private fun detect(lib: LibraryDb.Library, index: DexIndex, pool: Map<String, List<*>>): Detection? {
        val present = lib.features.filter { it.s in pool }
        if (present.size < minHits) return null
        val all = present.fold(0L) { acc, f -> acc or f.v }
        data class Score(val version: String, val hits: Int, val total: Int, val against: Int) {
            val score get() = hits - against
        }
        val scores = lib.versions.mapIndexed { i, v ->
            val m = 1L shl i
            Score(v, present.count { it.v and m != 0L }, lib.features.count { it.v and m != 0L }, present.count { it.v and m == 0L })
        }
        val bestScore = scores.maxOf { it.score }
        val best = scores.filter { it.score == bestScore }
        val top = best.last()
        val recall = top.hits.toDouble() / top.total.coerceAtLeast(1)
        if (top.hits < minHits || recall < MIN_RECALL || all == 0L) return null
        val classes = matchClasses(lib, lib.mask(top.version), index)
        val packages = classes.filter { it.library != it.apk }
            .groupBy { it.library.substringBeforeLast('.', "") }
            .mapValues { (_, ms) -> ms.groupingBy { it.apk.substringBeforeLast('.', "") }.eachCount().maxBy { it.value }.key }
            .filter { (k, v) -> k != v }
            .toSortedMap()
        return Detection(
            lib.id, lib.name, best.map { it.version }, recall, top.hits, top.total, top.against,
            classes, classes.count { it.library == it.apk }, packages,
        )
    }

    private fun matchClasses(lib: LibraryDb.Library, mask: Long, index: DexIndex): List<ClassMatch> {
        val stringsOf = HashMap<Int, MutableList<String>>()
        // 只看这个版本里有的类（同一个字符串在老版本里可能在别的类）
        for (f in lib.features) if (f.v and mask != 0L) for (c in f.c) if (lib.classVersions[c] and mask != 0L) stringsOf.getOrPut(c) { ArrayList() } += f.s
        val out = ArrayList<ClassMatch>()
        for ((ci, strings) in stringsOf) {
            val votes = HashMap<String, Int>()
            // R8 删掉的代码里的字符串不在 APK 里，不算进分母
            val present = strings.count { it in index.methodsByString }
            for (s in strings) {
                val owners = index.methodsByString[s].orEmpty().map { it.owner }.toSet()
                // 太常见的字符串（很多类都用）不投票
                if (owners.size > 3) continue
                owners.forEach { votes.merge(it, 1, Int::plus) }
            }
            val ranked = votes.entries.sortedByDescending { it.value }.toMutableList()
            val name = lib.classes[ci]
            // 名字没改（库没混淆，或 R8 保留了这个类名）：直接认
            if (index.classes.containsKey(name)) {
                out += ClassMatch(name, name, votes[name] ?: 0, present)
                continue
            }
            // 改了名的库类在 APK 里要么是混淆名，要么简单类名不变（被别的 SDK 整体搬到自己的包里）；
            // 别的有意义的名字是 App 自己或别的库的类（库的代码被内联进了它），不算
            ranked.retainAll { (apk, _) -> plausible(name, apk) }
            val first = ranked.firstOrNull() ?: continue
            val second = ranked.getOrNull(1)?.value ?: 0
            // 票数要够：至少两票且过半（APK 里还在的字符串）；只有一个字符串的类要求它在 APK 里只有一个类用
            val enough = if (present == 1) ranked.size == 1 else first.value >= 2 && first.value * 2 >= present
            if (enough && first.value > second) out += ClassMatch(name, first.key, first.value, present)
        }
        // 一个 APK 类被几个库类认领（R8 把它们的代码内联、合并到了一起）：只留字符串对得最好的那个
        val claims = out.groupBy { it.apk }
        out.retainAll { m -> claims.getValue(m.apk).maxWith(compareBy<ClassMatch>({ it.votes.toDouble() / it.strings }, { it.votes })) === m }
        return out.sortedBy { it.library }
    }

    private fun plausible(library: String, apk: String): Boolean {
        val simple = apk.substringAfterLast('.').substringAfterLast('$')
        return simple == library.substringAfterLast('.').substringAfterLast('$') || OBFUSCATED.matches(simple)
    }

    companion object {
        private val OBFUSCATED = Regex("[a-zA-Z]{1,3}\\d{0,3}|\\d+")
        const val MIN_HITS = 8
        const val MIN_RECALL = 0.25
    }
}
