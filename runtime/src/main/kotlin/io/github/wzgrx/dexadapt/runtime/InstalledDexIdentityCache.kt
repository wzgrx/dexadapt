package io.github.wzgrx.dexadapt.runtime

import io.github.wzgrx.dexadapt.resolve.DexDigest
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes

/** 仅对同一份系统安装记录及同一批未变动的文件复用摘要；不持久化快速身份。 */
class InstalledDexIdentityCache(
    private val snapshot: (String) -> Installation?,
    private val digest: (List<File>) -> String = DexDigest::of,
) {
    data class Installation(val versionCode: Long, val lastUpdateTime: Long, val files: List<File>)
    private data class FileState(val path: String, val bytes: Long, val modified: java.nio.file.attribute.FileTime, val key: Any?)
    private data class Snapshot(val installation: Installation, val files: List<FileState>)
    private data class Entry(val snapshot: Snapshot, val identity: HostCodeIdentity)
    private val entries = HashMap<String, Entry>()

    @Synchronized fun current(packageName: String): HostCodeIdentity? {
        val before = capture(packageName) ?: run {
            entries.remove(packageName)
            return null
        }
        val prior = entries[packageName]
        if (prior?.snapshot == before && before.files.all { it.key != null }) return prior.identity
        entries.remove(packageName)
        val code = try {
            digest(before.installation.files)
        } catch (_: java.io.IOException) {
            return null
        }
        if (capture(packageName) != before) return null
        return HostCodeIdentity(packageName, before.installation.versionCode, code).also {
            entries[packageName] = Entry(before, it)
        }
    }

    @Synchronized fun invalidate(packageName: String) { entries.remove(packageName) }

    private fun capture(packageName: String): Snapshot? {
        val installation = snapshot(packageName)?.let { it.copy(files = it.files.toList()) } ?: return null
        val files = try {
            installation.files.map { f ->
                val a = Files.readAttributes(f.toPath(), BasicFileAttributes::class.java)
                FileState(f.absolutePath, a.size(), a.lastModifiedTime(), a.fileKey())
            }
        } catch (_: java.io.IOException) {
            return null
        }
        return Snapshot(installation, files)
    }
}
