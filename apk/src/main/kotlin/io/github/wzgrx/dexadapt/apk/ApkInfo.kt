package io.github.wzgrx.dexadapt.apk

import com.android.apksig.ApkVerifier
import io.github.wzgrx.dexadapt.Hex
import kotlinx.serialization.Serializable
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

/** 一个安装包（base.apk 加拆分包）的档案：版本、签名、清单、代码和原生库。纯数据，可以序列化进报告。 */
@Serializable
data class ApkInfo(
    val packageName: String,
    val versionCode: Long,
    val versionName: String,
    val minSdk: Int?,
    val targetSdk: Int?,
    val compileSdk: Int?,
    /** 安装包里的每个文件（base 在前）：文件名和大小。 */
    val files: List<FileEntry>,
    val signers: List<Signer>,
    /** 签名是否通过校验（apksig）。 */
    val verified: Boolean,
    val signatureSchemes: List<String>,
    /** Google Play 的 source stamp（有就说明是 Play 下发的）。 */
    val sourceStamp: Boolean,
    /** base 里 META-INF 下的签名文件（v1 签名、Google Play 的 GOOGPLAY.*、镜像站重签留下的 BNDLTOOL.* 等）。 */
    val signatureFiles: List<String> = emptyList(),
    val manifest: Manifest,
    val dex: List<FileEntry>,
    /** classes*.dex 以外的 dex（assets 等处，运行时另外加载的插件 / 加固壳）。 */
    val extraDex: List<FileEntry> = emptyList(),
    val nativeLibs: List<NativeLib>,
    /** assets、res 等其它文件个数和总大小。 */
    val otherFiles: Int,
    val otherBytes: Long,
) {
    @Serializable
    data class FileEntry(val name: String, val size: Long)

    @Serializable
    data class Signer(val subject: String, val sha256: String)

    @Serializable
    data class NativeLib(val abi: String, val name: String, val size: Long, val crc: Long)

    val abis: Set<String> get() = nativeLibs.mapTo(sortedSetOf()) { it.abi }

    /** Google Play 下发的包（带 Play 的签名文件或 source stamp）。 */
    val fromPlay: Boolean get() = sourceStamp || signatureFiles.any { it.startsWith("META-INF/GOOGPLAY.") }
    val label: String get() = "$packageName $versionName ($versionCode)"
}

@Serializable
data class Manifest(
    val usesPermissions: List<String>,
    val permissions: List<String>,
    val components: List<Component>,
    /** application 下的 meta-data：名字 → 值。 */
    val metaData: Map<String, String>,
    val applicationClass: String?,
    val debuggable: Boolean,
) {
    @Serializable
    data class Component(
        /** activity / activity-alias / service / receiver / provider */
        val kind: String,
        val name: String,
        val exported: Boolean?,
        val process: String?,
        val permission: String?,
        val authorities: String?,
        val actions: List<String>,
        val categories: List<String>,
        /** intent-filter 里的 data：scheme://host/path 这类，拼成一行。 */
        val data: List<String>,
    )

    companion object {
        fun of(root: XmlElement): Manifest {
            val pkg = root.attrs["package"].orEmpty()
            fun qualify(n: String?) = when {
                n == null -> ""
                n.startsWith(".") -> pkg + n
                '.' !in n -> "$pkg.$n"
                else -> n
            }
            val app = root.child("application")
            val kinds = setOf("activity", "activity-alias", "service", "receiver", "provider")
            val components = app?.children.orEmpty().filter { it.name in kinds }.map { c ->
                val filters = c.all("intent-filter")
                Component(
                    kind = c.name, name = qualify(c.attrs["name"]), exported = c.attrs["exported"]?.toBooleanStrictOrNull(),
                    process = c.attrs["process"], permission = c.attrs["permission"], authorities = c.attrs["authorities"],
                    actions = filters.flatMap { f -> f.all("action").mapNotNull { it.attrs["name"] } }.distinct(),
                    categories = filters.flatMap { f -> f.all("category").mapNotNull { it.attrs["name"] } }.distinct(),
                    data = filters.flatMap { f ->
                        f.all("data").map { d ->
                            buildString {
                                d.attrs["scheme"]?.let { append(it).append("://") }
                                d.attrs["host"]?.let(::append)
                                (d.attrs["path"] ?: d.attrs["pathPrefix"] ?: d.attrs["pathPattern"])?.let(::append)
                                d.attrs["mimeType"]?.let { if (isNotEmpty()) append(' '); append(it) }
                            }
                        }.filter { it.isNotEmpty() }
                    }.distinct(),
                )
            }
            return Manifest(
                usesPermissions = root.all("uses-permission").mapNotNull { it.attrs["name"] }.distinct().sorted(),
                permissions = root.all("permission").mapNotNull { it.attrs["name"] }.distinct().sorted(),
                components = components,
                metaData = app?.all("meta-data").orEmpty().associate { (it.attrs["name"] ?: "") to (it.attrs["value"] ?: it.attrs["resource"] ?: "") },
                applicationClass = app?.attrs?.get("name")?.let(::qualify),
                debuggable = app?.attrs?.get("debuggable") == "true",
            )
        }
    }
}

object ApkInspector {
    /** 检查一组安装包文件（[ApkSet.files]，base 在前）。 */
    fun inspect(set: ApkSet): ApkInfo {
        val base = set.files.first()
        val manifestXml = ZipFile(base).use { z -> z.getInputStream(z.getEntry("AndroidManifest.xml") ?: error("$base has no AndroidManifest.xml")).readBytes() }
        val root = BinaryXml.parse(manifestXml)
        val sdk = root.child("uses-sdk")
        val dex = ArrayList<ApkInfo.FileEntry>()
        val extraDex = ArrayList<ApkInfo.FileEntry>()
        val libs = ArrayList<ApkInfo.NativeLib>()
        var otherFiles = 0
        var otherBytes = 0L
        val sigFiles = ArrayList<String>()
        for (f in set.files) ZipFile(f).use { z ->
            for (e in z.entries()) {
                if (e.isDirectory) continue
                val n = e.name
                when {
                    f == base && n.matches(Regex("classes\\d*\\.dex")) -> dex += ApkInfo.FileEntry(n, e.size)
                    n.endsWith(".dex") -> extraDex += ApkInfo.FileEntry(if (f == base) n else "${f.name}!$n", e.size)
                    n.startsWith("lib/") && n.endsWith(".so") -> {
                        val parts = n.split('/')
                        if (parts.size >= 3) libs += ApkInfo.NativeLib(parts[1], parts.drop(2).joinToString("/"), e.size, e.crc)
                    }
                    n.startsWith("META-INF/") -> if (f == base && n.matches(Regex("META-INF/[^/]+\\.(RSA|DSA|EC|SF)"))) sigFiles += n
                    else -> { otherFiles++; otherBytes += e.size }
                }
            }
        }
        val sig = runCatching { ApkVerifier.Builder(base).build().verify() }.getOrNull()
        val signers = sig?.signerCertificates.orEmpty().map { c ->
            ApkInfo.Signer(c.subjectX500Principal.name, Hex.of(MessageDigest.getInstance("SHA-256").digest(c.encoded)))
        }
        val schemes = buildList {
            if (sig?.isVerifiedUsingV1Scheme == true) add("v1")
            if (sig?.isVerifiedUsingV2Scheme == true) add("v2")
            if (sig?.isVerifiedUsingV3Scheme == true) add("v3")
            if (sig?.isVerifiedUsingV31Scheme == true) add("v3.1")
            if (sig?.isVerifiedUsingV4Scheme == true) add("v4")
        }
        return ApkInfo(
            packageName = root.attrs["package"].orEmpty(),
            versionCode = root.attrs["versionCode"]?.toLongOrNull() ?: 0,
            versionName = root.attrs["versionName"].orEmpty(),
            minSdk = sdk?.attrs?.get("minSdkVersion")?.toIntOrNull(),
            targetSdk = sdk?.attrs?.get("targetSdkVersion")?.toIntOrNull(),
            compileSdk = root.attrs["compileSdkVersion"]?.toIntOrNull(),
            files = set.files.map { ApkInfo.FileEntry(it.name, it.length()) },
            signers = signers,
            verified = sig?.isVerified == true,
            signatureSchemes = schemes,
            sourceStamp = sig?.sourceStampInfo != null,
            signatureFiles = sigFiles.sorted(),
            manifest = Manifest.of(root),
            dex = dex.sortedWith(compareBy({ it.name.length }, { it.name })),
            extraDex = extraDex,
            nativeLibs = libs.sortedWith(compareBy({ it.abi }, { it.name })),
            otherFiles = otherFiles,
            otherBytes = otherBytes,
        )
    }
}

/**
 * 一个安装包：单个 `.apk`、拆分包目录（base.apk + split_*.apk）、或打包好的 `.apks` / `.xapk` / `.apkm`（zip 里是若干 apk）。
 * [files] 第一个是 base（带清单和代码的那个）。解开的临时文件放在 [workDir]。
 */
class ApkSet private constructor(val source: File, val files: List<File>) {
    val base: File get() = files.first()

    companion object {
        fun open(path: File, workDir: File = File(System.getProperty("java.io.tmpdir"), "dexadapt-apks")): ApkSet {
            if (path.isDirectory) {
                val apks = path.listFiles { f -> f.isFile && f.name.endsWith(".apk") }.orEmpty().toList()
                require(apks.isNotEmpty()) { "$path 里没有 apk" }
                return ApkSet(path, order(apks))
            }
            val inner = ZipFile(path).use { z -> z.entries().asSequence().filter { !it.isDirectory && it.name.endsWith(".apk") && '/' !in it.name }.map { it.name }.toList() }
            val hasManifest = ZipFile(path).use { it.getEntry("AndroidManifest.xml") != null }
            if (hasManifest || inner.isEmpty()) return ApkSet(path, listOf(path))
            // .apks / .xapk：解到以内容摘要命名的目录，重复打开不再解。
            val dir = File(workDir, path.nameWithoutExtension.replace(Regex("[^A-Za-z0-9._-]"), "_") + "-" + path.length())
            if (!dir.isDirectory || inner.any { !File(dir, it).isFile }) {
                dir.mkdirs()
                ZipFile(path).use { z -> inner.forEach { n -> z.getInputStream(z.getEntry(n)).use { input -> File(dir, n).outputStream().use(input::copyTo) } } }
            }
            return ApkSet(path, order(inner.map { File(dir, it) }))
        }

        /** base 在前：名字叫 base.apk 的，或者带代码（classes.dex）的那个；其余按名字排。 */
        private fun order(apks: List<File>): List<File> {
            val base = apks.firstOrNull { it.name == "base.apk" }
                ?: apks.firstOrNull { f -> ZipFile(f).use { it.getEntry("classes.dex") != null } }
                ?: apks.first()
            return listOf(base) + apks.filter { it != base }.sortedBy { it.name }
        }
    }
}
