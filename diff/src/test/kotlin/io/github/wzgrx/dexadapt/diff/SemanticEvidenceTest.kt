package io.github.wzgrx.dexadapt.diff
import io.github.wzgrx.dexadapt.*
import io.github.wzgrx.dexadapt.dexlib.*
import org.junit.Test
import org.junit.Assert.*

class SemanticEvidenceTest {
    private fun index(order:List<String>)=DexIndex.of(listOf(ClassInfo("C",null,emptyList(),0).apply {
        methods+=MethodInfo("C","m",emptyList(),"void",0,order.toSet(),emptySet(),emptySet(),orderedStrings=order)
    }))
    @Test fun `same opcode and literal set does not hide swapped key and value`() {
        val a=index(listOf("param_dialog_type","dialog_app_score"));val b=index(listOf("dialog_app_score","param_dialog_type"))
        val table=SymbolTable(1,"",mapOf("m" to Resolved.Method("C","m",emptyList(),"void")))
        val row=SymbolDiff.of(table,table,null,a,b).rows.single()
        assertEquals(1.0,row.bodySimilarity!!,0.0);assertTrue(row.semanticEvidence.any { it.contains("顺序变化") })
        assertEquals(listOf("param_dialog_type","dialog_app_score"),row.beforeStringLoads)
    }
    @Test fun `split indexes retain first definition and include feature-only classes`() {
        val a=ClassInfo("A",null,emptyList(),0);val duplicate=ClassInfo("A","Other",emptyList(),0);val feature=ClassInfo("Feature",null,emptyList(),0)
        val merged=DexIndex.combine(listOf(DexIndex.of(listOf(a)),DexIndex.of(listOf(duplicate,feature))),"whole-code")
        assertSame(a,merged.classes.getValue("A"));assertSame(feature,merged.classes.getValue("Feature"));assertEquals("whole-code",merged.dexSha256)
    }
}
