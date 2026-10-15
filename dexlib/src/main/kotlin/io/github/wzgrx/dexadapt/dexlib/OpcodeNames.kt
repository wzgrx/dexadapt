package io.github.wzgrx.dexadapt.dexlib

/** 操作码名字和一个字节的编号互转（索引里每条指令只存一个字节）。 */
internal object OpcodeNames {
    private val names = ArrayList<String>()
    private val ids = HashMap<String, Byte>()

    @Synchronized
    fun id(name: String): Byte = ids.getOrPut(name) {
        check(names.size < 255) { "too many opcodes" }
        names += name
        (names.size - 1).toByte()
    }

    fun name(id: Byte): String = names[id.toInt() and 0xff]

    /** 现有的名字（下标就是 id），写索引记录用。 */
    @Synchronized
    fun snapshot(): List<String> = names.toList()
}
