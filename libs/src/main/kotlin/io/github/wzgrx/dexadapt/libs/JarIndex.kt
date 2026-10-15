package io.github.wzgrx.dexadapt.libs

import io.github.wzgrx.dexadapt.dexlib.ClassInfo
import io.github.wzgrx.dexadapt.dexlib.DexIndex
import io.github.wzgrx.dexadapt.dexlib.FieldInfo
import io.github.wzgrx.dexadapt.dexlib.MethodInfo
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.Handle
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * 开源库官方发布的 jar / aar 读成和 dex 一样的类模型（[ClassInfo]），好和 APK 里的类比较。
 * 只读字符串常量、调用、字段读写和方法签名这些 dex 编译（d8 / R8）前后不变的东西；
 * javac 的 `makeConcatWithConstants`（Java 9+ 的字符串拼接）拆成常量片段，和 d8 脱糖后的 `const-string` 一致。
 */
object JarIndex {
    fun load(file: File): List<ClassInfo> = file.inputStream().buffered().use { read(it, file.name.endsWith(".aar")) }

    private fun read(input: InputStream, aar: Boolean): List<ClassInfo> {
        val out = ArrayList<ClassInfo>()
        val zip = ZipInputStream(input)
        while (true) {
            val e = zip.nextEntry ?: break
            val name = e.name
            when {
                // aar 里的代码在 classes.jar 和 libs/*.jar
                aar && name.endsWith(".jar") -> out += read(ByteArrayInputStream(zip.readBytes()), false)
                !aar && name.endsWith(".class") && !name.startsWith("META-INF/") && !name.endsWith("module-info.class") ->
                    out += readClass(zip.readBytes())
            }
        }
        return out
    }

    fun readClass(bytes: ByteArray): ClassInfo {
        var info: ClassInfo? = null
        ClassReader(bytes).accept(object : ClassVisitor(Opcodes.ASM9) {
            override fun visit(version: Int, access: Int, name: String, signature: String?, superName: String?, interfaces: Array<out String>?) {
                info = ClassInfo(dotted(name), superName?.let(::dotted), interfaces.orEmpty().map(::dotted), access and 0xffff)
            }

            override fun visitSource(source: String?, debug: String?) {
                val c = info!!
                info = ClassInfo(c.name, c.superName, c.interfaces, c.flags, source).also { n -> n.fields += c.fields; n.methods += c.methods }
            }

            override fun visitField(access: Int, name: String, descriptor: String, signature: String?, value: Any?): FieldVisitor? {
                val c = info!!
                c.fields += FieldInfo(c.name, name, DexIndex.jvmName(descriptor), access and 0xffff)
                return null
            }

            override fun visitMethod(access: Int, name: String, descriptor: String, signature: String?, exceptions: Array<out String>?): MethodVisitor {
                val owner = info!!
                val strings = LinkedHashSet<String>()
                val calls = HashSet<String>()
                val fields = HashSet<String>()
                return object : MethodVisitor(Opcodes.ASM9) {
                    override fun visitLdcInsn(value: Any?) {
                        if (value is String) strings += value
                    }

                    override fun visitMethodInsn(opcode: Int, o: String, n: String, d: String, isInterface: Boolean) {
                        // 数组的 clone() 之类，owner 是数组描述符
                        val ownerName = if (o.startsWith("[")) DexIndex.jvmName(o) else dotted(o)
                        calls += MethodInfo.signature(ownerName, n, Type.getArgumentTypes(d).map { DexIndex.jvmName(it.descriptor) }, DexIndex.jvmName(Type.getReturnType(d).descriptor))
                    }

                    override fun visitFieldInsn(opcode: Int, o: String, n: String, d: String) {
                        fields += dotted(o) + "#" + n
                    }

                    override fun visitInvokeDynamicInsn(n: String, d: String, bsm: Handle, vararg args: Any?) {
                        if (bsm.name == "makeConcatWithConstants") {
                            (args.firstOrNull() as? String)?.split('\u0001', '\u0002')?.filter { it.isNotEmpty() }?.let { strings += it }
                        }
                    }

                    override fun visitEnd() {
                        val t = Type.getMethodType(descriptor)
                        owner.methods += MethodInfo(
                            owner.name, name, t.argumentTypes.map { DexIndex.jvmName(it.descriptor) }, DexIndex.jvmName(t.returnType.descriptor),
                            access and 0xffff, strings, calls, fields,
                        )
                    }
                }
            }
        }, ClassReader.SKIP_FRAMES)
        return info!!
    }

    private fun dotted(internal: String) = internal.replace('/', '.')
}
