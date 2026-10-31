package dev.hyperears.protocol.apple

import org.junit.Assert.*
import org.junit.Test

class AppleProximityTest {
    private fun reply(vararg records: Pair<Int, ByteArray>): ByteArray =
        byteArrayOf(4, 0, 4, 0, 0x31, 0, records.size.toByte()) + records.flatMap { (type, key) ->
            (byteArrayOf(type.toByte(), (type shr 8).toByte(), key.size.toByte(), (key.size shr 8).toByte()) + key).toList()
        }.toByteArray()

    @Test fun keyReplyRejectsDuplicatesTruncationAndTrailingData() {
        val frame = reply(1 to ByteArray(16), 4 to ByteArray(16))
        assertNotNull(AppleProximityKeys.parse(frame))
        assertNull(AppleProximityKeys.parse(frame + 0))
        assertNull(AppleProximityKeys.parse(frame.copyOf(frame.size - 1)))
        assertNull(AppleProximityKeys.parse(reply(1 to ByteArray(16), 1 to ByteArray(16))))
        assertNull(AppleProximityKeys.parse(reply(4 to ByteArray(15))))
        assertNull(AppleProximityKeys.parse(reply(257 to ByteArray(16))))
    }

    @Test fun identityUsesBluetoothAhByteOrderAndRejectsWrongKey() {
        // Bluetooth Core ah test: IRK ec0234a357c8ad05341010a60a397d9b, prand 708194, hash 0dfbaa.
        val key = "9b7d390aa610103405adc857a33402ec".chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        assertTrue(AppleProximityCodec.matchesIdentity("70:81:94:0D:FB:AA", key))
        assertFalse(AppleProximityCodec.matchesIdentity("70:81:94:0D:FB:AB", key))
        assertFalse(AppleProximityCodec.matchesIdentity("70:81:94:0D:FB:AA", ByteArray(16)))
    }

    @Test fun revokedAndOldGenerationsNeverAcceptKeysOrReports() {
        val owner = AppleProximityOwner("70:81:94:0D:FB:AA")
        val token = owner.admit()
        assertTrue(owner.acceptKeys(token, AppleProximityKeys.parse(reply(1 to ByteArray(16)))!!))
        owner.revoke()
        assertFalse(owner.acceptKeys(token, AppleProximityKeys.parse(reply(4 to ByteArray(16)))!!))
        val next = owner.admit()
        assertNotEquals(token, next)
        assertNull(owner.observe(token, "70:81:94:0D:FB:AA", ByteArray(0), 0, 0))
    }

    @Test fun lidBatchCloseCancelsPendingOpenAndSilenceDoesNotClose() {
        val lid = AppleProximityLidSession()
        assertTrue(lid.observe(true, 100, 100))
        assertFalse(lid.confirm(999))
        assertTrue(lid.observe(false, 200, 1000))
        assertFalse(lid.confirm(2000))
        assertFalse(lid.observe(true, 150, 2000))
        assertTrue(lid.observe(true, 3000, 3000))
        assertTrue(lid.confirm(3900))
        assertFalse(lid.confirm(4000))
        assertEquals(true, lid.open)
    }
    @Test fun keyFramesAreAtomicAcrossEverySplitAndNeverPublicStates() {
        val embedded = byteArrayOf(4, 0, 4, 0, 9, 0, 13, 2, 0, 0, 0) + ByteArray(5)
        val frame = reply(1 to embedded, 4 to ByteArray(16))
        for (split in 1 until frame.size) {
            var received = 0
            val decoder = AppleAapWireCodec.Decoder(onPrivateKeys = { keys -> received++; keys.close() })
            assertTrue(decoder.offer(frame.copyOfRange(0, split)).isEmpty())
            assertTrue(decoder.offer(frame.copyOfRange(split, frame.size)).isEmpty())
            assertEquals(1, received)
        }
    }

    @Test fun encryptedBatteryRequiresOwnedIdentityAndRejectsInvalidPrecision() {
        val owner = AppleProximityOwner("00:11:22:33:44:55")
        val token = owner.admit()
        val key = ByteArray(16) { it.toByte() }
        owner.acceptKeys(token, AppleProximityKeys.parse(reply(4 to key))!!)
        fun packet(left: Int): ByteArray {
            val clear = ByteArray(16).also { it[1] = left.toByte(); it[2] = 71; it[3] = 255.toByte() }
            val cipher = javax.crypto.Cipher.getInstance("AES/ECB/NoPadding")
            cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, javax.crypto.spec.SecretKeySpec(key, "AES"))
            return byteArrayOf(7, 25, 0, 0x20, 0x13, 0x20, 0, 0, 0, 0, 0) + cipher.doFinal(clear)
        }
        assertNull(owner.observe(token, "00:11:22:33:44:56", packet(81), 100, 100))
        val report = owner.observe(token, "00:11:22:33:44:55", packet(81 or 128), 100, 100)!!
        assertTrue(report.precise)
        assertEquals(81, report.left.percent)
        assertTrue(report.left.charging)
        assertEquals(71, report.right.percent)
        assertNull(report.case.percent)
        assertFalse(report.case.charging)
        assertNull(owner.observe(token, "00:11:22:33:44:55", packet(101), 200, 200))
        assertNull(owner.observe(token, "00:11:22:33:44:55", packet(80), 99, 200))
        owner.revoke()
        assertNull(owner.observe(token, "00:11:22:33:44:55", packet(80), 300, 300))
    }
    @Test fun conflictingSameCaptureCannotProducePopup() {
        val lid = AppleProximityLidSession()
        assertTrue(lid.observe(true, 100, 100))
        assertFalse(lid.observe(false, 100, 100))
        assertFalse(lid.confirm(1000))
    }

    @Test fun keyRecordBoundsAndMalformedContinuityAreRejected() {
        assertNull(AppleProximityKeys.parse(reply(*Array(9) { (it + 1) to ByteArray(16) })))
        assertNull(AppleProximityKeys.parse(reply(1 to ByteArray(257))))
        assertNull(AppleProximityKeys.parse(byteArrayOf(4, 0, 4, 0, 0x31, 1, 0)))
        assertTrue(AppleProximityCodec.messages(byteArrayOf(7, 9, 0)).isEmpty())
        assertTrue(AppleProximityCodec.messages(byteArrayOf(7, 1, 0, 7)).isEmpty())
    }
}
