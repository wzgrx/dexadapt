package io.github.wzgrx.dexadapt

/** 小写十六进制。 */
object Hex {
    private val digits = "0123456789abcdef".toCharArray()

    fun of(bytes: ByteArray): String {
        val out = CharArray(bytes.size * 2)
        bytes.forEachIndexed { i, b ->
            out[i * 2] = digits[(b.toInt() shr 4) and 0xf]
            out[i * 2 + 1] = digits[b.toInt() and 0xf]
        }
        return String(out)
    }
}
