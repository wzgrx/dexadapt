package io.github.wzgrx.dexadapt.diff

import io.github.wzgrx.dexadapt.apk.ApkInfo
import io.github.wzgrx.dexadapt.apk.Manifest
import kotlinx.serialization.Serializable

/** 增删改的一组名字。 */
@Serializable
data class Delta(val added: List<String> = emptyList(), val removed: List<String> = emptyList(), val changed: List<String> = emptyList()) {
    val empty: Boolean get() = added.isEmpty() && removed.isEmpty() && changed.isEmpty()

    companion object {
        fun of(old: Collection<String>, new: Collection<String>): Delta {
            val o = old.toSet()
            val n = new.toSet()
            return Delta((n - o).sorted(), (o - n).sorted())
        }
    }
}

/** 包和清单这一层的差异（不看代码）。 */
@Serializable
data class PackageDiff(
    val old: String,
    val new: String,
    /** 版本号、SDK 等字段的变化：字段 → “旧 → 新”。 */
    val fields: Map<String, String>,
    /** 签名证书是否相同；不同时说明可能被重签（镜像站、改包）或换了签名。 */
    val sameSigner: Boolean,
    val signatureFiles: Delta,
    val files: Delta,
    val dexCount: Pair<Int, Int>,
    val dexBytes: Pair<Long, Long>,
    val extraDex: Delta,
    /** 原生库：`abi/名字`；改了的是大小或 CRC 变了。 */
    val nativeLibs: Delta,
    val usesPermissions: Delta,
    val permissions: Delta,
    /** 组件：`kind 名字`；改了的写明哪里变（导出、进程、权限、intent-filter）。 */
    val components: Delta,
    /** 外部能唤起的 scheme://host。 */
    val deepLinks: Delta,
    val metaData: Delta,
) {
    companion object {
        fun of(a: ApkInfo, b: ApkInfo): PackageDiff {
            val fields = LinkedHashMap<String, String>()
            fun field(name: String, x: Any?, y: Any?) { if (x != y) fields[name] = "$x → $y" }
            field("包名", a.packageName, b.packageName)
            field("versionCode", a.versionCode, b.versionCode)
            field("versionName", a.versionName, b.versionName)
            field("minSdk", a.minSdk, b.minSdk)
            field("targetSdk", a.targetSdk, b.targetSdk)
            field("compileSdk", a.compileSdk, b.compileSdk)
            field("签名方案", a.signatureSchemes.joinToString(), b.signatureSchemes.joinToString())
            field("签名校验", a.verified, b.verified)
            field("Application", a.manifest.applicationClass, b.manifest.applicationClass)
            field("debuggable", a.manifest.debuggable, b.manifest.debuggable)
            field("ABI", a.abis.joinToString(), b.abis.joinToString())
            fun libs(i: ApkInfo) = i.nativeLibs.associate { "${it.abi}/${it.name}" to "${it.size}:${it.crc}" }
            val la = libs(a)
            val lb = libs(b)
            fun comps(m: Manifest) = m.components.associateBy { "${it.kind} ${it.name}" }
            val ca = comps(a.manifest)
            val cb = comps(b.manifest)
            val changedComponents = ca.keys.intersect(cb.keys).sorted().mapNotNull { k ->
                val x = ca.getValue(k)
                val y = cb.getValue(k)
                val what = buildList {
                    if (x.exported != y.exported) add("导出 ${x.exported} → ${y.exported}")
                    if (x.process != y.process) add("进程 ${x.process} → ${y.process}")
                    if (x.permission != y.permission) add("权限 ${x.permission} → ${y.permission}")
                    if (x.actions.toSet() != y.actions.toSet() || x.data.toSet() != y.data.toSet()) add("intent-filter 变了")
                }
                if (what.isEmpty()) null else "$k：${what.joinToString("，")}"
            }
            fun links(m: Manifest) = m.components.flatMap { it.data.filter { d -> "://" in d } }
            return PackageDiff(
                old = a.label, new = b.label, fields = fields,
                sameSigner = a.signers.map { it.sha256 }.toSet() == b.signers.map { it.sha256 }.toSet(),
                signatureFiles = Delta.of(a.signatureFiles, b.signatureFiles),
                files = Delta.of(a.files.map { it.name }, b.files.map { it.name }),
                dexCount = a.dex.size to b.dex.size,
                dexBytes = a.dex.sumOf { it.size } to b.dex.sumOf { it.size },
                extraDex = Delta.of(a.extraDex.map { it.name }, b.extraDex.map { it.name }),
                nativeLibs = Delta.of(la.keys, lb.keys).copy(changed = la.keys.intersect(lb.keys).filter { la[it] != lb[it] }.sorted()),
                usesPermissions = Delta.of(a.manifest.usesPermissions, b.manifest.usesPermissions),
                permissions = Delta.of(a.manifest.permissions, b.manifest.permissions),
                components = Delta.of(ca.keys, cb.keys).copy(changed = changedComponents),
                deepLinks = Delta.of(links(a.manifest), links(b.manifest)),
                metaData = Delta.of(a.manifest.metaData.map { "${it.key}=${it.value}" }, b.manifest.metaData.map { "${it.key}=${it.value}" }),
            )
        }
    }
}
