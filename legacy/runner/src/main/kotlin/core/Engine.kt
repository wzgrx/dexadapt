package core

import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.StringMatcherList
import org.luckypray.dexkit.query.enums.StringMatchType
import org.luckypray.dexkit.query.matchers.ClassMatcher
import org.luckypray.dexkit.query.matchers.FieldMatcher
import org.luckypray.dexkit.query.matchers.MethodMatcher
import org.luckypray.dexkit.query.matchers.base.StringMatcher
import org.luckypray.dexkit.result.ClassData

enum class Status { SUCCESS, NOT_FOUND, EXPECTED_FAILURE, AMBIGUOUS, ERROR }

data class TargetResult(
    val key: String,
    val type: String,
    val status: Status,
    val hits: Int,
    val resolved: List<String>,
    val ms: Long,
    val detail: String? = null,
)

object Engine {

    fun runTargets(bridge: DexKitBridge, spec: SpecFile): List<TargetResult> {
        val results = ArrayList<TargetResult>(spec.targets.size)
        val resolvedClasses = HashMap<String, List<ClassData>>()

        for (t in spec.targets) {
            val t0 = System.currentTimeMillis()
            val result = try {
                when (t.type) {
                    TargetType.CLASS -> runClass(bridge, t, resolvedClasses)
                    TargetType.METHOD -> runMethod(bridge, t, resolvedClasses)
                    TargetType.FIELD -> runField(bridge, t)
                    TargetType.NAME -> runName(bridge, t)
                }
            } catch (e: Throwable) {
                TargetResult(t.key, t.type.name.lowercase(), Status.ERROR, 0, emptyList(),
                    System.currentTimeMillis() - t0, e.message ?: e.toString())
            }
            val finalResult = when {
                result.status == Status.NOT_FOUND && t.allowFailure ->
                    result.copy(status = Status.EXPECTED_FAILURE)
                result.status == Status.SUCCESS && t.unique && result.hits > 1 ->
                    result.copy(status = Status.AMBIGUOUS)
                else -> result
            }
            results += finalResult
        }
        return results
    }

    private fun runClass(bridge: DexKitBridge, t: TargetSpec, cache: MutableMap<String, List<ClassData>>): TargetResult {
        if (!hasConditions(t.match)) return emptyMatcherError(t)
        val t0 = System.currentTimeMillis()
        val list = bridge.findClass {
            if (t.match.packages.isNotEmpty()) searchPackages(t.match.packages)
            if (t.match.excludePackages.isNotEmpty()) excludePackages(t.match.excludePackages)
            matcher(buildClassMatcher(t.match))
        }
        cache[t.key] = list
        return TargetResult(
            t.key, "class",
            if (list.isEmpty()) Status.NOT_FOUND else Status.SUCCESS,
            list.size, list.take(20).map { it.name }, System.currentTimeMillis() - t0,
        )
    }

    private fun buildClassMatcher(m: MatchSpec): ClassMatcher = ClassMatcher().apply {
        stringConditions(m)?.let { usingStrings(it) }
        m.classNameEq?.let { className(StringMatcher(it, StringMatchType.Equals)) }
        m.classNameContains?.let { className(StringMatcher(it, StringMatchType.Contains)) }
    }

    private fun runMethod(bridge: DexKitBridge, t: TargetSpec, cache: Map<String, List<ClassData>>): TargetResult {
        if (!hasConditions(t.match)) return emptyMatcherError(t)
        val t0 = System.currentTimeMillis()
        val inClasses: List<ClassData>? = t.inClass?.let { ref ->
            cache[ref] ?: error("in-class reference '$ref' not resolved yet (declare it earlier)")
        }
        val list = bridge.findMethod {
            if (t.match.packages.isNotEmpty()) searchPackages(t.match.packages)
            if (inClasses != null) searchInClass(inClasses)
            matcher(buildMethodMatcher(t.match))
        }
        val resolved = list.take(20).map { md -> "${md.declaredClassName}#${md.name}(${md.paramCount})" }
        return TargetResult(
            t.key, "method",
            if (list.isEmpty()) Status.NOT_FOUND else Status.SUCCESS,
            list.size, resolved, System.currentTimeMillis() - t0,
        )
    }

    private fun buildMethodMatcher(m: MatchSpec): MethodMatcher = MethodMatcher().apply {
        stringConditions(m)?.let { usingStrings(it) }
        m.methodNameEq?.let { name(StringMatcher(it, StringMatchType.Equals)) }
        m.methodNameContains?.let { name(StringMatcher(it, StringMatchType.Contains)) }
        when {
            m.paramCount != null -> paramCount(m.paramCount)
            m.paramCountMin != null || m.paramCountMax != null ->
                paramCount(m.paramCountMin ?: 0, m.paramCountMax ?: Int.MAX_VALUE)
        }
        m.returnTypeEq?.let {
            returnType(ClassMatcher().apply { className(StringMatcher(it, StringMatchType.Equals)) })
        }
    }

    private fun runField(bridge: DexKitBridge, t: TargetSpec): TargetResult {
        if (!hasConditions(t.match)) return emptyMatcherError(t)
        val t0 = System.currentTimeMillis()
        val list = bridge.findField {
            if (t.match.packages.isNotEmpty()) searchPackages(t.match.packages)
            matcher(buildFieldMatcher(t.match))
        }
        return TargetResult(
            t.key, "field",
            if (list.isEmpty()) Status.NOT_FOUND else Status.SUCCESS,
            list.size, list.take(20).map { "${it.className}->${it.name}" },
            System.currentTimeMillis() - t0,
        )
    }

    private fun buildFieldMatcher(m: MatchSpec): FieldMatcher = FieldMatcher().apply {
        m.methodNameEq?.let { name(StringMatcher(it, StringMatchType.Equals)) }
        m.methodNameContains?.let { name(StringMatcher(it, StringMatchType.Contains)) }
        m.classNameEq?.let {
            declaredClass(ClassMatcher().apply { className(StringMatcher(it, StringMatchType.Equals)) })
        }
    }

    private fun runName(bridge: DexKitBridge, t: TargetSpec): TargetResult {
        val t0 = System.currentTimeMillis()
        val cn = t.className ?: error("name target missing 'class'")
        val cd = bridge.getClassData(cn)
            ?: return TargetResult(t.key, "name", Status.NOT_FOUND, 0, emptyList(),
                System.currentTimeMillis() - t0, "class not found: $cn")
        if (t.memberKind == null) {
            return TargetResult(t.key, "name", Status.SUCCESS, 1, listOf(cd.name),
                System.currentTimeMillis() - t0, "methods=${cd.methodCount} fields=${cd.fieldCount}")
        }
        val hits: List<String> = when (t.memberKind) {
            "method" -> cd.methods.filter { md ->
                (t.memberName == null || md.name == t.memberName) &&
                    (t.memberParamCount == null || md.paramCount == t.memberParamCount)
            }.map { "${cd.name}#${it.name}(${it.paramCount})" }
            "field" -> cd.fields.filter { fd ->
                t.memberName == null || fd.name == t.memberName
            }.map { "${cd.name}->${it.name}" }
            else -> error("unknown member_kind: ${t.memberKind}")
        }
        return TargetResult(
            t.key, "name",
            if (hits.isEmpty()) Status.NOT_FOUND else Status.SUCCESS,
            hits.size, hits.take(20), System.currentTimeMillis() - t0,
        )
    }

    private fun stringConditions(m: MatchSpec): StringMatcherList? {
        val list = StringMatcherList()
        m.stringsEq.forEach { list.add(it, StringMatchType.Equals) }
        m.stringsContains.forEach { list.add(it, StringMatchType.Contains) }
        m.stringsContainsIgnoreCase.forEach { list.add(it, StringMatchType.Contains, true) }
        m.stringsStarts.forEach { list.add(it, StringMatchType.StartsWith) }
        m.stringsEnds.forEach { list.add(it, StringMatchType.EndsWith) }
        m.stringsEqIgnoreCase.forEach { list.add(it, StringMatchType.Equals, true) }
        return if (list.isEmpty()) null else list
    }

    /** 空 matcher 防御：无条件 matcher = 全量匹配，直接判 ERROR 而非默默扫全库 */
    private fun hasConditions(m: MatchSpec): Boolean =
        m.stringsEq.isNotEmpty() || m.stringsContains.isNotEmpty() || m.stringsContainsIgnoreCase.isNotEmpty() ||
            m.stringsStarts.isNotEmpty() || m.stringsEnds.isNotEmpty() || m.stringsEqIgnoreCase.isNotEmpty() ||
            m.classNameEq != null || m.classNameContains != null ||
            m.methodNameEq != null || m.methodNameContains != null ||
            m.paramCount != null || m.paramCountMin != null || m.paramCountMax != null ||
            m.returnTypeEq != null

    private fun emptyMatcherError(t: TargetSpec): TargetResult =
        TargetResult(t.key, t.type.name.lowercase(), Status.ERROR, 0, emptyList(), 0,
            "empty matcher conditions (would match everything) — check spec syntax")
}
