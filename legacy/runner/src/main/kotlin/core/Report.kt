package core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.security.MessageDigest

object Report {

    fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { ins ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = ins.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    fun build(spec: SpecFile, apk: File, label: String, results: List<TargetResult>, durationMs: Long): JsonObject =
        buildJsonObject {
            put("schema", 1)
            put("app", spec.app)
            put("label", label)
            putJsonObject("apk") {
                put("file", apk.name)
                put("size", apk.length())
                put("sha256", sha256(apk))
            }
            put("durationMs", durationMs)
            putJsonArray("targets") {
                results.forEach { r ->
                    addJsonObject {
                        put("key", r.key)
                        put("type", r.type)
                        put("status", r.status.name)
                        put("hits", r.hits)
                        putJsonArray("resolved") { r.resolved.forEach { add(it) } }
                        put("ms", r.ms)
                        r.detail?.let { put("detail", it) }
                    }
                }
            }
        }

    fun write(obj: JsonObject, out: File) {
        out.parentFile?.mkdirs()
        out.writeText(Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), obj))
    }
}
