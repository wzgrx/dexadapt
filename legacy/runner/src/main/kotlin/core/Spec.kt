package core

import org.yaml.snakeyaml.Yaml
import java.io.File

data class MatchSpec(
    val packages: List<String> = emptyList(),
    val excludePackages: List<String> = emptyList(),
    val stringsEq: List<String> = emptyList(),
    val stringsContains: List<String> = emptyList(),
    val stringsContainsIgnoreCase: List<String> = emptyList(),
    val stringsStarts: List<String> = emptyList(),
    val stringsEnds: List<String> = emptyList(),
    val stringsEqIgnoreCase: List<String> = emptyList(),
    val classNameEq: String? = null,
    val classNameContains: String? = null,
    val methodNameEq: String? = null,
    val methodNameContains: String? = null,
    val paramCount: Int? = null,
    val paramCountMin: Int? = null,
    val paramCountMax: Int? = null,
    val returnTypeEq: String? = null,
)

enum class TargetType { CLASS, METHOD, FIELD, NAME }

data class TargetSpec(
    val key: String,
    val type: TargetType,
    val allowFailure: Boolean = false,
    val unique: Boolean = false,
    val inClass: String? = null,
    val match: MatchSpec = MatchSpec(),
    // NAME 类型专用
    val className: String? = null,
    val memberKind: String? = null,
    val memberName: String? = null,
    val memberParamCount: Int? = null,
)

data class SpecFile(val app: String, val label: String, val targets: List<TargetSpec>)

object SpecLoader {

    fun load(file: File): SpecFile {
        @Suppress("UNCHECKED_CAST")
        val root = Yaml().load(file.readText()) as? Map<String, Any?>
            ?: error("invalid spec: ${file.path}")
        val app = root["app"] as? String ?: error("spec missing 'app'")
        val label = root["label"] as? String ?: "default"
        @Suppress("UNCHECKED_CAST")
        val rawTargets = root["targets"] as? List<Any?> ?: emptyList()
        val targets = rawTargets.map { raw ->
            @Suppress("UNCHECKED_CAST")
            val t = raw as? Map<String, Any?> ?: error("invalid target entry")
            @Suppress("UNCHECKED_CAST")
            val m = (t["match"] as? Map<String, Any?>).orEmpty()
            TargetSpec(
                key = t["key"] as? String ?: error("target missing 'key'"),
                type = TargetType.valueOf(((t["type"] as? String) ?: "class").uppercase()),
                allowFailure = t["allowFailure"] as? Boolean ?: false,
                unique = t["unique"] as? Boolean ?: false,
                inClass = t["in"] as? String,
                match = MatchSpec(
                    packages = m.strList("packages"),
                    excludePackages = m.strList("exclude_packages"),
                    stringsEq = m.strList("strings_eq"),
                    stringsContains = m.strList("strings_contains"),
                    stringsContainsIgnoreCase = m.strList("strings_contains_ignore_case"),
                    stringsStarts = m.strList("strings_starts"),
                    stringsEnds = m.strList("strings_ends"),
                    stringsEqIgnoreCase = m.strList("strings_eq_ignore_case"),
                    classNameEq = m.strOrList("class_name_eq"),
                    classNameContains = m.strOrList("class_name_contains"),
                    methodNameEq = m.strOrList("method_name_eq"),
                    methodNameContains = m.strOrList("method_name_contains"),
                    paramCount = (m["param_count"] as? Number)?.toInt(),
                    paramCountMin = (m["param_count_min"] as? Number)?.toInt(),
                    paramCountMax = (m["param_count_max"] as? Number)?.toInt(),
                    returnTypeEq = m.strOrList("return_type_eq"),
                ),
                className = t["class"] as? String,
                memberKind = t["member_kind"] as? String,
                memberName = t["member_name"] as? String,
                memberParamCount = (t["member_param_count"] as? Number)?.toInt(),
            )
        }
        return SpecFile(app, label, targets)
    }

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.strList(k: String): List<String> =
        (this[k] as? List<Any?>)?.mapNotNull { it as? String }.orEmpty()

    /** 接受标量或单元素列表两种写法 */
    private fun Map<String, Any?>.strOrList(k: String): String? =
        (this[k] as? String) ?: ((this[k] as? List<Any?>)?.firstOrNull() as? String)
}
