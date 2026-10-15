package io.github.wzgrx.dexadapt.libs

import io.github.wzgrx.dexadapt.dexlib.ClassInfo
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.InputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * 开源库指纹库：每个库若干版本，记下各版本用到的“有特征的字符串”（错误消息、头字段、配置键……）和用到它的类。
 *
 * 为什么用字符串：R8 会内联、合并类、删参数，方法体整段的指纹在 App 里只剩两三成对得上；字符串常量即使被内联到
 * 调用者里也还在。实测（小红书 9.50.0）：OkHttp 3.14.9 的字符串 99% 都在，相邻版本之间靠“只有某些版本才有的字符串”区分。
 */
@Serializable
data class LibraryDb(val format: Int = FORMAT, val libraries: List<Library>) {
    @Serializable
    data class Library(
        /** Maven 坐标 `group:artifact`。 */
        val id: String,
        /** 显示名（OkHttp、Gson……）。 */
        val name: String,
        /** 收录的版本，旧的在前；最多 63 个（[Feature.v] 是位掩码）。 */
        val versions: List<String>,
        /** 各版本的类名并集。 */
        val classes: List<String>,
        /** [classes] 每个类在哪些版本里有（位掩码）。 */
        val classVersions: List<Long>,
        val features: List<Feature>,
    ) {
        fun mask(version: String): Long = 1L shl versions.indexOf(version).also { require(it >= 0) { "$id 没有版本 $version" } }

        /** 指定版本的特征。 */
        fun features(version: String): List<Feature> = mask(version).let { m -> features.filter { it.v and m != 0L } }

        /** 指定版本的类（有特征字符串的）。 */
        fun classCount(version: String): Int = features(version).flatMap { it.c }.toSet().size
    }

    /** 一个字符串：[v] 哪些版本有（位掩码），[c] 哪些类用到（[Library.classes] 的下标）。 */
    @Serializable
    data class Feature(val s: String, val v: Long, val c: List<Int>)

    fun toJson(): String = json.encodeToString(serializer(), this)

    fun write(file: File) {
        if (file.name.endsWith(".gz")) GZIPOutputStream(file.outputStream()).bufferedWriter().use { it.write(toJson()) } else file.writeText(toJson())
    }

    fun library(id: String): Library? = libraries.firstOrNull { it.id == id }

    /** 加入（或替换）一个库。 */
    fun with(lib: Library) = copy(libraries = libraries.filter { it.id != lib.id } + lib)

    companion object {
        const val FORMAT = 2
        const val MAX_VERSIONS = 63
        private val json = Json { ignoreUnknownKeys = true }

        fun fromJson(text: String): LibraryDb = json.decodeFromString(serializer(), text).also {
            require(it.format == FORMAT) { "指纹库格式 ${it.format}，这个版本只认 $FORMAT" }
        }

        fun read(file: File): LibraryDb = file.inputStream().use { read(it, file.name.endsWith(".gz")) }

        fun read(input: InputStream, gzip: Boolean): LibraryDb =
            fromJson((if (gzip) GZIPInputStream(input) else input).bufferedReader().readText())

        /** 随 DexAdapt 发布的指纹库（常用的 Android 开源库，`scripts/build-libdb.sh` 生成）。 */
        fun bundled(): LibraryDb? = LibraryDb::class.java.getResourceAsStream("/dexadapt/libs.json.gz")?.use { read(it, true) }

        /** 有特征的字符串：至少 6 个字符，并且不是单纯的标识符（含空格、标点等），这样的字符串很少在两个不相干的库里重复。 */
        fun distinctive(s: String): Boolean = s.length in 6..400 && s.any { !it.isLetterOrDigit() && it != '_' && it != '$' } && s.isNotBlank()

        /** 从各版本的类建一个库的指纹。 */
        fun build(id: String, name: String, versions: List<Pair<String, List<ClassInfo>>>): Library {
            require(versions.size in 1..MAX_VERSIONS) { "$id：版本数 ${versions.size} 不在 1..$MAX_VERSIONS" }
            val classIds = LinkedHashMap<String, Int>()
            val classMasks = ArrayList<Long>()
            val masks = LinkedHashMap<String, Long>()
            val users = HashMap<String, MutableSet<Int>>()
            versions.forEachIndexed { i, (_, classes) ->
                for (c in classes) {
                    val strings = c.methods.flatMap { it.strings }.filter(::distinctive).toSet()
                    if (strings.isEmpty()) continue
                    val ci = classIds.getOrPut(c.name) { classMasks += 0L; classIds.size }
                    classMasks[ci] = classMasks[ci] or (1L shl i)
                    for (s in strings) {
                        masks.merge(s, 1L shl i, Long::or)
                        users.getOrPut(s) { HashSet() } += ci
                    }
                }
            }
            val features = masks.entries.sortedBy { it.key }.map { (s, v) -> Feature(s, v, users.getValue(s).sorted()) }
            return Library(id, name, versions.map { it.first }, classIds.keys.toList(), classMasks, features)
        }
    }
}
