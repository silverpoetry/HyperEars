package dev.hyperears.hook

/** Keep the ROM's phone-side codec controls and their listeners, not Xiaomi device commands. */
internal object NativePhoneAudioPreferences {
    private val keys=setOf("ldac_pre","le_audio_pre","codec_claimer")
    fun replaceDevicePreferences(screen:Any,loader:ClassLoader,keepPhoneAudio:Boolean) {
        val group=loader.loadClass("androidx.preference.PreferenceGroup")
        val preference=loader.loadClass("androidx.preference.Preference")
        val removeAll=group.getMethod("removeAll")
        val find=group.getMethod("findPreference",CharSequence::class.java)
        val count=group.getMethod("getPreferenceCount")
        val child=group.getMethod("getPreference",Int::class.javaPrimitiveType)
        val key=preference.getMethod("getKey")
        val remove=group.getMethod("removePreference",preference)
        val add=group.getMethod("addPreference",preference)
        val codec=if(keepPhoneAudio)find.invoke(screen,"ldac_container")?.takeIf(group::isInstance) else null
        val excluded=codec?.let {container->(0 until (count.invoke(container) as Int)).map {child.invoke(container,it)}
            .filter{key.invoke(it) !in keys}}.orEmpty()
        // Preserve the original container even while empty: the ROM may populate it after a profile callback.
        excluded.forEach{remove.invoke(codec,it)}
        removeAll.invoke(screen)
        if(codec!=null) {
            preference.getMethod("setTitle",CharSequence::class.java).invoke(codec,"手机音频设置")
            check(add.invoke(screen,codec)==true)
        }
    }
}
