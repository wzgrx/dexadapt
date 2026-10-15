package io.github.wzgrx.dexadapt.dexlib

import com.android.tools.smali.dexlib2.DexFileFactory
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.Annotation
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import com.android.tools.smali.dexlib2.iface.value.ArrayEncodedValue
import com.android.tools.smali.dexlib2.iface.value.EncodedValue
import com.android.tools.smali.dexlib2.iface.value.StringEncodedValue
import io.github.wzgrx.dexadapt.resolve.AnnotationText
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import io.github.wzgrx.dexadapt.ClassQuery
import io.github.wzgrx.dexadapt.TypeRef
import io.github.wzgrx.dexadapt.resolve.ClassView
import io.github.wzgrx.dexadapt.resolve.DexDigest
import io.github.wzgrx.dexadapt.resolve.FieldView
import io.github.wzgrx.dexadapt.resolve.MethodView
import io.github.wzgrx.dexadapt.resolve.SymbolSource
import java.io.File

class ClassInfo(
    override val name: String,
    override val superName: String?,
    override val interfaces: List<String>,
    override val flags: Int,
    override val sourceFile: String? = null,
    override val annotations: List<String> = emptyList(),
) : ClassView {
    override val fields = mutableListOf<FieldInfo>()
    override val methods = mutableListOf<MethodInfo>()
}

class FieldInfo(override val owner: String, override val name: String, override val type: String, override val flags: Int) : FieldView

class MethodInfo(
    override val owner: String,
    override val name: String,
    override val params: List<String>,
    override val returns: String,
    override val flags: Int,
    override val strings: Set<String>,
    /** Invoked methods as [signature] strings. */
    override val calls: Set<String>,
    /** Accessed fields as `owner#name`. */
    override val fieldRefs: Set<String>,
    /** 操作码（[OpcodeNames] 的下标），省内存。 */
    internal val opcodeIds: ByteArray = EMPTY_OPS,
    override val numbers: Set<Long> = emptySet(),
    override val annotations: List<String> = emptyList(),
) : MethodView {
    override val opcodes: List<String> get() = opcodeIds.map { OpcodeNames.name(it) }

    override val signature = MethodView.signature(owner, name, params, returns)

    companion object {
        private val EMPTY_OPS = ByteArray(0)

        fun signature(owner: String, name: String, params: List<String>, returns: String) = MethodView.signature(owner, name, params, returns)
    }
}

/** Everything the resolver needs from one APK, with JVM-style names (`a.b.C`, `int[]`): the full in-memory index used on the PC. */
class DexIndex private constructor(
    val classes: Map<String, ClassInfo>,
    /** string literal → methods that load it */
    val methodsByString: Map<String, List<MethodInfo>>,
    override val dexSha256: String,
) : SymbolSource {
    fun classesIn(packagePrefix: String): Sequence<ClassInfo> =
        classes.values.asSequence().filter { it.name.startsWith("$packagePrefix.") }

    override fun classByName(name: String): ClassView? = classes[name]

    override fun methodsUsingString(s: String): List<MethodView> = methodsByString[s].orEmpty()

    override fun classesInPackage(packagePrefix: String): Sequence<ClassView> = classesIn(packagePrefix)

    override fun classesInExactPackage(pkg: String): Sequence<ClassView> =
        classes.values.asSequence().filter { it.name.substringBeforeLast('.') == pkg }

    override fun candidateClasses(q: ClassQuery, typeName: (TypeRef) -> String): Sequence<ClassView> = classes.values.asSequence()

    companion object {
        /** 手工构造的类组成的索引（解析器单测、合成场景用）。 */
        fun of(classes: List<ClassInfo>): DexIndex {
            val byString = HashMap<String, MutableList<MethodInfo>>()
            classes.forEach { c -> c.methods.forEach { m -> m.strings.forEach { byString.getOrPut(it) { ArrayList() } += m } } }
            return DexIndex(classes.associateBy { it.name }, byString, "")
        }

        /** 拼装（索引记录读回来时用）：按方法里的字符串建反查表。 */
        internal fun assemble(classes: List<ClassInfo>, dexSha256: String): DexIndex {
            val byString = HashMap<String, MutableList<MethodInfo>>(400_000)
            for (c in classes) for (m in c.methods) for (s in m.strings) byString.getOrPut(s) { ArrayList(2) } += m
            return DexIndex(classes.associateByTo(HashMap(classes.size * 2)) { it.name }, byString, dexSha256)
        }

        /**
         * 读一个 APK（或 .dex）的全部代码。先找索引记录（[IndexCache]，同样的代码只解析一次），没有再用 dexlib2 解析并写记录。
         * [cache] 为 false 时总是重新解析（不读也不写记录）。
         */
        fun load(apk: File, cache: Boolean = true): DexIndex {
            val key = if (cache) IndexCache.key(apk, CLASSES_DEX) else null
            key?.let(IndexCache::read)?.let { return it }
            return parse(apk).also { index -> key?.let { IndexCache.write(it, index) } }
        }

        private fun parse(apk: File): DexIndex {
            val container = DexFileFactory.loadDexContainer(apk, Opcodes.getDefault())
            val classes = HashMap<String, ClassInfo>(200_000)
            val byString = HashMap<String, MutableList<MethodInfo>>(400_000)
            val interner = HashMap<String, String>()
            fun intern(s: String) = interner.getOrPut(s) { s }

            // 只认 classes*.dex（和 ART、DexKit 一样）；assets 里藏的 dex 是运行时另外加载的，不在宿主的类加载器里。
            for (entryName in container.dexEntryNames.filter { it.matches(CLASSES_DEX) }.sortedWith(compareBy({ it.length }, { it }))) {
                val dex = container.getEntry(entryName)!!.dexFile
                for (def in dex.classes) {
                    val name = intern(jvmName(def.type))
                    if (name in classes) continue // first definition wins, like the runtime class loader
                    val info = ClassInfo(
                        name, def.superclass?.let { intern(jvmName(it)) }, def.interfaces.map { intern(jvmName(it)) }, def.accessFlags,
                        def.sourceFile?.takeIf { it.isNotEmpty() }?.let(::intern), def.annotations.map(::annotationText),
                    )
                    for (f in def.fields) info.fields += FieldInfo(name, intern(f.name), intern(jvmName(f.type)), f.accessFlags)
                    for (m in def.methods) {
                        val strings = HashSet<String>()
                        val calls = HashSet<String>()
                        val fieldRefs = HashSet<String>()
                        var numbers: HashSet<Long>? = null
                        val ops = ArrayList<Byte>()
                        m.implementation?.instructions?.forEach { insn ->
                            // switch / 数组的数据块（payload）和对齐用的 nop 不算指令（DexKit 把数据块报成 nop，两边都去掉）。
                            val op = insn.opcode.name
                            if (op != "nop" && !op.endsWith("-payload")) ops += OpcodeNames.id(op)
                            when (insn) {
                                is WideLiteralInstruction -> (numbers ?: HashSet<Long>().also { numbers = it }) += insn.wideLiteral
                                is NarrowLiteralInstruction -> (numbers ?: HashSet<Long>().also { numbers = it }) += insn.narrowLiteral.toLong()
                            }
                            if (insn is ReferenceInstruction) {
                                when (val ref = insn.reference) {
                                    is StringReference -> strings += intern(ref.string)
                                    is MethodReference -> calls += intern(
                                        MethodInfo.signature(jvmName(ref.definingClass), ref.name, ref.parameterTypes.map { jvmName(it.toString()) }, jvmName(ref.returnType)),
                                    )
                                    is FieldReference -> fieldRefs += intern(jvmName(ref.definingClass) + "#" + ref.name)
                                }
                            }
                        }
                        val method = MethodInfo(
                            name, intern(m.name), m.parameterTypes.map { intern(jvmName(it.toString())) }, intern(jvmName(m.returnType)),
                            m.accessFlags, strings, calls, fieldRefs,
                            ops.toByteArray(), numbers ?: emptySet(), m.annotations.map(::annotationText),
                        )
                        info.methods += method
                        for (s in strings) byString.getOrPut(s) { ArrayList(2) } += method
                    }
                    classes[name] = info
                }
            }
            return DexIndex(classes, byString, DexDigest.of(apk))
        }

        /** 注解写成 [AnnotationText]：只保留字符串值（含字符串数组），和 DexKit 那边一致。 */
        private val CLASSES_DEX = Regex("classes\\d*\\.dex")

        private fun annotationText(a: Annotation): String =
            AnnotationText.of(jvmName(a.type), a.elements.mapNotNull { e -> encoded(e.value)?.let { e.name to it } })

        private fun encoded(v: EncodedValue): String? = when (v) {
            is StringEncodedValue -> v.value
            is ArrayEncodedValue -> v.value.mapNotNull(::encoded).takeIf { it.isNotEmpty() }?.joinToString(",")
            else -> null
        }

        fun jvmName(descriptor: String): String {
            var dims = 0
            while (descriptor[dims] == '[') dims++
            val base = when (val c = descriptor[dims]) {
                'V' -> "void"; 'Z' -> "boolean"; 'B' -> "byte"; 'S' -> "short"; 'C' -> "char"
                'I' -> "int"; 'J' -> "long"; 'F' -> "float"; 'D' -> "double"
                'L' -> descriptor.substring(dims + 1, descriptor.length - 1).replace('/', '.')
                else -> error("bad descriptor $descriptor ($c)")
            }
            return base + "[]".repeat(dims)
        }
    }
}
