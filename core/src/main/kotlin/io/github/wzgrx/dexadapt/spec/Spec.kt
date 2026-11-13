package io.github.wzgrx.dexadapt.spec

import io.github.wzgrx.dexadapt.Catalog
import io.github.wzgrx.dexadapt.ClassQuery
import io.github.wzgrx.dexadapt.ClassSymbol
import io.github.wzgrx.dexadapt.FieldQuery
import io.github.wzgrx.dexadapt.FieldSymbol
import io.github.wzgrx.dexadapt.MethodQuery
import io.github.wzgrx.dexadapt.MethodShape
import io.github.wzgrx.dexadapt.MethodSymbol
import io.github.wzgrx.dexadapt.MethodVariant
import io.github.wzgrx.dexadapt.Pin
import io.github.wzgrx.dexadapt.Symbol
import io.github.wzgrx.dexadapt.TypeRef
import io.github.wzgrx.dexadapt.absentIn
import io.github.wzgrx.dexadapt.pin
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import org.snakeyaml.engine.v2.api.Dump
import org.snakeyaml.engine.v2.api.DumpSettings
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings
import org.snakeyaml.engine.v2.common.FlowStyle

/**
 * 查询定义的数据格式（YAML / JSON，同一个结构），让不写 Kotlin 的模块和命令行工具也能用。语法见 docs/SPEC.md：
 *
 * ```yaml
 * dexadapt: 1
 * app: com.xingin.xhs
 * symbols:
 *   - id: feedCopy.overlay
 *     class: { strings: [feedbackOverlay, dsl/negative_feedback_mask_view] }
 *   - id: feedCopy.showOverlay
 *     method: { owner: $feedCopy.overlay, params: [java.lang.String, int, $feedCopy.feedbackBean], returns: void }
 * ```
 *
 * 类型写法：`$id` 是另一个类符号，`super($id)` 是它的直接父类，`*` 是任意类型，其余是 JVM 类型名。
 * 方法、字段引用（calls、usesFields……）写 `$id`。符号可以按任意顺序写，引用在读入时解析。
 */
data class Spec(val app: String?, val catalog: Catalog) {
    fun toJson(): String = pretty.encodeToString(JsonElement.serializer(), encode())

    fun toYaml(): String {
        val settings = DumpSettings.builder().setDefaultFlowStyle(FlowStyle.BLOCK).setIndent(2).setIndicatorIndent(2)
            .setIndentWithIndicator(true).setWidth(160).build()
        return Dump(settings).dumpToString(plain(encode()))
    }

    fun encode(): JsonObject = buildJsonObject {
        val hasVariants = catalog.symbols.any { it is MethodSymbol && it.query.variants.isNotEmpty() }
        put("dexadapt", JsonPrimitive(if (hasVariants) VERSION else 1))
        app?.let { put("app", JsonPrimitive(it)) }
        put("symbols", buildJsonArray { catalog.symbols.forEach { add(SpecCodec.symbol(it, catalog.contracts[it.id])) } })
    }

    /** 合并候选定义后重新绑定全部符号引用，保留原契约及构建元数据；原spec不被修改。 */
    fun withSymbols(replacements: Collection<Symbol>): Spec {
        require(replacements.map { it.id }.distinct().size == replacements.size) { "候选符号id重复" }
        val original = encode()
        val replacement = Spec(null, Catalog(replacements.toList())).encode()
        val changed = replacement["symbols"]!!.jsonArray
            .associateBy { it.jsonObject["id"]!!.jsonPrimitive.content }
        val seen = HashSet<String>()
        val merged = original["symbols"]!!.jsonArray.map { item ->
            val old = item.jsonObject;val id = old["id"]!!.jsonPrimitive.content;seen += id
            val next = changed[id]?.jsonObject
            if(next==null)item else JsonObject(old + next)
        } + changed.filterKeys { it !in seen }.values
        val version = maxOf(original.getValue("dexadapt").jsonPrimitive.longOrNull!!,
            replacement.getValue("dexadapt").jsonPrimitive.longOrNull!!)
        return decode(JsonObject(original + ("symbols" to JsonArray(merged)) + ("dexadapt" to JsonPrimitive(version))))
    }

    companion object {
        const val VERSION = 2
        private val pretty = Json { prettyPrint = true; prettyPrintIndent = "  " }

        fun fromJson(text: String): Spec = decode(Json.parseToJsonElement(text).jsonObject)

        fun fromYaml(text: String): Spec = decode(json(Load(LoadSettings.builder().build()).loadFromString(text)).jsonObject)

        /** 按扩展名选格式（`.json` 以外都按 YAML 读）。 */
        fun read(file: java.io.File): Spec = if (file.name.endsWith(".json")) fromJson(file.readText()) else fromYaml(file.readText())

        fun decode(root: JsonObject): Spec {
            val version = root["dexadapt"]?.let {
                it.jsonPrimitive.longOrNull ?: error("dexadapt 必须是整数版本")
            } ?: 1L
            require(version <= VERSION) { "spec 格式版本 $version 比这个 DexAdapt 新（支持到 $VERSION）" }
            val app = root["app"]?.jsonPrimitive?.contentOrNull
            val items = root["symbols"]?.jsonArray ?: error("spec 里没有 symbols")
            val objects = items.map { it.jsonObject }
            require(version >= 2 || objects.none { it["method"]?.jsonObject?.get("variants") != null }) {
                "method.variants 需要 dexadapt: 2"
            }
            val contracts = objects.mapNotNull { o ->
                o["contract"]?.let { c -> o["id"]!!.jsonPrimitive.content to Json.decodeFromJsonElement(io.github.wzgrx.dexadapt.Contract.serializer(), c) }
            }.toMap()
            return Spec(app, Catalog(SpecCodec.symbols(objects), contracts))
        }

        private fun plain(e: JsonElement): Any? = when (e) {
            is JsonNull -> null
            is JsonObject -> e.mapValuesTo(LinkedHashMap()) { plain(it.value) }
            is JsonArray -> e.map(::plain)
            is JsonPrimitive -> if (e.isString) e.content else e.booleanOrNull ?: e.longOrNull ?: e.doubleOrNull ?: e.content
        }

        private fun json(v: Any?): JsonElement = when (v) {
            null -> JsonNull
            is Map<*, *> -> JsonObject(v.entries.associate { (k, x) -> k.toString() to json(x) })
            is List<*> -> JsonArray(v.map(::json))
            is Boolean -> JsonPrimitive(v)
            is Double, is Float -> JsonPrimitive(v.toDouble())
            is Number -> JsonPrimitive(v.toLong())
            else -> JsonPrimitive(v.toString())
        }
    }
}

/** 符号 ⇄ JSON。只写非默认的字段，读的时候缺的字段取默认值。 */
internal object SpecCodec {
    // ---- 写 ----

    fun symbol(s: Symbol, contract: io.github.wzgrx.dexadapt.Contract? = null): JsonObject = obj {
        put("id", JsonPrimitive(s.id))
        when (s) {
            is ClassSymbol -> put("class", cls(s.query))
            is MethodSymbol -> put("method", method(s.query))
            is FieldSymbol -> put("field", field(s.query))
        }
        if (s.pins.isNotEmpty()) put("pins", obj { s.pins.toSortedMap().forEach { (b, p) -> put(b, pin(p)) } })
        if (s.absentIn.isNotEmpty()) put("absentIn", strings(s.absentIn.sorted()))
        if (contract != null) put("contract", Json.encodeToJsonElement(io.github.wzgrx.dexadapt.Contract.serializer(), contract))
    }

    private fun type(t: TypeRef): String = when (t) {
        is TypeRef.Exact -> t.name
        is TypeRef.Of -> "$" + t.symbol.id
        is TypeRef.SuperOf -> "super($" + t.symbol.id + ")"
        TypeRef.Any -> "*"
    }

    private fun strings(list: Collection<String>) = JsonArray(list.map(::JsonPrimitive))
    private fun refs(list: List<Symbol>) = strings(list.map { "$" + it.id })

    private fun MutableMap<String, JsonElement>.opt(key: String, v: JsonElement?) { if (v != null) put(key, v) }
    private fun obj(block: MutableMap<String, JsonElement>.() -> Unit) = JsonObject(LinkedHashMap<String, JsonElement>().apply(block))

    private fun cls(q: ClassQuery) = obj {
        opt("name", q.name?.let(::JsonPrimitive))
        opt("packagePrefix", q.packagePrefix?.let(::JsonPrimitive))
        opt("superclass", q.superclass?.let { JsonPrimitive(type(it)) })
        if (q.interfaces.isNotEmpty()) put("interfaces", strings(q.interfaces.map(::type)))
        if (q.strings.isNotEmpty()) put("strings", strings(q.strings))
        if (q.fieldTypes.isNotEmpty()) put("fieldTypes", strings(q.fieldTypes.map(::type)))
        if (q.fieldNames.isNotEmpty()) put("fieldNames", strings(q.fieldNames))
        if (q.methods.isNotEmpty()) put("methods", JsonArray(q.methods.map(::shape)))
        opt("interface", q.isInterface?.let(::JsonPrimitive))
        opt("abstract", q.isAbstract?.let(::JsonPrimitive))
        opt("samePackageAs", q.samePackageAs?.let { JsonPrimitive(type(it)) })
        opt("fieldOf", q.fieldOf?.let { JsonPrimitive(type(it)) })
        if (q.instantiatedIn.isNotEmpty()) put("instantiatedIn", refs(q.instantiatedIn))
        if (q.declares.isNotEmpty()) put("declares", refs(q.declares))
        opt("sourceFile", q.sourceFile?.let(::JsonPrimitive))
        if (q.annotations.isNotEmpty()) put("annotations", strings(q.annotations))
    }

    private fun shape(s: MethodShape) = obj { shapeInto(s) }

    private fun MutableMap<String, JsonElement>.shapeInto(s: MethodShape) {
        opt("name", s.name?.let(::JsonPrimitive))
        opt("params", s.params?.let { p -> strings(p.map(::type)) })
        opt("returns", s.returns?.let { JsonPrimitive(type(it)) })
        if (s.strings.isNotEmpty()) put("strings", strings(s.strings))
        opt("static", s.isStatic?.let(::JsonPrimitive))
        if (s.numbers.isNotEmpty()) put("numbers", JsonArray(s.numbers.map(::JsonPrimitive)))
        if (s.annotations.isNotEmpty()) put("annotations", strings(s.annotations))
        if (s.opcodes.isNotEmpty()) put("opcodes", strings(s.opcodes))
    }

    private fun method(q: MethodQuery) = obj {
        opt("owner", q.owner?.let { JsonPrimitive(type(it)) })
        opt("packagePrefix", q.packagePrefix?.let(::JsonPrimitive))
        shapeInto(q.shape)
        if (q.variants.isNotEmpty()) put("variants", JsonArray(q.variants.map { v -> obj {
            shapeInto(v.shape)
            if (v.builds.isNotEmpty()) put("builds", strings(v.builds.sorted()))
            if (v.dexSha256.isNotEmpty()) put("dexSha256", strings(v.dexSha256.sorted()))
        } }))
        if (q.calls.isNotEmpty()) put("calls", refs(q.calls))
        if (q.callsExact.isNotEmpty()) put("callsExact", strings(q.callsExact))
        if (q.usesFields.isNotEmpty()) put("usesFields", refs(q.usesFields))
        if (q.onlyTheseFields) put("onlyTheseFields", JsonPrimitive(true))
        if (q.excludes.isNotEmpty()) put("excludes", refs(q.excludes))
        if (q.calledBy.isNotEmpty()) put("calledBy", refs(q.calledBy))
    }

    private fun field(q: FieldQuery) = obj {
        put("owner", JsonPrimitive(type(q.owner)))
        opt("name", q.name?.let(::JsonPrimitive))
        opt("type", q.type?.let { JsonPrimitive(type(it)) })
        opt("static", q.isStatic?.let(::JsonPrimitive))
        if (q.accessedIn.isNotEmpty()) put("accessedIn", refs(q.accessedIn))
    }

    private fun pin(p: Pin) = obj {
        when (p) {
            is Pin.Class -> put("class", JsonPrimitive(p.name))
            is Pin.Method -> put("method", JsonPrimitive("${p.owner}#${p.name}(${p.params.joinToString(",")})"))
            is Pin.Field -> put("field", JsonPrimitive("${p.owner}#${p.name}"))
        }
    }

    // ---- 读 ----

    fun symbols(items: List<JsonObject>): List<Symbol> {
        val byId = LinkedHashMap<String, JsonObject>()
        for (o in items) {
            val id = o["id"]?.jsonPrimitive?.contentOrNull ?: error("symbol without id: $o")
            require(byId.put(id, o) == null) { "duplicate symbol id $id" }
        }
        val built = HashMap<String, Symbol>()
        val building = LinkedHashSet<String>()

        fun build(id: String): Symbol {
            built[id]?.let { return it }
            val o = byId[id] ?: error("reference to unknown symbol \$$id")
            require(building.add(id)) { "dependency cycle: " + (building.dropWhile { it != id } + id).joinToString(" -> ") }
            fun ref(s: String): Symbol = build(s.removePrefix("$").also { require(s.startsWith("$")) { "$id: expected \$id, got $s" } })
            fun typ(s: String): TypeRef = when {
                s == "*" -> TypeRef.Any
                s.startsWith("super($") && s.endsWith(")") -> TypeRef.SuperOf(ref(s.removePrefix("super(").removeSuffix(")")) as ClassSymbol)
                s.startsWith("$") -> TypeRef.Of(ref(s) as ClassSymbol)
                else -> TypeRef.Exact(s)
            }
            fun JsonObject.str(k: String) = this[k]?.jsonPrimitive?.contentOrNull
            fun JsonObject.bool(k: String) = this[k]?.jsonPrimitive?.booleanOrNull
            fun JsonObject.list(k: String) = this[k]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
            fun JsonObject.methods(k: String) = list(k).map { ref(it) as MethodSymbol }
            fun JsonObject.shape() = MethodShape(
                name = str("name"), params = this["params"]?.jsonArray?.map { typ(it.jsonPrimitive.content) },
                returns = str("returns")?.let(::typ), strings = list("strings"), isStatic = bool("static"),
                numbers = this["numbers"]?.jsonArray?.map { it.jsonPrimitive.longOrNull ?: error("$id: numbers must be integers") }.orEmpty(),
                annotations = list("annotations"), opcodes = list("opcodes"),
            )
            val symbol: Symbol = when {
                o["class"] != null -> o["class"]!!.jsonObject.let { q ->
                    ClassSymbol(id, ClassQuery(
                        name = q.str("name"), packagePrefix = q.str("packagePrefix"), superclass = q.str("superclass")?.let(::typ),
                        interfaces = q.list("interfaces").map(::typ), strings = q.list("strings"), fieldTypes = q.list("fieldTypes").map(::typ),
                        fieldNames = q.list("fieldNames"), methods = q["methods"]?.jsonArray?.map { it.jsonObject.shape() }.orEmpty(),
                        isInterface = q.bool("interface"), isAbstract = q.bool("abstract"), samePackageAs = q.str("samePackageAs")?.let(::typ),
                        fieldOf = q.str("fieldOf")?.let(::typ), instantiatedIn = q.methods("instantiatedIn"), declares = q.methods("declares"),
                        sourceFile = q.str("sourceFile"), annotations = q.list("annotations"),
                    ))
                }
                o["method"] != null -> o["method"]!!.jsonObject.let { q ->
                    MethodSymbol(id, MethodQuery(
                        owner = q.str("owner")?.let(::typ), packagePrefix = q.str("packagePrefix"), shape = q.shape(),
                        calls = q.methods("calls"), callsExact = q.list("callsExact"),
                        usesFields = q.list("usesFields").map { ref(it) as FieldSymbol }, onlyTheseFields = q.bool("onlyTheseFields") == true,
                        excludes = q.methods("excludes"), calledBy = q.methods("calledBy"),
                        variants = q["variants"]?.jsonArray?.map { item ->
                            val variant = item.jsonObject
                            val unknown = variant.keys - setOf("name", "params", "returns", "strings", "static", "numbers",
                                "annotations", "opcodes", "builds", "dexSha256")
                            require(unknown.isEmpty()) { "$id: unsupported method variant keys: $unknown" }
                            MethodVariant(variant.shape(), variant.list("builds").toSet(), variant.list("dexSha256").toSet())
                        }.orEmpty(),
                    ))
                }
                o["field"] != null -> o["field"]!!.jsonObject.let { q ->
                    FieldSymbol(id, FieldQuery(
                        owner = typ(q.str("owner") ?: error("$id: field needs an owner")), name = q.str("name"),
                        type = q.str("type")?.let(::typ), isStatic = q.bool("static"), accessedIn = q.methods("accessedIn"),
                    ))
                }
                else -> error("$id: needs one of class / method / field")
            }
            o["pins"]?.jsonObject?.forEach { (build, p) -> symbol.pin(build, readPin(id, p.jsonObject)) }
            o["absentIn"]?.jsonArray?.let { a -> symbol.absentIn(*a.map { it.jsonPrimitive.content }.toTypedArray()) }
            building.remove(id)
            built[id] = symbol
            return symbol
        }
        return byId.keys.map(::build)
    }

    private fun readPin(id: String, o: JsonObject): Pin {
        o["class"]?.jsonPrimitive?.content?.let { return Pin.Class(it) }
        o["field"]?.jsonPrimitive?.content?.let { return Pin.Field(it.substringBefore('#'), it.substringAfter('#')) }
        o["method"]?.jsonPrimitive?.content?.let { m ->
            val owner = m.substringBefore('#')
            val name = m.substringAfter('#').substringBefore('(')
            val params = m.substringAfter('(').substringBefore(')').split(',').filter { it.isNotEmpty() }
            return Pin.Method(owner, name, params)
        }
        error("$id: bad pin $o")
    }
}
