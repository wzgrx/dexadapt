package io.github.wzgrx.dexadapt.libs

/** 随 DexAdapt 发布的指纹库收录哪些库（Android App 里最常见、最常被 Hook 的）。 */
object KnownLibraries {
    data class Entry(val group: String, val artifact: String, val name: String, val minVersion: String? = null, val maxVersion: String? = null) {
        val id get() = "$group:$artifact"
    }

    val ALL = listOf(
        Entry("com.squareup.okhttp3", "okhttp", "OkHttp", "3.0.0"),
        Entry("com.squareup.okio", "okio", "Okio", "1.13.0"),
        Entry("com.squareup.okio", "okio-jvm", "Okio（3.x）"),
        Entry("com.squareup.retrofit2", "retrofit", "Retrofit"),
        Entry("com.google.code.gson", "gson", "Gson", "2.5"),
        Entry("com.squareup.moshi", "moshi", "Moshi"),
        // 2.x 的 fastjson 是 fastjson2 的兼容壳，App 里常见的是 1.2.x
        Entry("com.alibaba", "fastjson", "Fastjson", "1.2.40", maxVersion = "1.2.83"),
        Entry("io.reactivex.rxjava2", "rxjava", "RxJava 2"),
        Entry("io.reactivex.rxjava3", "rxjava", "RxJava 3"),
        Entry("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm", "Kotlin 协程"),
        Entry("org.jetbrains.kotlin", "kotlin-stdlib", "Kotlin 标准库", "1.4.0"),
        Entry("com.github.bumptech.glide", "glide", "Glide", "4.0.0"),
        Entry("com.airbnb.android", "lottie", "Lottie", "3.0.0"),
        Entry("com.google.protobuf", "protobuf-javalite", "Protobuf Lite"),
        Entry("com.google.protobuf", "protobuf-java", "Protobuf", "3.5.0"),
        Entry("org.greenrobot", "eventbus", "EventBus", "3.0.0"),
        Entry("com.tencent", "mmkv", "MMKV"),
        Entry("com.google.android.exoplayer", "exoplayer-core", "ExoPlayer", "2.10.0"),
        Entry("androidx.media3", "media3-exoplayer", "Media3 ExoPlayer"),
    )

    /** 每个库最多收录的版本数。 */
    const val MAX_VERSIONS = 40

    /**
     * 收录哪些版本：正式版、在 [Entry.minVersion] 和 [Entry.maxVersion] 之间，每个“主.次”版本取最后一个修订版（修订版之间字符串几乎不变）；
     * 只有一两个“主.次”版本的库（Fastjson 一直是 1.2.x）改为逐个修订版。最多 [MAX_VERSIONS] 个，多了丢最旧的。
     */
    fun pick(entry: Entry, published: List<String>): List<String> {
        val stable = published.filter(Maven::stable)
            .filter { v -> entry.minVersion == null || Maven.VersionOrder.compare(v, entry.minVersion) >= 0 }
            .filter { v -> entry.maxVersion == null || Maven.VersionOrder.compare(v, entry.maxVersion) <= 0 }
            .sortedWith(Maven.VersionOrder)
        val minors = stable.groupBy { it.split('.').take(2).joinToString(".") }
        val chosen = if (minors.size < 4) stable else minors.values.map { it.last() }
        return chosen.takeLast(MAX_VERSIONS)
    }

    /** 下载各版本、建指纹（[log] 报进度）。 */
    fun build(maven: Maven, entries: List<Entry> = ALL, log: (String) -> Unit = {}): LibraryDb {
        var db = LibraryDb(libraries = emptyList())
        for (e in entries) {
            val versions = pick(e, maven.versions(e.group, e.artifact))
            if (versions.isEmpty()) {
                log("${e.id}: 没有可收录的版本，跳过")
                continue
            }
            log("${e.id}: ${versions.size} 个版本（${versions.first()} ～ ${versions.last()}）")
            val classes = versions.map { v -> v to JarIndex.load(maven.artifact(e.group, e.artifact, v)) }
            db = db.with(LibraryDb.build(e.id, e.name, classes))
        }
        return db
    }
}
