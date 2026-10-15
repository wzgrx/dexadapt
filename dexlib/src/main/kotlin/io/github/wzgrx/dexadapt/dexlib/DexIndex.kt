package io.github.wzgrx.dexadapt.dexlib

import com.android.tools.smali.dexlib2.DexFileFactory
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
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
) : MethodView {
    override val signature = MethodView.signature(owner, name, params, returns)

    companion object {
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

        fun load(apk: File): DexIndex {
            val container = DexFileFactory.loadDexContainer(apk, Opcodes.getDefault())
            val classes = HashMap<String, ClassInfo>(200_000)
            val byString = HashMap<String, MutableList<MethodInfo>>(400_000)
            val interner = HashMap<String, String>()
            fun intern(s: String) = interner.getOrPut(s) { s }

            for (entryName in container.dexEntryNames.sortedWith(compareBy({ it.length }, { it }))) {
                val dex = container.getEntry(entryName)!!.dexFile
                for (def in dex.classes) {
                    val name = intern(jvmName(def.type))
                    if (name in classes) continue // first definition wins, like the runtime class loader
                    val info = ClassInfo(name, def.superclass?.let { intern(jvmName(it)) }, def.interfaces.map { intern(jvmName(it)) }, def.accessFlags)
                    for (f in def.fields) info.fields += FieldInfo(name, intern(f.name), intern(jvmName(f.type)), f.accessFlags)
                    for (m in def.methods) {
                        val strings = HashSet<String>()
                        val calls = HashSet<String>()
                        val fieldRefs = HashSet<String>()
                        m.implementation?.instructions?.forEach { insn ->
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
                        )
                        info.methods += method
                        for (s in strings) byString.getOrPut(s) { ArrayList(2) } += method
                    }
                    classes[name] = info
                }
            }
            return DexIndex(classes, byString, DexDigest.of(apk))
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
