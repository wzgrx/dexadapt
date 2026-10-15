package io.github.wzgrx.dexadapt.diff

import io.github.wzgrx.dexadapt.apk.Elf
import java.io.File
import java.util.zip.ZipFile

/**
 * 原生层的差异（设计 10.10）：同一个 `.so` 在新旧两版的导出符号、依赖库。Hook 原生函数的模块最怕导出的符号没了、
 * JNI 函数（`Java_…`）改了名；只看一个 ABI（默认 arm64-v8a，没有就取两边都有的第一个）。
 */
object NativeDiff {
    private val PREFERRED = listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")

    /** [old]、[new] 是两个版本的安装包文件（base 和拆分包）；结果的 changed 每行一个库。 */
    fun of(old: List<File>, new: List<File>, limit: Int = 12): Delta {
        val a = libs(old)
        val b = libs(new)
        val abi = PREFERRED.firstOrNull { it in a.keys && it in b.keys } ?: (a.keys intersect b.keys).firstOrNull() ?: return Delta()
        val la = a.getValue(abi)
        val lb = b.getValue(abi)
        val changed = ArrayList<String>()
        for (name in (la.keys intersect lb.keys).sorted()) {
            val x = runCatching { Elf.parse(la.getValue(name)()) }.getOrNull() ?: continue
            val y = runCatching { Elf.parse(lb.getValue(name)()) }.getOrNull() ?: continue
            val ex = x.exported.toSet()
            val ey = y.exported.toSet()
            // JNI 函数（Java_…）排前面：原生 Hook 和 Java 层的 native 方法都靠它们
            val jniFirst = compareBy<String>({ !it.startsWith("Java_") }, { it })
            val added = (ey - ex).sortedWith(jniFirst)
            val removed = (ex - ey).sortedWith(jniFirst)
            val neededAdded = y.needed - x.needed.toSet()
            val neededRemoved = x.needed - y.needed.toSet()
            if (added.isEmpty() && removed.isEmpty() && neededAdded.isEmpty() && neededRemoved.isEmpty()) continue
            changed += buildString {
                append(name).append("：")
                val parts = ArrayList<String>()
                if (removed.isNotEmpty()) parts += "去掉导出 ${removed.size}（${sample(removed, limit)}）"
                if (added.isNotEmpty()) parts += "新增导出 ${added.size}（${sample(added, limit)}）"
                val jniGone = removed.count { it.startsWith("Java_") }
                if (jniGone > 0) parts += "其中 JNI 函数去掉 $jniGone 个"
                if (neededAdded.isNotEmpty()) parts += "新依赖 ${neededAdded.joinToString()}"
                if (neededRemoved.isNotEmpty()) parts += "不再依赖 ${neededRemoved.joinToString()}"
                append(parts.joinToString("；"))
            }
        }
        return Delta(changed = changed)
    }

    private fun sample(list: List<String>, n: Int) = list.take(n).joinToString(", ") + if (list.size > n) " …" else ""

    /** ABI → 库名 → 读字节的函数（按需读，不把几十 MB 的库全放内存）。 */
    private fun libs(files: List<File>): Map<String, Map<String, () -> ByteArray>> {
        val out = LinkedHashMap<String, MutableMap<String, () -> ByteArray>>()
        for (f in files) ZipFile(f).use { z ->
            for (e in z.entries()) {
                val parts = e.name.split('/')
                if (parts.size != 3 || parts[0] != "lib" || !e.name.endsWith(".so")) continue
                out.getOrPut(parts[1]) { LinkedHashMap() }.putIfAbsent(parts[2]) { ZipFile(f).use { zz -> zz.getInputStream(zz.getEntry(e.name)).readBytes() } }
            }
        }
        return out
    }
}
