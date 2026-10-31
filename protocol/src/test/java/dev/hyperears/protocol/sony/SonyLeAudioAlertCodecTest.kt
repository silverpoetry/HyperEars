package dev.hyperears.protocol.sony

import org.junit.Assert.*
import org.junit.Test

class SonyLeAudioAlertCodecTest {
    private fun b(vararg v: Int) = v.map(Int::toByte).toByteArray()
    @Test fun fixedAndForegroundKeepTheirDomainsAndDoNotConsumeMultipoint() {
        for (domain in listOf(0,4)) {
            val alert = requireNotNull(SonyLeAudioAlertCodec.parse(b(0x99,domain,45,0)))
            assertEquals(domain,alert.domain)
            assertEquals(45,alert.message)
            assertArrayEquals(b(0x98,domain,45,1),SonyLeAudioAlertCodec.reply(alert,true))
            assertArrayEquals(b(0x98,domain,45,0),SonyLeAudioAlertCodec.reply(alert,false))
        }
        assertNull(SonyLeAudioAlertCodec.parse(b(0x99,0,7,0)))
        assertNull(SonyLeAudioAlertCodec.parse(b(0x97,0,45,0)))
        assertNull(SonyLeAudioAlertCodec.parse(b(0x99,0,45,0,0)))
    }
    @Test fun flexiblePreservesAllItemsAndRejectsTruncatedOrTrailingData() {
        val alert = requireNotNull(SonyLeAudioAlertCodec.parse(b(0x99,6,13,2,1,7,0)))
        assertEquals(listOf(1,7),alert.items)
        assertArrayEquals(b(0x98,6,13,1),SonyLeAudioAlertCodec.reply(alert,true))
        assertNull(SonyLeAudioAlertCodec.parse(b(0x99,6,13,2,1,0)))
        assertNull(SonyLeAudioAlertCodec.parse(b(0x99,6,13,0,0,0)))
        assertNull(SonyLeAudioAlertCodec.parse(b(0x99,6,99,0,0)))
    }
    @Test fun unknownActionAndLeftRightSelectionAreNeverConfirmedAsBooleans() {
        val unknown = requireNotNull(SonyLeAudioAlertCodec.parse(b(0x99,0,45,255)))
        assertNull(SonyLeAudioAlertCodec.reply(unknown,true))
        val sides = requireNotNull(SonyLeAudioAlertCodec.parse(b(0x99,2,45,0)))
        assertNull(SonyLeAudioAlertCodec.reply(sides,true))
    }
}
