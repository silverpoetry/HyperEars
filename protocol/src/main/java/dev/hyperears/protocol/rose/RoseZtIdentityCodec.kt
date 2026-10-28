package dev.hyperears.protocol.rose

/** Advertisement prefixes select a candidate only; every feature still needs its own report. */
object RoseZtIdentityCodec {
    fun identify(data: Map<Int, List<Int>>): RoseZtWireCodec.Model? = RoseZtWireCodec.identify(data)
    fun named(name: String): RoseZtWireCodec.Model? = RoseZtWireCodec.Model.entries.singleOrNull {
        it.existingAdapterId == null && it.display.filter(Char::isLetterOrDigit).equals(name.filter(Char::isLetterOrDigit), true)
    }
}
