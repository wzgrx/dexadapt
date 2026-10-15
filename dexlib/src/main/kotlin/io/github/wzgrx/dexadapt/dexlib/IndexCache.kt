package io.github.wzgrx.dexadapt.dexlib

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

/**
 * APK 索引记录文件（设计 11.2 第 4 点）：每个 APK 只用 dexlib2 解析一次（小红书约 30 秒），结果写成紧凑的二进制记录，
 * 之后 matrix、diff、suggest 直接读记录（几秒）。按 APK 里 classes*.dex 的 CRC 和大小认（不用读整个文件），
 * 同样的代码换了路径、改了名也能命中；索引的内容或格式变了加 [FORMAT]。
 *
 * 目录：环境变量 `DEXADAPT_CACHE`（设成 `off` 关掉），默认 `~/.cache/dexadapt/index`；最多留 [KEEP] 个，旧的删掉。
 */
internal object IndexCache {
    /** 记录格式和索引内容的版本：DexIndex 解析的东西变了（多记了什么、过滤规则改了）就加一，旧记录作废。 */
    private const val FORMAT = 1
    private const val MAGIC = 0x44584149 // "DXAI"
    private const val KEEP = 8

    private val dir: File? get() {
        val env = System.getenv("DEXADAPT_CACHE")
        if (env == "off") return null
        return File(env?.takeIf { it.isNotBlank() } ?: (System.getProperty("user.home") + "/.cache/dexadapt/index"))
    }

    /** APK 里代码的身份：classes*.dex 的名字、CRC、大小（zip 目录里就有）。不是 zip 时返回 null（不缓存）。 */
    fun key(apk: File, classesDex: Regex): String? = runCatching {
        ZipFile(apk).use { zip ->
            val dexes = zip.entries().asSequence().filter { it.name.matches(classesDex) }.sortedWith(compareBy({ it.name.length }, { it.name })).toList()
            if (dexes.isEmpty()) return null
            val text = "format=$FORMAT\n" + dexes.joinToString("\n") { "${it.name}:${it.crc}:${it.size}" }
            MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }.take(32)
        }
    }.getOrNull()

    fun read(key: String): DexIndex? {
        val f = File(dir ?: return null, "$key.dxi")
        if (!f.isFile) return null
        return runCatching {
            DataInputStream(BufferedInputStream(f.inputStream(), 1 shl 20)).use { read(it) }
        }.onFailure { f.delete() }.getOrNull()?.also { f.setLastModified(System.currentTimeMillis()) }
    }

    fun write(key: String, index: DexIndex) {
        val d = dir ?: return
        runCatching {
            d.mkdirs()
            val tmp = File(d, "$key.dxi.${ProcessHandle.current().pid()}.tmp")
            DataOutputStream(BufferedOutputStream(tmp.outputStream(), 1 shl 20)).use { write(it, index) }
            if (!tmp.renameTo(File(d, "$key.dxi"))) tmp.delete()
            d.listFiles { f -> f.name.endsWith(".dxi") }.orEmpty().sortedByDescending { it.lastModified() }.drop(KEEP).forEach { it.delete() }
        }
    }

    // ---- 格式：头、字符串表、操作码名表、类 ----

    private fun write(out: DataOutputStream, index: DexIndex) {
        val strings = LinkedHashMap<String, Int>()
        fun id(s: String): Int = strings.getOrPut(s) { strings.size }
        // 先把所有字符串编号（写类时只写编号）
        val body = java.io.ByteArrayOutputStream(64 shl 20)
        val o = DataOutputStream(body)
        fun ids(list: Collection<String>) { o.varint(list.size); list.forEach { o.varint(id(it)) } }
        o.varint(index.classes.size)
        for (c in index.classes.values) {
            o.varint(id(c.name)); o.varint(c.superName?.let(::id)?.plus(1) ?: 0); ids(c.interfaces); o.varint(c.flags)
            o.varint(c.sourceFile?.let(::id)?.plus(1) ?: 0); ids(c.annotations)
            o.varint(c.fields.size)
            for (f in c.fields) { o.varint(id(f.name)); o.varint(id(f.type)); o.varint(f.flags) }
            o.varint(c.methods.size)
            for (m in c.methods) {
                o.varint(id(m.name)); ids(m.params); o.varint(id(m.returns)); o.varint(m.flags)
                ids(m.strings); ids(m.calls); ids(m.fieldRefs)
                val ops = m.opcodeIds
                o.varint(ops.size); o.write(ops)
                o.varint(m.numbers.size); m.numbers.forEach(o::writeLong)
                ids(m.annotations)
            }
        }
        o.flush()
        out.writeInt(MAGIC); out.writeInt(FORMAT)
        out.writeUTF(index.dexSha256)
        out.varint(strings.size)
        for (s in strings.keys) out.str(s)
        val opNames = OpcodeNames.snapshot()
        out.varint(opNames.size); opNames.forEach(out::writeUTF)
        body.writeTo(out)
    }

    private fun read(input: DataInputStream): DexIndex {
        check(input.readInt() == MAGIC && input.readInt() == FORMAT) { "not a DexAdapt index record" }
        val sha = input.readUTF()
        val strings = Array(input.varint()) { input.str() }
        val opRemap = ByteArray(input.varint()) { OpcodeNames.id(input.readUTF()) }
        fun idList(): List<String> = List(input.varint()) { strings[input.varint()] }
        fun idSet(): Set<String> = when (val n = input.varint()) {
            0 -> emptySet()
            else -> HashSet<String>(n * 2).apply { repeat(n) { add(strings[input.varint()]) } }
        }
        val n = input.varint()
        val classes = ArrayList<ClassInfo>(n)
        repeat(n) {
            val name = strings[input.varint()]
            val sup = input.varint().let { if (it == 0) null else strings[it - 1] }
            val ifaces = idList()
            val flags = input.varint()
            val src = input.varint().let { if (it == 0) null else strings[it - 1] }
            val c = ClassInfo(name, sup, ifaces, flags, src, idList())
            repeat(input.varint()) { c.fields += FieldInfo(name, strings[input.varint()], strings[input.varint()], input.varint()) }
            repeat(input.varint()) {
                val mName = strings[input.varint()]
                val params = idList()
                val ret = strings[input.varint()]
                val mFlags = input.varint()
                val ss = idSet(); val calls = idSet(); val refs = idSet()
                val ops = ByteArray(input.varint()).also(input::readFully)
                for (i in ops.indices) ops[i] = opRemap[ops[i].toInt() and 0xff]
                val nums = when (val k = input.varint()) { 0 -> emptySet(); else -> HashSet<Long>(k * 2).apply { repeat(k) { add(input.readLong()) } } }
                c.methods += MethodInfo(name, mName, params, ret, mFlags, ss, calls, refs, ops, nums, idList())
            }
            classes += c
        }
        return DexIndex.assemble(classes, sha)
    }

    /**
     * 字符串逐个 char 编码（和 Java 的“修改版 UTF-8”一样，单独的代理字符也能原样保存；标准 UTF-8 会把它变成“?”），
     * 长度不受 writeUTF 的 64 KB 限制。
     */
    private fun DataOutputStream.str(s: String) {
        varint(s.length)
        for (c in s) {
            val x = c.code
            when {
                x in 1..0x7f -> writeByte(x)
                x <= 0x7ff -> { writeByte(0xc0 or (x shr 6)); writeByte(0x80 or (x and 0x3f)) }
                else -> { writeByte(0xe0 or (x shr 12)); writeByte(0x80 or ((x shr 6) and 0x3f)); writeByte(0x80 or (x and 0x3f)) }
            }
        }
    }

    private fun DataInputStream.str(): String {
        val n = varint()
        val chars = CharArray(n)
        for (i in 0 until n) {
            val a = readUnsignedByte()
            chars[i] = when {
                a < 0x80 -> a.toChar()
                a < 0xe0 -> (((a and 0x1f) shl 6) or (readUnsignedByte() and 0x3f)).toChar()
                else -> (((a and 0x0f) shl 12) or ((readUnsignedByte() and 0x3f) shl 6) or (readUnsignedByte() and 0x3f)).toChar()
            }
        }
        return String(chars)
    }

    private fun DataOutputStream.varint(v: Int) {
        var x = v
        while (x and 0x7f.inv() != 0) { writeByte((x and 0x7f) or 0x80); x = x ushr 7 }
        writeByte(x)
    }

    private fun DataInputStream.varint(): Int {
        var r = 0; var s = 0
        while (true) {
            val b = readUnsignedByte()
            r = r or ((b and 0x7f) shl s)
            if (b < 0x80) return r
            s += 7
        }
    }
}
