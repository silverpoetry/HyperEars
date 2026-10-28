package dev.hyperears.integration
import org.junit.Assert.*
import org.junit.Test
class HuaweiEqualizerCrudTest {
    private fun hex(s:String)=s.chunked(2).map{it.toInt(16).toByte()}.toByteArray()
    private val full=hex("5a0036002b4a0101010201640302010205010a0824640a00000000000000000000410000000000000000000000000000000000000000000000093d")
    private val empty=hex("5a0012002b4a0101010201010302010205010a080081e2")
    private fun adapter(name:String="HUAWEI FreeBuds 6i")=requireNotNull(EarbudAdapterRegistry.resolve(EarbudIdentity(name,true)))
    private fun state(a:EarbudAdapter)=a.runtimeState().features.get<HuaweiDailySettingsFeatureState>()!!
    @Test fun completeInventoryAndWrittenPendingGuardAllFourOperations() {
        for(name in listOf("HUAWEI FreeBuds 6i","HUAWEI FreeBuds Pro 3","HUAWEI FreeBuds Pro 5","HUAWEI FreeLace Pro 2")) {
            val a=adapter(name);a.receive(full)
            val inventory=state(a).eqInventory!!
            val delete=HuaweiDailyControlRequest.ManageEqualizer(inventory,HuaweiEqAction.DELETE,0x64)
            assertTrue(a.supportsControl(delete))
            assertFalse(a.supportsControl(HuaweiDailyControlRequest.SetEqualizer(1)))
            assertFalse(a.supportsControl(HuaweiDailyControlRequest.SetCustomEqualizer(0x64,List(10){0},inventory.slots.single())))
            val write=a.executeControl(delete)
            assertArrayEquals(hex("5a001b002b4901016402010a030a00000000000000000000040141050102b04a"),write.commands.single())
            assertFalse(state(a).eqPending)
            a.controlWritten(delete)
            assertTrue(state(a).eqPending);assertEquals(1,state(a).eqInventory!!.slots.size)
            assertFalse(a.supportsControl(delete));assertTrue(a.protocolSession.encode(delete).isEmpty())
            a.executeControl(StandardControlRequest.Refresh);assertTrue(state(a).eqPending)
            a.receive(hex("5a0006002b4a020164d195"));assertTrue(state(a).eqPending);assertNull(state(a).eqInventory)
            a.receive(empty);assertFalse(state(a).eqPending);assertTrue(state(a).eqInventory!!.slots.isEmpty())
            val create=HuaweiDailyControlRequest.ManageEqualizer(state(a).eqInventory!!,HuaweiEqAction.CREATE,0x64,"New",List(10){0})
            assertTrue(a.supportsControl(create));assertFalse(a.supportsControl(create.copy(id=0x65)))
            a.receive(full);assertFalse(a.supportsControl(create))
            val current=state(a).eqInventory!!
            assertTrue(a.supportsControl(HuaweiDailyControlRequest.ManageEqualizer(current,HuaweiEqAction.SELECT,0x64)))
            assertTrue(a.supportsControl(HuaweiDailyControlRequest.ManageEqualizer(current,HuaweiEqAction.UPDATE,0x64,gains=List(10){1})))
            assertFalse(a.supportsControl(delete)) // equal bytes after ABA still have newer revision
            a.resetProtocolSession();assertTrue(a.protocolSession.encode(delete).isEmpty())
        }
    }
    @Test fun exactCreateSelectAndFullCapacityNeverGuessSlotOrDeleteBuiltin() {
        val a=adapter();a.receive(full)
        val inventory=state(a).eqInventory!!
        val create=HuaweiDailyControlRequest.ManageEqualizer(inventory,HuaweiEqAction.CREATE,0x65,"B",List(10){0})
        assertArrayEquals(hex("5a001b002b4901016502010a030a00000000000000000000040142050101762d"),a.executeControl(create).commands.single())
        val select=HuaweiDailyControlRequest.ManageEqualizer(inventory,HuaweiEqAction.SELECT,0x64)
        assertArrayEquals(hex("5a001b002b4901016402010a030a000000000000000000000401410501018029"),a.executeControl(select).commands.single())
        assertEquals(select,ControlRequestTransport.decode(ControlRequestTransport.encode(select)))
        assertFalse(a.supportsControl(select.copy(action=HuaweiEqAction.DELETE,id=1)))
        assertFalse(a.supportsControl(create.copy(name="中".repeat(9))))
        a.receive(hex("5a007d002b4a01010102016403010105010a086c640a00000000000000000000410000000000000000000000000000000000000000000000650a00000000000000000000420000000000000000000000000000000000000000000000660a00000000000000000000430000000000000000000000000000000000000000000000f459"));val all=state(a).eqInventory!!
        assertNull(all.nextId)
        assertFalse(a.supportsControl(create.copy(expected=all,id=0x66)))
        assertFalse(a.supportsControl(select.copy(expected=all,action=HuaweiEqAction.DELETE,id=0x65)))
        a.receive(hex("5a0059002b4a01010102016403010105010a0848640a00000000000000000000410000000000000000000000000000000000000000000000640a000000000000000000004200000000000000000000000000000000000000000000000b24"));assertNull(state(a).eqInventory)
        a.receive(full);a.receive(hex("5a0035002b4a01010102016403010105010a0824670a000000000000000000004100000000000000000000000000000000000000000000007288"));assertNull(state(a).eqInventory)
    }
    @Test fun openFreebudsMinimalCompleteFieldsAndExplicitUnsupportedAreDistinguished() {
        val a=adapter()
        a.receive(hex("5a000b002b4a0201010301010800c886"));assertTrue(state(a).eqInventory!!.slots.isEmpty())
        a.receive(hex("5a002f002b4a0201640301010824640a00000000000000000000410000000000000000000000000000000000000000000000e3e1"));assertEquals(0x64,state(a).eqInventory!!.selected)
        a.receive(hex("5a0032002b4a0101000201640301010824640a00000000000000000000410000000000000000000000000000000000000000000000296c"));assertNull(state(a).eqInventory)
        a.receive(full);a.receive(hex("5a0032002b4a0201640301010501090824640a00000000000000000000410000000000000000000000000000000000000000000000a000"));assertNull(state(a).eqInventory)
    }
    @Test fun sourceIgnoredNameTailRemainsOpaqueAndPinsWrites() {
        val a=adapter();a.receive(hex("5a002f002b4a0201640301010824640a000000000000000000004100410000000000000000000000000000000000000000005b63"))
        val first=state(a).eqInventory!!;assertEquals("A",first.slots.single().name)
        val request=HuaweiDailyControlRequest.ManageEqualizer(first,HuaweiEqAction.DELETE,0x64)
        assertTrue(a.supportsControl(request))
        a.receive(hex("5a002f002b4a0201640301010824640a00000000000000000000410042000000000000000000000000000000000000000000ed0b"));val second=state(a).eqInventory!!
        assertEquals(first.slots,second.slots);assertNotEquals(first.raw,second.raw)
        assertFalse(a.supportsControl(request));assertTrue(a.protocolSession.encode(request).isEmpty())
    }
    @Test fun aliasProfilesDoNotAcquireCrudAndUnknownInventoryCannotAllocate() {
        for(name in listOf("HUAWEI FreeBuds Pro 4","HUAWEI FreeClip","HUAWEI FreeBuds 7i")) {
            val a=adapter(name);a.receive(full);assertNull(state(a).eqInventory)
        }
        val a=adapter();a.receive(full);val expected=state(a).eqInventory!!
        val bad=full.copyOf();bad[bad.lastIndex]=(bad.last().toInt() xor 1).toByte()
        a.receive(bad);assertEquals(expected,state(a).eqInventory)
        a.receive(hex("5a0006002b4a020164d195"));assertNull(state(a).eqInventory)
    }
}
