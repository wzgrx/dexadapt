package io.github.wzgrx.dexadapt.diff

import io.github.wzgrx.dexadapt.dexlib.ClassInfo
import io.github.wzgrx.dexadapt.dexlib.DexIndex
import io.github.wzgrx.dexadapt.dexlib.MethodInfo
import kotlinx.serialization.Serializable

/** 一对配上的类：旧版名字 → 新版名字，配上的方式和把握。 */
@Serializable
data class ClassPair(val old: String, val new: String, val how: How, val confidence: Double) {
    enum class How {
        /** 名字没混淆，两边同名。 */
        SAME_NAME,
        /** 两边各只有这一个类用到同一个少见的字符串。 */
        RARE_STRING,
        /** 已配上的类的父类、字段类型、方法签名里对应位置上的类。 */
        PROPAGATED,
        /** 结构特征最像，而且明显比第二像的强。 */
        STRUCTURE,
        /** 同一个包被整体改名（多数类都从 P 到了 Q），短名相同、结构也像；或者是已配上的类的内部类。 */
        RELATIVE,
    }
}

/** 一对配上的方法（在配上的类里）。 */
@Serializable
data class MethodPair(val old: String, val new: String)

/**
 * 跨版本的混淆映射：把旧版的类、方法对应到新版（名字被混淆改掉也能认出是同一个）。
 * 借鉴 Quarkslab 的四阶段思路（锚定 → 结构相似 → 方法级），按顺序做，后一步用前一步的结果：
 *
 * 1. **同名**：名字看起来没混淆、两边都有的类，直接配；
 * 2. **稀有字符串**：一个字符串在旧版和新版各只出现在一个类里，这两个类配上（日志、埋点、接口路径都很独特）；
 * 3. **传播**：配上的类，父类 / 接口、按形状唯一对应的方法和字段，其中混淆的类型两两配上；
 * 4. **亲属**：R8 常把整个包改名而保留短名（`zed.c` → `pgd.c`）：按已配上的类投票得出包的对应，同短名、结构像的配上；
 *    已配上的类的内部类，后缀相同、结构像的配上；
 * 5. **结构**：剩下的按结构特征（方法形状、字段类型、父类接口、调用的稳定 API，混淆的类型换成已配上的编号）
 *    找最像的，最像的要足够像、并且明显比第二像的强，才配。
 * 2～5 循环几轮：配上的越多，混淆类型能换成编号的越多，结构特征越能区分。
 *
 * 只配一对一；拿不准的宁可不配（报告里列为新增 / 删除）。
 */
class ClassMatcher(private val a: DexIndex, private val b: DexIndex) {
    private val pairs = LinkedHashMap<String, ClassPair>() // 旧 → 对
    private val reverse = HashMap<String, String>() // 新 → 旧
    private val ids = HashMap<String, Int>() // 旧类名 → 配对编号（结构特征里用）

    fun run(rounds: Int = 4): Result {
        sameName()
        repeat(rounds) {
            val before = pairs.size
            rareStrings()
            propagate()
            relatives()
            structure()
            if (pairs.size == before) return@repeat
        }
        val methods = pairs.values.flatMap { p -> matchMethods(a.classes.getValue(p.old), b.classes.getValue(p.new)) }
        return Result(pairs.values.toList(), methods, a.classes.size, b.classes.size)
    }

    @Serializable
    data class Result(val classes: List<ClassPair>, val methods: List<MethodPair>, val oldClasses: Int, val newClasses: Int) {
        val byOld: Map<String, String> by lazy { classes.associate { it.old to it.new } }
        fun count(how: ClassPair.How) = classes.count { it.how == how }
    }

    private fun pair(old: String, new: String, how: ClassPair.How, confidence: Double): Boolean {
        if (old in pairs || new in reverse) return false
        pairs[old] = ClassPair(old, new, how, confidence)
        reverse[new] = old
        ids[old] = ids.size
        return true
    }

    // ---- 1. 同名 ----

    private fun sameName() {
        for (name in a.classes.keys) if (!Obfuscation.looksObfuscated(name) && name in b.classes) pair(name, name, ClassPair.How.SAME_NAME, 1.0)
    }

    // ---- 2. 稀有字符串 ----

    /** 字符串 → 用到它的类（只记出现在一个类里的）。 */
    private fun uniqueOwners(index: DexIndex): Map<String, String> {
        val owners = HashMap<String, Any>() // 值是类名，或者 MULTI
        for ((s, methods) in index.methodsByString) {
            if (s.length < 4) continue
            var owner: String? = null
            var multi = false
            for (m in methods) {
                if (owner == null) owner = m.owner else if (owner != m.owner) { multi = true; break }
            }
            if (!multi && owner != null) owners[s] = owner
        }
        @Suppress("UNCHECKED_CAST")
        return owners as Map<String, String>
    }

    private val uniqueA by lazy { uniqueOwners(a) }
    private val uniqueB by lazy { uniqueOwners(b) }

    private fun rareStrings() {
        // 旧类 → (新类 → 票数)
        val votes = HashMap<String, HashMap<String, Int>>()
        for ((s, oldOwner) in uniqueA) {
            if (oldOwner in pairs) continue
            val newOwner = uniqueB[s] ?: continue
            if (newOwner in reverse) continue
            votes.getOrPut(oldOwner) { HashMap() }.merge(newOwner, 1, Int::plus)
        }
        // 票数多的先配；一个旧类的票分给了几个新类时，只有最多的那个明显领先才配。
        val ranked = votes.mapNotNull { (old, m) ->
            val sorted = m.entries.sortedByDescending { it.value }
            val best = sorted[0]
            val second = sorted.getOrNull(1)?.value ?: 0
            if (best.value > second * 2) Triple(old, best.key, best.value) else null
        }.sortedByDescending { it.third }
        for ((old, new, n) in ranked) pair(old, new, ClassPair.How.RARE_STRING, (0.8 + 0.05 * n).coerceAtMost(1.0))
    }

    // ---- 3. 传播 ----

    private fun propagate() {
        var changed = true
        while (changed) {
            changed = false
            for (p in pairs.values.toList()) {
                val x = a.classes[p.old] ?: continue
                val y = b.classes[p.new] ?: continue
                fun link(ta: String?, tb: String?) {
                    if (ta == null || tb == null) return
                    if (ta in a.classes && tb in b.classes && Obfuscation.looksObfuscated(ta) && pair(ta, tb, ClassPair.How.PROPAGATED, 0.9)) changed = true
                }
                link(x.superName, y.superName)
                if (x.interfaces.size == y.interfaces.size) x.interfaces.zip(y.interfaces).forEach { (i, j) -> link(i, j) }
                for ((m, n) in matchMethods(x, y)) {
                    val mi = methodOf(x, m)
                    val ni = methodOf(y, n)
                    if (mi != null && ni != null && mi.params.size == ni.params.size) {
                        mi.params.zip(ni.params).forEach { (i, j) -> link(elem(i), elem(j)) }
                        link(elem(mi.returns), elem(ni.returns))
                    }
                }
                // 字段：按（规范化后的类型）唯一对应
                val fa = x.fields.groupBy { norm(it.type, true) }
                val fb = y.fields.groupBy { norm(it.type, false) }
                for ((k, list) in fa) {
                    val other = fb[k] ?: continue
                    if (list.size == 1 && other.size == 1) link(elem(list[0].type), elem(other[0].type))
                }
            }
        }
    }

    private fun methodOf(c: ClassInfo, signature: String) = c.methods.firstOrNull { it.signature == signature }

    private fun elem(t: String) = t.removeSuffix("[]").removeSuffix("[]")

    // ---- 4. 亲属：包整体改名、内部类 ----

    private fun relatives() {
        // 包的对应：配上的混淆类按“旧包 → 新包”投票，取明显多数。
        val votes = HashMap<String, HashMap<String, Int>>()
        for (p in pairs.values) {
            if (p.how == ClassPair.How.SAME_NAME) continue
            votes.getOrPut(pkg(p.old)) { HashMap() }.merge(pkg(p.new), 1, Int::plus)
        }
        val pkgMap = votes.mapNotNull { (o, m) ->
            val sorted = m.entries.sortedByDescending { it.value }
            val best = sorted[0]
            if (best.value >= 2 && best.value > (sorted.getOrNull(1)?.value ?: 0) * 2) o to best.key else null
        }.toMap()
        fun tryPair(old: ClassInfo, newName: String) {
            if (old.name in pairs || newName in reverse) return
            val n = b.classes[newName] ?: return
            if (similarity(features(old, true), features(n, false)) >= RELATIVE_SCORE) pair(old.name, newName, ClassPair.How.RELATIVE, 0.85)
        }
        for (c in a.classes.values) {
            if (c.name in pairs) continue
            // 已配上的外部类的内部类
            val dollar = c.name.lastIndexOf('$')
            if (dollar > 0) {
                val outerNew = pairs[c.name.substring(0, dollar)]?.new
                if (outerNew != null) { tryPair(c, outerNew + c.name.substring(dollar)); if (c.name in pairs) continue }
            }
            // 包整体改名
            val q = pkgMap[pkg(c.name)] ?: continue
            tryPair(c, q + "." + c.name.substring(pkg(c.name).length + 1))
        }
    }

    private fun pkg(name: String) = name.substringBeforeLast('.', "")

    // ---- 5. 结构 ----

    /**
     * 把一个类型变成可以跨版本比较的样子：基本类型和没混淆的类原样；配上了的混淆类写成编号；
     * 没配上的混淆类只记“某个混淆类”。
     */
    private fun norm(type: String, old: Boolean): String {
        val dims = type.length - type.removeSuffix("[]").removeSuffix("[]").removeSuffix("[]").length
        val base = type.dropLast(dims)
        val n = when {
            !Obfuscation.looksObfuscated(base) || base !in (if (old) a.classes else b.classes) -> base
            old -> ids[base]?.let { "#$it" } ?: "?"
            else -> reverse[base]?.let { ids[it] }?.let { "#$it" } ?: "?"
        }
        return n + type.takeLast(dims)
    }

    private fun features(c: ClassInfo, old: Boolean): Map<String, Int> {
        val f = HashMap<String, Int>()
        fun add(k: String, w: Int = 1) = f.merge(k, w, Int::plus)
        add("super:" + (c.superName?.let { norm(it, old) } ?: "-"), 3)
        c.interfaces.forEach { add("if:" + norm(it, old), 2) }
        add("flags:" + (c.flags and 0x7631))
        for (fl in c.fields) add("f:" + norm(fl.type, old) + if (fl.isStatic) ":s" else "")
        for (m in c.methods) {
            val shape = m.params.joinToString(",") { norm(it, old) } + ")" + norm(m.returns, old) + if (m.isStatic) ":s" else ""
            add("m:" + (if (Obfuscation.memberObfuscated(m.name)) "" else m.name) + "(" + shape, 2)
            add("ops:" + m.opcodes.size / 8)
            for (call in m.calls) {
                val owner = call.substringBefore('#')
                if (!Obfuscation.looksObfuscated(owner)) add("call:" + call.substringBefore('('))
                else norm(owner, old).let { if (it != "?") add("call:$it#" + call.substringAfter('#').substringBefore('(').let { n -> if (Obfuscation.memberObfuscated(n)) "" else n }) }
            }
            for (s in m.strings) add("str:$s", 3)
        }
        return f
    }

    private fun similarity(x: Map<String, Int>, y: Map<String, Int>): Double {
        var inter = 0
        var union = 0
        for (k in x.keys + y.keys) {
            val p = x[k] ?: 0
            val q = y[k] ?: 0
            inter += minOf(p, q)
            union += maxOf(p, q)
        }
        return if (union == 0) 0.0 else inter.toDouble() / union
    }

    private fun structure() {
        val oldLeft = a.classes.values.filter { it.name !in pairs && Obfuscation.looksObfuscated(it.name) }
        val newLeft = b.classes.values.filter { it.name !in reverse && Obfuscation.looksObfuscated(it.name) }
        if (oldLeft.isEmpty() || newLeft.isEmpty()) return
        // 先按粗特征分桶（父类、方法数、字段数），只在桶里比较。
        fun bucket(c: ClassInfo, old: Boolean) = "${c.superName?.let { norm(it, old) }}|${c.methods.size}|${c.fields.size}"
        val newBuckets = newLeft.groupBy { bucket(it, false) }
        val newFeatures = HashMap<String, Map<String, Int>>()
        val candidates = ArrayList<Triple<String, String, Double>>()
        for (c in oldLeft) {
            val pool = newBuckets[bucket(c, true)] ?: continue
            if (pool.size > 400) continue // 太大的桶（大量同形状小类）比不出来
            val fx = features(c, true)
            var best: ClassInfo? = null
            var bestScore = 0.0
            var second = 0.0
            for (d in pool) {
                val fy = newFeatures.getOrPut(d.name) { features(d, false) }
                val s = similarity(fx, fy)
                if (s > bestScore) { second = bestScore; bestScore = s; best = d } else if (s > second) second = s
            }
            if (best != null && bestScore >= MIN_SCORE && bestScore - second >= MIN_GAP) candidates += Triple(c.name, best.name, bestScore)
        }
        // 新版那边被两个旧类同时看中的，只给分高的。
        for ((old, new, s) in candidates.sortedByDescending { it.third }) pair(old, new, ClassPair.How.STRUCTURE, s)
    }

    // ---- 方法 ----

    /** 配上的两个类里，按名字（没混淆时）或按规范化形状唯一对应的方法。 */
    private fun matchMethods(x: ClassInfo, y: ClassInfo): List<MethodPair> {
        fun key(m: MethodInfo, old: Boolean): String =
            (if (Obfuscation.memberObfuscated(m.name)) "" else m.name) + "(" + m.params.joinToString(",") { norm(it, old) } + ")" + norm(m.returns, old) + (if (m.isStatic) ":s" else "")
        val ka = x.methods.groupBy { key(it, true) }
        val kb = y.methods.groupBy { key(it, false) }
        return ka.mapNotNull { (k, list) ->
            val other = kb[k]
            if (list.size == 1 && other?.size == 1) MethodPair(list[0].signature, other[0].signature) else null
        }
    }

    private companion object {
        const val MIN_SCORE = 0.75
        const val MIN_GAP = 0.08
        /** 亲属配对的结构相似度门槛（已有包或外部类的线索，比纯结构宽松）。 */
        const val RELATIVE_SCORE = 0.55
    }
}

/** 名字是否像混淆过的（R8 / ProGuard 的短名字）。 */
object Obfuscation {
    private val shortName = Regex("[a-zA-Z]{1,3}\\d{0,3}")
    private val letterSegment = Regex("[a-z]")
    /** 真实存在的顶级包名（R8 把类压进 `sa9`、`mna` 这类三个字母以内的顶级包，和它们区分开）。 */
    private val roots = setOf(
        "com", "org", "net", "android", "androidx", "java", "javax", "kotlin", "kotlinx", "dalvik", "junit", "okhttp3", "okio",
        "retrofit2", "io", "tv", "cn", "me", "de", "fr", "uk", "jp", "app", "dev", "us", "edu", "gov", "info", "biz", "co", "sun", "libcore",
    )

    /**
     * 像混淆过的：没有包名；类名是 1～3 个字母加数字（`g0`、`aa`）；顶级包不是常见的真实包名而且很短（`sa9`、`mna`）；
     * 或者中间某一段是单个字母（`com.xingin.a.b`）。内部类按外部类判断。
     */
    fun looksObfuscated(className: String): Boolean {
        val outer = className.substringBefore('$')
        val parts = outer.split('.')
        if (parts.size <= 1) return true
        if (parts.last().matches(shortName)) return true
        if (parts[0] !in roots && parts[0].length <= 3) return true
        return parts.drop(1).dropLast(1).any { it.matches(letterSegment) }
    }

    /** 成员名是否像混淆过的（`a`、`b0`、`xa`）。 */
    fun memberObfuscated(name: String): Boolean = name.length <= 2 || name.matches(Regex("[a-z]{1,2}\\d{1,3}"))
}
