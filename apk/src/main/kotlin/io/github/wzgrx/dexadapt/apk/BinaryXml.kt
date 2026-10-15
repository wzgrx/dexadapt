package io.github.wzgrx.dexadapt.apk

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** 解析后的 XML 元素：属性按名字存（`android:` 前缀去掉），值是可读的文字。 */
data class XmlElement(val name: String, val attrs: Map<String, String>, val children: List<XmlElement>) {
    fun child(name: String): XmlElement? = children.firstOrNull { it.name == name }
    fun all(name: String): List<XmlElement> = children.filter { it.name == name }
}

/**
 * Android 编译后的二进制 XML（AndroidManifest.xml、res/layout/...）解析，只依赖格式本身（ResXMLTree）。
 * 资源混淆（AndResGuard 一类）会把属性名的字符串清空，这时按资源映射表里的系统属性 id 认出常用属性名。
 */
object BinaryXml {
    private const val RES_STRING_POOL = 0x0001
    private const val RES_XML = 0x0003
    private const val RES_XML_START_ELEMENT = 0x0102
    private const val RES_XML_END_ELEMENT = 0x0103
    private const val RES_XML_RESOURCE_MAP = 0x0180
    private const val UTF8_FLAG = 1 shl 8

    /** 系统属性 id → 名字（属性名被清空时用）。 */
    private val ATTR_IDS = mapOf(
        0x01010003 to "name", 0x01010001 to "label", 0x01010002 to "icon", 0x01010006 to "permission",
        0x0101000f to "debuggable", 0x01010010 to "exported", 0x01010011 to "process", 0x01010012 to "taskAffinity",
        0x0101000e to "enabled", 0x01010018 to "authorities", 0x01010024 to "value", 0x01010025 to "resource",
        0x01010026 to "mimeType", 0x01010027 to "scheme", 0x01010028 to "host", 0x01010029 to "port", 0x0101002a to "path",
        0x0101002b to "pathPrefix", 0x0101002c to "pathPattern", 0x0101021b to "versionCode", 0x0101021c to "versionName",
        0x0101020c to "minSdkVersion", 0x01010270 to "targetSdkVersion", 0x01010271 to "maxSdkVersion",
        0x01010202 to "targetActivity", 0x01010000 to "theme", 0x0101001d to "launchMode", 0x0101022b to "installLocation",
        0x01010572 to "compileSdkVersion", 0x01010573 to "compileSdkVersionCodename", 0x0101000c to "hasCode",
        0x0101021d to "sharedUserId", 0x01010447 to "isSplitRequired", 0x010104ea to "extractNativeLibs",
        0x01010490 to "split",
    )

    fun parse(bytes: ByteArray): XmlElement {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(buf.short.toInt() and 0xffff == RES_XML) { "not a binary XML file" }
        val headerSize = buf.short.toInt() and 0xffff
        val total = buf.int
        var strings: List<String> = emptyList()
        var resIds = IntArray(0)
        val root = Node("#root")
        val stack = ArrayDeque<Node>().apply { addLast(root) }
        var pos = headerSize
        while (pos + 8 <= minOf(total, bytes.size)) {
            val type = buf.getShort(pos).toInt() and 0xffff
            val hdr = buf.getShort(pos + 2).toInt() and 0xffff
            val size = buf.getInt(pos + 4)
            if (size <= 0) break
            when (type) {
                RES_STRING_POOL -> strings = stringPool(buf, pos)
                RES_XML_RESOURCE_MAP -> resIds = IntArray((size - hdr) / 4) { buf.getInt(pos + hdr + it * 4) }
                RES_XML_START_ELEMENT -> {
                    val ext = pos + hdr
                    val name = str(strings, buf.getInt(ext + 4))
                    val attrStart = buf.getShort(ext + 8).toInt() and 0xffff
                    val attrSize = buf.getShort(ext + 10).toInt() and 0xffff
                    val attrCount = buf.getShort(ext + 12).toInt() and 0xffff
                    val attrs = LinkedHashMap<String, String>()
                    for (i in 0 until attrCount) {
                        val a = ext + attrStart + i * attrSize
                        val nameIdx = buf.getInt(a + 4)
                        val attrName = str(strings, nameIdx).ifEmpty { resIds.getOrNull(nameIdx)?.let(ATTR_IDS::get) ?: "attr$nameIdx" }
                        val raw = buf.getInt(a + 8)
                        val dataType = buf.get(a + 15).toInt() and 0xff
                        val data = buf.getInt(a + 16)
                        attrs[attrName] = value(strings, raw, dataType, data)
                    }
                    val node = Node(name, attrs)
                    stack.last().children += node
                    stack.addLast(node)
                }
                RES_XML_END_ELEMENT -> if (stack.size > 1) stack.removeLast()
            }
            pos += size
        }
        return root.children.firstOrNull()?.freeze() ?: error("empty XML")
    }

    private class Node(val name: String, val attrs: Map<String, String> = emptyMap(), val children: MutableList<Node> = ArrayList()) {
        fun freeze(): XmlElement = XmlElement(name, attrs, children.map { it.freeze() })
    }

    private fun str(strings: List<String>, i: Int) = if (i < 0 || i >= strings.size) "" else strings[i]

    private fun value(strings: List<String>, raw: Int, type: Int, data: Int): String = when (type) {
        0x03 -> str(strings, if (raw >= 0) raw else data) // TYPE_STRING
        0x12 -> (data != 0).toString() // TYPE_INT_BOOLEAN
        0x10 -> data.toString() // TYPE_INT_DEC
        0x11 -> "0x" + Integer.toHexString(data) // TYPE_INT_HEX
        0x01 -> "@0x" + Integer.toHexString(data) // TYPE_REFERENCE
        0x02 -> "?0x" + Integer.toHexString(data) // TYPE_ATTRIBUTE
        0x04 -> java.lang.Float.intBitsToFloat(data).toString() // TYPE_FLOAT
        else -> if (raw >= 0) str(strings, raw) else "0x" + Integer.toHexString(data)
    }

    private fun stringPool(buf: ByteBuffer, start: Int): List<String> {
        val count = buf.getInt(start + 8)
        val flags = buf.getInt(start + 16)
        val stringsStart = buf.getInt(start + 20)
        val utf8 = flags and UTF8_FLAG != 0
        return List(count) { i ->
            var p = start + stringsStart + buf.getInt(start + 28 + i * 4)
            if (utf8) {
                // UTF-16 长度（跳过）和 UTF-8 字节长度，各 1～2 字节
                p += if (buf.get(p).toInt() and 0x80 != 0) 2 else 1
                var len = buf.get(p).toInt() and 0xff
                if (len and 0x80 != 0) { len = ((len and 0x7f) shl 8) or (buf.get(p + 1).toInt() and 0xff); p += 2 } else p += 1
                val b = ByteArray(len)
                for (k in 0 until len) b[k] = buf.get(p + k)
                String(b, Charsets.UTF_8)
            } else {
                var len = buf.getShort(p).toInt() and 0xffff
                if (len and 0x8000 != 0) { len = ((len and 0x7fff) shl 16) or (buf.getShort(p + 2).toInt() and 0xffff); p += 4 } else p += 2
                val c = CharArray(len) { k -> buf.getChar(p + k * 2) }
                String(c)
            }
        }
    }
}
