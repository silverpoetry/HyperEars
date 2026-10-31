package dev.hyperears.bridge

import dev.hyperears.integration.ControlRequest
import dev.hyperears.integration.StandardControlRequest
import java.util.Locale

/** Return is consumed only after dispatch; callbacks run outside the lease lock. */
internal class VendorHandoffReturnLeases {
    private class Lease(val token:String,val id:String,var returning:Boolean=false)
    private val leases=linkedMapOf<String,Lease>()
    private fun key(address:String)=address.uppercase(Locale.ROOT)
    fun record(address:String,token:String,id:String) = synchronized(leases) {
        leases[key(address)]=Lease(token,id)
        while(leases.size>32)leases.remove(leases.keys.first())
    }
    fun contains(address:String,token:String?):Boolean = synchronized(leases) {
        token!=null && leases[key(address)]?.let {it.token==token && !it.returning}==true
    }
    fun returnControl(address:String,tokenProvider:(String)->String?,send:(String,ControlRequest)->Unit):Boolean {
        val key=key(address)
        val lease=synchronized(leases) {
            leases[key]?.takeUnless {it.returning}?.also {it.returning=true}
        } ?: return false
        fun consume()=synchronized(leases) {if(leases[key]===lease)leases.remove(key)}
        return try {
            if(tokenProvider(address)!=lease.token) {consume();false}
            else {
                send(address,StandardControlRequest.EndVendorHandoff(lease.id))
                consume();true
            }
        } catch (_:Exception) {
            synchronized(leases) {if(leases[key]===lease)lease.returning=false}
            false
        }
    }
}
