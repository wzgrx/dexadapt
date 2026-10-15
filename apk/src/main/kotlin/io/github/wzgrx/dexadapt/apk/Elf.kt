package io.github.wzgrx.dexadapt.apk

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * ELF 的 `.so`（设计 10.10 原生层）：读 soname、依赖的库（DT_NEEDED）、动态符号表里导出和导入的函数 / 变量。
 * 给原生 Hook 的模块看升级后原生库的接口变化（导出的符号没了、换了名）。只读需要的几张表，不反汇编。
 *
 * 支持 32 / 64 位、大 / 小端。节头被 strip 掉时退回用 PT_DYNAMIC（DT_SYMTAB / DT_STRTAB / DT_GNU_HASH）。
 */
class Elf private constructor(
    val is64: Boolean,
    /** soname（DT_SONAME），没有时为 null。 */
    val soname: String?,
    /** 依赖的共享库（DT_NEEDED）。 */
    val needed: List<String>,
    /** 动态符号表里的符号。 */
    val symbols: List<Sym>,
) {
    /** [name] 导出的（本库定义的）函数和变量；[imported] 为真表示引用别的库的符号（UNDEF）。 */
    data class Sym(val name: String, val type: Type, val binding: Binding, val imported: Boolean) {
        enum class Type { NOTYPE, OBJECT, FUNC, SECTION, FILE, COMMON, TLS, OTHER }
        enum class Binding { LOCAL, GLOBAL, WEAK, OTHER }
    }

    /** 本库导出的全局 / 弱符号名字（给别的库用的）。 */
    val exported: List<String> get() = symbols.filter { !it.imported && it.binding != Sym.Binding.LOCAL && it.name.isNotEmpty() }.map { it.name }

    /** 从别的库引用的符号名字。 */
    val imported: List<String> get() = symbols.filter { it.imported && it.name.isNotEmpty() }.map { it.name }

    companion object {
        fun parse(bytes: ByteArray): Elf {
            require(bytes.size >= 64 && bytes[0] == 0x7f.toByte() && bytes[1] == 'E'.code.toByte() && bytes[2] == 'L'.code.toByte() && bytes[3] == 'F'.code.toByte()) {
                "not an ELF file"
            }
            val is64 = bytes[4].toInt() == 2
            val buf = ByteBuffer.wrap(bytes).order(if (bytes[5].toInt() == 2) ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN)
            // ELF 头：e_shoff、e_shentsize、e_shnum、e_shstrndx（64 位的偏移）
            val shoff = if (is64) buf.getLong(0x28) else buf.getInt(0x20).toLong()
            val phoff = if (is64) buf.getLong(0x20) else buf.getInt(0x1c).toLong()
            val phentsize = buf.getShort(if (is64) 0x36 else 0x2a).toInt() and 0xffff
            val phnum = buf.getShort(if (is64) 0x38 else 0x2c).toInt() and 0xffff
            val shentsize = buf.getShort(if (is64) 0x3a else 0x2e).toInt() and 0xffff
            val shnum = buf.getShort(if (is64) 0x3c else 0x30).toInt() and 0xffff

            // 优先用节头找 .dynsym / .dynstr / .dynamic
            var dynsym: Pair<Long, Long>? = null // (offset, size)
            var dynamic: Pair<Long, Long>? = null
            if (shoff > 0 && shnum > 0) {
                for (i in 0 until shnum) {
                    val sh = shoff + i.toLong() * shentsize
                    val type = buf.getInt((sh + 4).toInt())
                    val off = if (is64) buf.getLong((sh + 0x18).toInt()) else buf.getInt((sh + 0x10).toInt()).toLong()
                    val size = if (is64) buf.getLong((sh + 0x20).toInt()) else buf.getInt((sh + 0x14).toInt()).toLong()
                    when (type) {
                        11 -> dynsym = off to size // SHT_DYNSYM
                        6 -> dynamic = off to size // SHT_DYNAMIC
                    }
                }
            }

            // 用 PT_DYNAMIC 补全（没有节头，或要拿准确的 strtab）
            val dynEntries = ArrayList<Pair<Long, Long>>() // (tag, value)
            if (dynamic == null && phoff > 0) {
                for (i in 0 until phnum) {
                    val ph = phoff + i.toLong() * phentsize
                    if (buf.getInt(ph.toInt()) == 2) { // PT_DYNAMIC
                        val off = if (is64) buf.getLong((ph + 0x08).toInt()) else buf.getInt((ph + 0x04).toInt()).toLong()
                        val size = if (is64) buf.getLong((ph + 0x28).toInt()) else buf.getInt((ph + 0x14).toInt()).toLong()
                        dynamic = off to size
                    }
                }
            }
            // 基址映射：虚拟地址 → 文件偏移（PT_LOAD）
            val loads = ArrayList<Triple<Long, Long, Long>>() // (vaddr, offset, filesz)
            if (phoff > 0) for (i in 0 until phnum) {
                val ph = phoff + i.toLong() * phentsize
                if (buf.getInt(ph.toInt()) == 1) { // PT_LOAD
                    val off = if (is64) buf.getLong((ph + 0x08).toInt()) else buf.getInt((ph + 0x04).toInt()).toLong()
                    val vaddr = if (is64) buf.getLong((ph + 0x10).toInt()) else buf.getInt((ph + 0x08).toInt()).toLong()
                    val filesz = if (is64) buf.getLong((ph + 0x20).toInt()) else buf.getInt((ph + 0x10).toInt()).toLong()
                    loads += Triple(vaddr, off, filesz)
                }
            }
            fun toOffset(vaddr: Long): Long {
                for ((va, off, sz) in loads) if (vaddr in va until va + sz) return off + (vaddr - va)
                return vaddr // 没有 PT_LOAD（有节头时偏移就是文件偏移）
            }

            // 读 .dynamic 取 DT_NEEDED / DT_SONAME / DT_STRTAB / DT_SYMTAB / DT_GNU_HASH
            var strtabVa = 0L; var symtabVa = 0L; var gnuHashVa = 0L; var strsz = 0L
            val neededOffsets = ArrayList<Long>()
            var sonameOffset = -1L
            dynamic?.let { (off, size) ->
                val entSize = if (is64) 16 else 8
                var p = off
                val end = off + size
                while (p + entSize <= end) {
                    val tag = if (is64) buf.getLong(p.toInt()) else buf.getInt(p.toInt()).toLong()
                    val value = if (is64) buf.getLong((p + 8).toInt()) else buf.getInt((p + 4).toInt()).toLong()
                    when (tag.toInt()) {
                        0 -> { p = end; continue } // DT_NULL
                        1 -> neededOffsets += value // DT_NEEDED
                        14 -> sonameOffset = value // DT_SONAME
                        5 -> strtabVa = value // DT_STRTAB
                        6 -> symtabVa = value // DT_SYMTAB
                        10 -> strsz = value // DT_STRSZ
                        0x6ffffef5 -> gnuHashVa = value // DT_GNU_HASH
                    }
                    p += entSize
                }
            }
            // 字符串表一律从 DT_STRTAB 取（节头里 SHT_STRTAB 有好几张，分不清哪张是 .dynstr）
            val strtabOff = toOffset(strtabVa)
            fun str(base: Long, idx: Long): String {
                var i = (base + idx).toInt()
                if (i < 0 || i >= bytes.size) return ""
                val start = i
                while (i < bytes.size && bytes[i].toInt() != 0) i++
                return String(bytes, start, i - start, Charsets.UTF_8)
            }

            val soname = if (sonameOffset >= 0) str(strtabOff, sonameOffset) else null
            val needed = neededOffsets.map { str(strtabOff, it) }

            // 符号表：节头给了 .dynsym 就用它的 size 算个数，否则用 DT_GNU_HASH 推最大符号下标
            val symEntSize = if (is64) 24 else 16
            val symOff = dynsym?.first ?: if (symtabVa != 0L) toOffset(symtabVa) else -1L
            val symCount = when {
                dynsym != null -> (dynsym.second / symEntSize).toInt()
                gnuHashVa != 0L -> gnuHashSymCount(buf, toOffset(gnuHashVa).toInt(), is64)
                else -> 0
            }
            val symbols = ArrayList<Sym>(symCount)
            if (symOff >= 0) for (i in 0 until symCount) {
                val s = symOff + i.toLong() * symEntSize
                // 64 位：name(4) info(1) other(1) shndx(2) value(8) size(8)；32 位：name(4) value(4) size(4) info(1) other(1) shndx(2)
                val nameIdx = buf.getInt(s.toInt()).toLong() and 0xffffffffL
                val info: Int; val shndx: Int
                if (is64) {
                    info = bytes[(s + 4).toInt()].toInt() and 0xff
                    shndx = buf.getShort((s + 6).toInt()).toInt() and 0xffff
                } else {
                    info = bytes[(s + 12).toInt()].toInt() and 0xff
                    shndx = buf.getShort((s + 14).toInt()).toInt() and 0xffff
                }
                val name = str(strtabOff, nameIdx)
                if (name.isEmpty()) continue
                val type = when (info and 0xf) {
                    0 -> Sym.Type.NOTYPE; 1 -> Sym.Type.OBJECT; 2 -> Sym.Type.FUNC; 3 -> Sym.Type.SECTION
                    4 -> Sym.Type.FILE; 5 -> Sym.Type.COMMON; 6 -> Sym.Type.TLS; else -> Sym.Type.OTHER
                }
                val binding = when (info shr 4) { 0 -> Sym.Binding.LOCAL; 1 -> Sym.Binding.GLOBAL; 2 -> Sym.Binding.WEAK; else -> Sym.Binding.OTHER }
                symbols += Sym(name, type, binding, imported = shndx == 0)
            }
            return Elf(is64, soname?.ifEmpty { null }, needed, symbols)
        }

        /** DT_GNU_HASH 里最大的符号下标 + 1（没有节头时算符号个数）。 */
        private fun gnuHashSymCount(buf: ByteBuffer, off: Int, is64: Boolean): Int {
            val nbuckets = buf.getInt(off)
            val symoffset = buf.getInt(off + 4)
            val bloomSize = buf.getInt(off + 8)
            val bloomEntry = if (is64) 8 else 4
            val bucketsOff = off + 16 + bloomSize * bloomEntry
            var maxBucket = 0
            for (i in 0 until nbuckets) {
                val b = buf.getInt(bucketsOff + i * 4)
                if (b > maxBucket) maxBucket = b
            }
            if (maxBucket < symoffset) return symoffset
            // 从 maxBucket 的链上往后走到结束位（chain 值最低位为 1）
            val chainOff = bucketsOff + nbuckets * 4
            var idx = maxBucket
            while (true) {
                val h = buf.getInt(chainOff + (idx - symoffset) * 4)
                idx++
                if (h and 1 == 1) break
            }
            return idx
        }
    }
}
