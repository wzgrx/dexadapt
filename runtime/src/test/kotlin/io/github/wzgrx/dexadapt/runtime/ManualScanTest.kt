package io.github.wzgrx.dexadapt.runtime

import io.github.wzgrx.dexadapt.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executor

class ManualScanTest {
    @Test fun manualScanUsesTheSameQueueButDoesNotReuseAnExistingTable() {
        val host=HostCodeIdentity("host",1,"a".repeat(64))
        val table=SymbolTable(1,host.dexSha256,emptyMap(),packageName="host",catalog="cat")
        var scans=0
        val outcomes=ArrayList<UpgradeCoordinator.Outcome>()
        val store=object:AdaptationStore {
            override fun bundled(identity:HostCodeIdentity)=table
            override fun local(identity:HostCodeIdentity,catalog:String)=table
            override fun save(identity:HostCodeIdentity,table:SymbolTable){}
        }
        val coordinator=UpgradeCoordinator(Executor(Runnable::run),{host},store,
            {_,_,_->scans++;table},{emptySet()},{outcomes+=it.outcome})
        coordinator.onEvent("host","cat",emptySet(),UpgradeCoordinator.Trigger.HOST_FIRST_OPEN)
        coordinator.onEvent("host","cat",emptySet(),UpgradeCoordinator.Trigger.MANUAL_SCAN)
        assertEquals(1,scans)
        assertEquals(listOf(UpgradeCoordinator.Outcome.REUSED,UpgradeCoordinator.Outcome.SCANNED),outcomes)
    }
    @Test fun unchangedTargetsKeepReflectionCacheButChangedTargetsOrCatalogCannot() {
        val table=SymbolTable(1,"a".repeat(64),mapOf("type" to Resolved.Class("java.lang.String")),packageName="host")
        val linker=SymbolLinker(table,javaClass.classLoader)
        val symbol=ClassSymbol("type",ClassQuery())
        val target=linker.clazz(symbol)
        val delivered=table.copy(catalog="catalog",generator="dexkit",recoveryToken="new")
        assertTrue(linker.updateDelivery(table,delivered))
        assertEquals("type",linker.idOf(target))
        assertSame(target,linker.clazz(symbol))
        assertFalse(linker.updateDelivery(delivered,delivered.copy(catalog="different")))
        assertFalse(linker.updateDelivery(delivered,delivered.copy(entries=mapOf("type" to Resolved.Class("java.lang.Integer")))))
    }
}
