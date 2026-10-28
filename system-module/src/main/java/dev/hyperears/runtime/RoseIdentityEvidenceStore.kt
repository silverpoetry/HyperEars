package dev.hyperears.runtime

import dev.hyperears.integration.EarbudIdentity

/** Confirmed immutable evidence belongs to a physical connection, not a private RFCOMM owner. */
internal class RoseIdentityEvidenceStore {
    private data class Evidence(val connection:Any,val data:Map<Int,List<Int>>)
    private val confirmed=mutableMapOf<String,Evidence>()
    fun accept(address:String,connection:Any,data:Map<Int,List<Int>>) {
        confirmed[address]=Evidence(connection,data.mapValues { (_,bytes)->bytes.toList() }.toMap())
    }
    fun merge(address:String,connection:Any?,incoming:EarbudIdentity):EarbudIdentity {
        val evidence=confirmed[address]?.takeIf { it.connection==connection } ?: return incoming
        return if(incoming.manufacturerData.isEmpty()) incoming.copy(manufacturerData=evidence.data) else incoming
    }
    fun remove(address:String){confirmed.remove(address)}
    fun clear(){confirmed.clear()}
}
