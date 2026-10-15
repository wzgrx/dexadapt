package io.github.wzgrx.dexadapt.jadx

import io.github.wzgrx.dexadapt.Resolved
import org.junit.Assert.assertEquals
import org.junit.Test

class PluginTest {
    @Test
    fun `raw method ids become locations`() {
        assertEquals(
            Resolved.Method("a.b.C\$D", "show", listOf("java.lang.String", "int", "long[]", "a.b.E[][]"), "void"),
            DexAdaptJadxPlugin.method("a.b.C\$D.show(Ljava/lang/String;I[J[[La/b/E;)V"),
        )
        assertEquals(Resolved.Method("x.Y", "<init>", emptyList(), "void"), DexAdaptJadxPlugin.method("x.Y.<init>()V"))
    }
}
