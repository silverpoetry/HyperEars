package dev.hyperears.runtime

import dev.hyperears.integration.SonyLeAudioIdentity
import java.util.Locale

/** Verified protocol identity and functional-profile presence; no name or MAC-offset inference. */
internal class SonyLeAudioPresence {
    data class Route(val classicAddress:String,val controlAddress:String,val preferGatt:Boolean)
    private val groups=linkedMapOf<String,SonyLeAudioIdentity>()
    private val aliases=mutableMapOf<String,String>()
    private val a2dp=mutableSetOf<String>()
    private val le=mutableSetOf<String>()

    @Synchronized fun learn(source:String,identity:SonyLeAudioIdentity):Boolean {
        val classic=normalize(identity.classic)?:return false
        val peers=identity.le.map {normalize(it)?:return false}
        val sender=normalize(source)?:return false
        val members=listOf(classic)+peers
        if(peers.isEmpty() || members.distinct().size!=members.size || sender !in members)return false
        if(members.any {aliases[it]?.let {owner->owner!=classic}==true})return false
        if(classic !in groups && groups.size>=64)return false
        if(groups[classic]==SonyLeAudioIdentity(classic,peers))return false
        groups[classic]?.let {old->(listOf(old.classic)+old.le).forEach {aliases.remove(it)}}
        groups[classic]=SonyLeAudioIdentity(classic,peers)
        members.forEach {aliases[it]=classic}
        return true
    }
    @Synchronized fun classicFor(address:String):String?=normalize(address)?.let(aliases::get)
    @Synchronized fun a2dp(address:String,connected:Boolean)=update(a2dp,address,connected)
    @Synchronized fun leAudio(address:String,connected:Boolean)=update(le,address,connected)
    @Synchronized fun hasA2dp(address:String)=normalize(address) in a2dp
    @Synchronized fun hasLeAudio(address:String)=normalize(address) in le
    @Synchronized fun route(address:String):Route? {
        val classic=classicFor(address)?:return null
        val group=groups[classic]?:return null
        // GATT can use the reported Classic identity too; do not equate transport with address kind.
        (group.le+classic).firstOrNull {it in le}?.let {return Route(classic,it,true)}
        return if(classic in a2dp)Route(classic,classic,false) else null
    }
    @Synchronized fun forget(address:String) {
        val normalized=normalize(address)?:return
        val classic=aliases[normalized]
        val group=classic?.let(groups::remove)
        val members=group?.let {listOf(it.classic)+it.le}?:listOf(normalized)
        members.forEach {aliases.remove(it);a2dp.remove(it);le.remove(it)}
    }
    @Synchronized fun clear() {groups.clear();aliases.clear();a2dp.clear();le.clear()}
    private fun update(values:MutableSet<String>,address:String,connected:Boolean) {
        val normalized=normalize(address)?:return
        if(!connected)values.remove(normalized) else if(values.size<64)values.add(normalized)
    }
    private fun normalize(value:String):String?=value.uppercase(Locale.ROOT).takeIf {MAC.matches(it)}
    companion object {private val MAC=Regex("[0-9A-F]{2}(:[0-9A-F]{2}){5}")}
}
