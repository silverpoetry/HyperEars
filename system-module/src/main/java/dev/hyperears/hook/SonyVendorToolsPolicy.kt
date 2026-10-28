package dev.hyperears.hook
import dev.hyperears.integration.*

/** Annotates the existing acknowledged handoff row; no duplicate launcher or wire action. */
internal object SonyVendorToolsPolicy {
    data class Annotation(val title:String,val summary:String,val expected:SonyVendorToolsFeatureState)
    fun annotation(state:EarbudState):Annotation? {
        val feature=state.features.get<SonyVendorToolsFeatureState>()?:return null
        if(feature.version!=1 || !feature.optimizerAvailable ||
            state.adapter?.controlApps?.none{it.packageName==ControlAppCatalog.sonySoundConnect.packageName}!=false)return null
        return Annotation("降噪优化 · 打开 Sony App 并让出控制",
            "先释放 HyperEars 私有连接，再在 Sony 官方 App 中使用降噪优化",feature)
    }
    /** A removed optimizer annotation must not silently turn its old callback into a generic launch. */
    fun current(state:EarbudState,expected:Annotation?):Boolean = expected==null || annotation(state)==expected
}
