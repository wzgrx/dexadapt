package io.github.wzgrx.dexadapt.synth

import io.github.wzgrx.dexadapt.*
import io.github.wzgrx.dexadapt.dexlib.*
import io.github.wzgrx.dexadapt.spec.Spec
import io.github.wzgrx.dexadapt.resolve.*
import org.junit.Test
import org.junit.Assert.*

class BuildAwareRepairTest {
    private fun source(vararg params:String)=DexIndex.of(listOf(ClassInfo("C",null,emptyList(),0).apply {
        params.forEachIndexed { i,p->methods+=MethodInfo("C","m$i",listOf(p),"void",0,setOf("anchor"),emptySet(),emptySet()) }
    }))
    @Test fun `actual build pin is honored and additional ambiguous build rejects relaxation`() {
        val old=source("boolean","boolean");val new=source("int");val symbol=MethodSymbol("test.method",MethodQuery(owner=type("C"),shape=MethodShape(params=listOf(type("boolean")),returns=type("void"),strings=listOf("anchor"))))
            .pin("OLD",Pin.Method("C","m0",listOf("boolean")))
        assertNull(Repair(old,new).repair(symbol).yaml)
        val fix=Repair(old,new,100,200,"OLD","NEW").repair(symbol)
        assertNotNull(fix.yaml);assertTrue(fix.checkedBuilds.contains("OLD"));assertTrue(fix.yaml!!.contains("pins"))
        val ambiguous=source("boolean","int")
        assertNull(Repair(old,new,100,200,"OLD","NEW",listOf(Repair.Sample(ambiguous,300,"THIRD"))).repair(symbol).yaml)
    }
    @Test fun `overlay rebinds dependent symbols and retains contracts`() {
        val parent=ClassSymbol("test.parent",ClassQuery(name="Old"));val child=MethodSymbol("test.child",MethodQuery(owner=TypeRef.Of(parent),shape=MethodShape(name="go",params=emptyList(),returns=type("void"))))
        val spec=Spec(null,Catalog(listOf(parent,child),mapOf(child.id to Contract(minCalls=1))))
        val candidate=spec.withSymbols(listOf(ClassSymbol(parent.id,ClassQuery(name="New"))))
        val index=DexIndex.of(listOf(ClassInfo("New",null,emptyList(),0).apply { methods+=MethodInfo("New","go",emptyList(),"void",0,emptySet(),emptySet(),emptySet()) }))
        val table=SymbolResolver(index,1).table(candidate.catalog.symbols)
        assertTrue(table.missing.isEmpty());assertEquals("New",(table.entries.getValue(child.id) as Resolved.Method).owner);assertEquals(spec.catalog.contracts,candidate.catalog.contracts)
    }
}
