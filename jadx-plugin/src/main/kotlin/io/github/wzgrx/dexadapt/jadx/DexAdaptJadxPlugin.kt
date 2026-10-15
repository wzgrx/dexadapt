package io.github.wzgrx.dexadapt.jadx

import io.github.wzgrx.dexadapt.Catalog
import io.github.wzgrx.dexadapt.Resolved
import io.github.wzgrx.dexadapt.dexlib.DexIndex
import io.github.wzgrx.dexadapt.spec.Fingerprints
import io.github.wzgrx.dexadapt.spec.Spec
import io.github.wzgrx.dexadapt.synth.Suggester
import jadx.api.JavaClass
import jadx.api.JavaField
import jadx.api.JavaMethod
import jadx.api.metadata.ICodeAnnotation
import jadx.api.plugins.JadxPlugin
import jadx.api.plugins.JadxPluginContext
import jadx.api.plugins.JadxPluginInfo
import jadx.api.plugins.gui.JadxGuiContext
import java.awt.Dimension
import java.awt.Font
import java.io.File
import javax.swing.JOptionPane
import javax.swing.JScrollPane
import javax.swing.JTextArea

/**
 * jadx 插件（设计 10.9）：在代码里右键一个类、方法或字段 →“DexAdapt：生成查询”，对当前打开的 APK 跑 `suggest`，
 * 把能让它唯一、最稳的查询写成 YAML，弹窗显示并复制到剪贴板。第一次要建 APK 的索引（有索引记录后几秒）。
 */
class DexAdaptJadxPlugin : JadxPlugin {
    @Volatile
    private var index: Pair<File, DexIndex>? = null

    override fun getPluginInfo() = JadxPluginInfo(
        "dexadapt", "DexAdapt", "右键类 / 方法 / 字段，生成跨版本稳定的 DexAdapt 查询（YAML）",
    ).apply { homepage = "https://github.com/wzgrx/dexadapt" }

    override fun init(context: JadxPluginContext) {
        val gui = context.guiContext ?: return // 命令行模式没有界面
        val types = setOf(ICodeAnnotation.AnnType.CLASS, ICodeAnnotation.AnnType.METHOD, ICodeAnnotation.AnnType.FIELD)
        gui.addPopupMenuAction("DexAdapt：生成查询", { ref -> ref?.annType in types }, null) { ref ->
            val node = context.decompiler.getJavaNodeByRef(ref) ?: return@addPopupMenuAction
            val apk = context.args.inputFiles.firstOrNull { it.name.endsWith(".apk") || it.name.endsWith(".dex") || it.isDirectory }
            if (apk == null) {
                show(gui, "DexAdapt", "没找到打开的 APK")
                return@addPopupMenuAction
            }
            Thread({
                val text = runCatching {
                    val target = target(node) ?: return@runCatching "# 这里不支持：${node.javaClass.simpleName}"
                    suggest(apk, target)
                }.getOrElse { "# 出错：${it.message ?: it}" }
                gui.uiRun {
                    if (!text.startsWith("#")) gui.copyToClipboard(text)
                    show(gui, "DexAdapt 查询（已复制）", text)
                }
            }, "dexadapt-suggest").apply { isDaemon = true }.start()
        }
    }

    private fun suggest(apk: File, target: Resolved): String {
        val idx = index?.takeIf { it.first == apk }?.second ?: DexIndex.load(apk).also { index = apk to it }
        val id = "jadx." + when (target) {
            is Resolved.Class -> target.name.substringAfterLast('.').substringAfterLast('$')
            is Resolved.Method -> target.name.removePrefix("<").removeSuffix(">")
            is Resolved.Field -> target.name
        }
        val s = Suggester(idx).suggest(target, id)
        val q = s.query ?: return "# ${s.why}"
        return "# ${s.why}\n" + Spec(null, Catalog(s.extra + q)).toYaml()
    }

    private fun show(gui: JadxGuiContext, title: String, text: String) {
        val area = JTextArea(text).apply { isEditable = false; font = Font(Font.MONOSPACED, Font.PLAIN, 12) }
        JOptionPane.showMessageDialog(gui.mainFrame, JScrollPane(area).apply { preferredSize = Dimension(720, 420) }, title, JOptionPane.PLAIN_MESSAGE)
    }

    companion object {
        /** jadx 的节点 → DexAdapt 的位置（用原始名字，不受 jadx 里的重命名影响）。 */
        internal fun target(node: Any): Resolved? = when (node) {
            is JavaClass -> Resolved.Class(node.rawName)
            is JavaMethod -> method(node.methodNode.methodInfo.rawFullId)
            is JavaField -> node.fieldNode.fieldInfo.let { f -> Resolved.Field(f.declClass.rawName, f.name, Fingerprints.jvm(f.type.toString().let(::descriptorOf))) }
            else -> null
        }

        /** `a.b.C.name(Ljava/lang/String;I)V` → 方法位置。 */
        internal fun method(rawFullId: String): Resolved.Method {
            val paren = rawFullId.indexOf('(')
            val head = rawFullId.substring(0, paren)
            val owner = head.substringBeforeLast('.')
            val name = head.substringAfterLast('.')
            val desc = rawFullId.substring(paren)
            val params = ArrayList<String>()
            var i = 1
            while (desc[i] != ')') {
                val start = i
                while (desc[i] == '[') i++
                if (desc[i] == 'L') i = desc.indexOf(';', i)
                i++
                params += Fingerprints.jvm(desc.substring(start, i))
            }
            return Resolved.Method(owner, name, params, Fingerprints.jvm(desc.substring(i + 1)))
        }

        /** jadx 的类型文字（`int`、`java.lang.String`、`int[]`）→ 描述符。 */
        private fun descriptorOf(t: String): String {
            var dims = 0
            var base = t
            while (base.endsWith("[]")) { dims++; base = base.removeSuffix("[]") }
            val d = when (base) {
                "void" -> "V"; "boolean" -> "Z"; "byte" -> "B"; "short" -> "S"; "char" -> "C"
                "int" -> "I"; "long" -> "J"; "float" -> "F"; "double" -> "D"
                else -> "L" + base.replace('.', '/') + ";"
            }
            return "[".repeat(dims) + d
        }
    }
}
