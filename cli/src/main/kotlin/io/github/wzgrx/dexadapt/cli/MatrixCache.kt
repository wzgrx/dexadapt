package io.github.wzgrx.dexadapt.cli

import io.github.wzgrx.dexadapt.Hex
import io.github.wzgrx.dexadapt.SymbolTable
import io.github.wzgrx.dexadapt.resolve.SymbolMargin
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

/** 仅在明确要求incremental时复用；key含完整spec、引擎字节码、构建及代码身份。parity始终另跑。 */
internal object MatrixCache {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    @Serializable data class Drop(val constraint:String,val count:Int)
    @Serializable data class Margin(val id:String,val drops:List<Drop>)
    @Serializable data class Payload(val table:String,val margins:List<Margin>)
    @Serializable data class Record(val key:String,val payload:Payload,val checksum:String)
    fun hash(text:String):String = Hex.of(MessageDigest.getInstance("SHA-256").digest(text.toByteArray()))
    fun engine():String {
        val hash=MessageDigest.getInstance("SHA-256")
        val locations=listOf(MatrixCache::class.java,io.github.wzgrx.dexadapt.resolve.SymbolResolver::class.java,
            io.github.wzgrx.dexadapt.dexlib.DexIndex::class.java,io.github.wzgrx.dexadapt.spec.Spec::class.java)
            .map { File(it.protectionDomain.codeSource.location.toURI()) }.distinctBy { it.canonicalPath }
        for(location in locations) {
            val files=if(location.isDirectory)location.walkTopDown().filter { it.isFile }.sortedBy { it.relativeTo(location).path }.toList() else listOf(location)
            for(file in files) {
                if(location.isDirectory)hash.update(file.relativeTo(location).path.toByteArray())
                file.inputStream().use { input->val b=ByteArray(65536);while(true){val n=input.read(b);if(n<0)break;hash.update(b,0,n)} }
            }
        }
        return Hex.of(hash.digest())
    }
    fun read(dir:File,key:String):Pair<SymbolTable,List<SymbolMargin>>? = runCatching {
        val file=File(dir,"$key.json");require(file.length() in 1..32L*1024*1024)
        val record=json.decodeFromString(Record.serializer(),file.readText());require(record.key==key)
        require(hash(json.encodeToString(Payload.serializer(),record.payload))==record.checksum)
        SymbolTable.fromJson(record.payload.table) to record.payload.margins.map { row->SymbolMargin(row.id,row.drops.map { SymbolMargin.Drop(it.constraint,it.count) }) }
    }.getOrNull()
    fun write(dir:File,key:String,table:SymbolTable,margins:List<SymbolMargin>) {
        dir.mkdirs();val payload=Payload(table.toJson(),margins.map { row->Margin(row.id,row.drops.map { Drop(it.constraint,it.count) }) })
        val record=Record(key,payload,hash(json.encodeToString(Payload.serializer(),payload)))
        val temp=File(dir,"$key.${java.util.UUID.randomUUID()}.tmp")
        try {
            temp.outputStream().use { it.write(json.encodeToString(Record.serializer(),record).toByteArray());it.fd.sync() }
            check(temp.renameTo(File(dir,"$key.json"))) { "矩阵缓存发布失败" }
        } finally { temp.delete() }
    }
}
