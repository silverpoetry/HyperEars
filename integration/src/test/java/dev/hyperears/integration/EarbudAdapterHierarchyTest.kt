package dev.hyperears.integration

import dev.hyperears.protocol.edifier.EdifierWireCodec
import dev.hyperears.protocol.huawei.HuaweiFreebudsSppCodec
import dev.hyperears.protocol.oppo.OppoWireCodec
import dev.hyperears.protocol.vivo.VivoTwsProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class EarbudAdapterHierarchyTest {
    @Test
    fun registryCreatesOneIndependentAdapterAggregatePerPhysicalSession() {
        val identity = identity("vivo TWS Air3 Pro")
        val first = requireNotNull(EarbudAdapterRegistry.resolve(identity))
        val second = requireNotNull(EarbudAdapterRegistry.resolve(identity))

        assertTrue(first is VivoTwsAir3ProAdapter)
        assertTrue(second is VivoTwsAir3ProAdapter)
        assertNotSame(first, second)
        assertNotSame(first.protocolSession, second.protocolSession)
        assertEquals(AdapterResolution.EXACT_MATCH, first.snapshot().resolution)
    }

    @Test
    fun registryOrdersExactThenFamilyThenStandardAdapters() {
        assertTrue(resolve("vivo TWS Air3 Pro") is VivoTwsAir3ProAdapter)
        assertTrue(resolve("vivo TWS Air2") is VivoEarbudAdapter)
        assertTrue(resolve("StarRing Ultra") is StarRingUltraAdapter)
        assertTrue(resolve("StarRing Future") is StarRingEarbudAdapter)
        assertTrue(resolve("OPPO Enco Air2 Pro", standard = true) is OppoEncoAir2ProAdapter)
        assertTrue(resolve("OPPO Enco Buds2", standard = true) is OppoEarbudAdapter)
        assertTrue(resolve("漫步者・花再 Evo Pro", standard = true) is EdifierEvoProAdapter)
        assertTrue(resolve("EDIFIER FitClip Ultra", standard = true) is EdifierFitClipUltraAdapter)
        assertTrue(resolve("HUAWEI FreeBuds Pro 3", standard = true) is HuaweiFreebudsPro3Adapter)
        assertTrue(resolve("FreeBuds Pro 3", standard = true) is HuaweiFreebudsPro3Adapter)
        assertTrue(resolve("HUAWEI FreeBuds 4", standard = true) is HuaweiFreeBuds4Adapter)
        assertTrue(resolve("FreeBuds 4", standard = true) is HuaweiFreeBuds4Adapter)
        assertTrue(resolve("Unknown headset", standard = true) is StandardEarbudAdapter)
    }

    @Test
    fun fitClipUltraMatchingDoesNotClaimOtherFitClipModels() {
        assertTrue(resolve("FitClip Ultra", standard = true) is EdifierFitClipUltraAdapter)
        assertTrue(resolve("EDIFIER FitClip 2", standard = true) is EdifierEarbudAdapter)
    }

    @Test
    fun registryExposesOneStableGroupForEveryAdapter() {
        val adapters = EarbudAdapterRegistry.adapters
        val groups = EarbudAdapterRegistry.groups
        val descriptors = groups.flatMap(EarbudAdapterGroup::adapters)

        assertEquals(groups.size, groups.map { it.id }.distinct().size)
        assertEquals(adapters.map(EarbudAdapter::id).toSet(), descriptors.map { it.id }.toSet())
        groups.filterNot { it.id == "standard" }.forEach { group ->
            assertTrue(
                "${group.id} must not expose vendor fallbacks as standard adapters",
                group.adapters.none { it.kind == EarbudAdapterKind.STANDARD },
            )
        }
        assertEquals(
            listOf(StandardEarbudAdapter.ID),
            groups.single { it.id == "standard" }.adapters.map { it.id },
        )
    }

    @Test
    fun disabledExactAdapterFallsThroughToItsFamilyAdapter() {
        val adapter = EarbudAdapterRegistry.resolve(
            identity = identity("vivo TWS Air3 Pro", standard = true),
            disabledAdapterIds = setOf(VivoTwsAir3ProAdapter.ID),
        )

        assertEquals(VivoEarbudAdapter.ID, adapter?.id)
    }

    @Test
    fun disablingExactAndFamilyAdaptersFallsThroughToStandard() {
        val adapter = EarbudAdapterRegistry.resolve(
            identity = identity("vivo TWS Air3 Pro", standard = true),
            disabledAdapterIds = setOf(VivoTwsAir3ProAdapter.ID, VivoEarbudAdapter.ID),
        )

        assertEquals(StandardEarbudAdapter.ID, adapter?.id)
    }

    @Test
    fun disablingTheStandardAdapterCanExcludeTheGenericFallback() {

        assertNull(
            EarbudAdapterRegistry.resolve(
                identity = identity("Unknown headset", standard = true),
                disabledAdapterIds = setOf(StandardEarbudAdapter.ID),
            ),
        )
    }

    @Test
    fun everyPrivateAdapterStartsWithTheSystemBatteryFallback() {
        EarbudAdapterRegistry.adapters
            .filter(EarbudAdapter::privateProtocolRequired)
            .forEach { adapter ->
                val snapshot = adapter.snapshot()
                assertTrue("${adapter.id} must expose aggregate battery", snapshot.capabilities.battery)
                assertEquals(
                    "${adapter.id} must keep system battery before private evidence",
                    BatterySource.SYSTEM_AGGREGATE,
                    snapshot.batterySource,
                )
            }
    }

    @Test
    fun appleIdentitiesAreReservedBeforeTheStandardFallback() {
        assertNull(
            EarbudAdapterRegistry.resolve(
                EarbudIdentity(deviceName = "小明的 AirPods Pro", standardHeadset = true),
            ),
        )
        assertNull(
            EarbudAdapterRegistry.resolve(
                EarbudIdentity(
                    deviceName = "Renamed headset",
                    standardHeadset = true,
                    serviceUuids = setOf(AppleAirPodsAdapter.AAP_SERVICE_UUID),
                ),
            ),
        )
    }

    @Test
    fun unconfirmedFamilyKeepsSystemBatteryWithoutPublishingPrivateNoiseControls() {
        val vivo = VivoEarbudAdapter()
        val oppo = OppoEarbudAdapter()
        val bose = BoseEarbudAdapter()
        val edifier = EdifierEarbudAdapter()

        assertTrue(vivo.snapshot().capabilities.battery)
        assertEquals(BatterySource.SYSTEM_AGGREGATE, vivo.snapshot().batterySource)
        assertFalse(vivo.snapshot().capabilities.noiseControl)
        assertTrue(vivo.snapshot().supportedNoiseModes.isEmpty())
        assertTrue(oppo.snapshot().capabilities.battery)
        assertEquals(BatterySource.SYSTEM_AGGREGATE, oppo.snapshot().batterySource)
        assertFalse(oppo.snapshot().capabilities.noiseControl)
        assertTrue(oppo.snapshot().supportedNoiseModes.isEmpty())
        assertTrue(bose.snapshot().capabilities.battery)
        assertEquals(BatterySource.SYSTEM_AGGREGATE, bose.snapshot().batterySource)
        assertFalse(bose.snapshot().capabilities.noiseControl)
        assertTrue(edifier.snapshot().capabilities.battery)
        assertEquals(BatterySource.SYSTEM_AGGREGATE, edifier.snapshot().batterySource)
        assertFalse(edifier.snapshot().capabilities.noiseControl)
    }

    @Test
    fun vivoFamilyPublishesControlsOnlyAfterProtocolEvidence() {
        val adapter = VivoEarbudAdapter()
        val handshake = VivoTwsProtocol.frame(
            version = 4,
            vendor = VivoTwsProtocol.GAIA_VENDOR,
            command = VivoTwsProtocol.HANDSHAKE_RESPONSE,
            payload = byteArrayOf(0),
        )

        val result = adapter.receive(handshake)

        assertEquals(HandshakeResult.Ready, result.handshake)
        assertFalse(result.stateChanged)
        assertEquals(BatterySource.SYSTEM_AGGREGATE, adapter.snapshot().batterySource)
        assertFalse(adapter.snapshot().capabilities.noiseControl)

        val state = adapter.receive(hex("FF 03 00 03 00 1B 81 30 00 02 03"))

        assertTrue(state.stateChanged)
        assertTrue(adapter.snapshot().capabilities.noiseControl)
        assertEquals(
            setOf(NoiseMode.ANC, NoiseMode.OFF, NoiseMode.TRANSPARENCY),
            adapter.snapshot().supportedNoiseModes,
        )
    }

    @Test
    fun oppoNotificationDiscoveryConfirmsTransportWithoutInventingNoiseModes() {
        val adapter = OppoEarbudAdapter()
        val notificationSupport = OppoWireCodec.packet(
            command = OppoWireCodec.NOTIFICATION_SUPPORT_RESPONSE,
            payload = hex("00 04 01 02 03 F1"),
        )

        val result = adapter.receive(notificationSupport)

        assertEquals(HandshakeResult.Ready, result.handshake)
        assertEquals(BatterySource.SYSTEM_AGGREGATE, adapter.snapshot().batterySource)
        assertFalse(adapter.snapshot().capabilities.noiseControl)
        assertTrue(adapter.snapshot().supportedNoiseModes.isEmpty())
    }

    @Test
    fun oppoAncStateCanConfirmTheFamilyWithoutNotificationDiscovery() {
        val adapter = OppoEarbudAdapter()
        val anc = OppoWireCodec.packet(
            command = OppoWireCodec.ANC_RESPONSE,
            payload = hex("00 01 01 00 08"),
        )

        val result = adapter.receive(anc)

        assertEquals(HandshakeResult.Ready, result.handshake)
        assertEquals(BatterySource.SYSTEM_AGGREGATE, adapter.snapshot().batterySource)
        assertTrue(adapter.snapshot().capabilities.noiseControl)
        assertEquals(NoiseMode.ANC, adapter.runtimeState().noiseMode)
    }

    @Test
    fun edifierValidAncStateConfirmsOnlyTheObservedProtocolCapabilities() {
        val adapter = EdifierEarbudAdapter()

        val result = adapter.receive(hex("BB EC CC 00 02 B5 A0 CA"))

        assertEquals(HandshakeResult.Ready, result.handshake)
        assertTrue(adapter.snapshot().capabilities.noiseControl)
        assertEquals(NoiseMode.OFF, adapter.runtimeState().noiseMode)
    }

    @Test
    fun edifierFunctionReplyConfirmsTransportWithoutInventingDeviceCapabilities() {
        val adapter = EdifierEarbudAdapter()

        val result = adapter.receive(hex("BB EC D8 00 00 7F"))

        assertEquals(HandshakeResult.Ready, result.handshake)
        assertTrue(adapter.snapshot().capabilities.battery)
        assertEquals(BatterySource.SYSTEM_AGGREGATE, adapter.snapshot().batterySource)
        assertFalse(adapter.snapshot().capabilities.noiseControl)
    }

    @Test
    fun edifierFamilyUsesTheKnownDialectObservedDuringReadOnlyProbe() {
        val adapter = EdifierEarbudAdapter()
        adapter.receive(hex("BB EC CC 00 02 B5 A0 CA"))

        val result = adapter.executeControl(StandardControlRequest.SetNoiseMode(NoiseMode.ANC))

        assertTrue(result.accepted)
        assertEquals(
            EdifierWireCodec.setAnc(
                ancValue = EdifierWireCodec.ANC_VALUE_DEEP,
                ancIndex = 0x10,
            ).toList(),
            result.commands.single().toList(),
        )
    }

    @Test
    fun edifierEvoProUnlocksItsPreferredDialectFromAncEvidence() {
        val adapter = EdifierEvoProAdapter()

        assertEquals(AdapterResolution.EXACT_MATCH, adapter.snapshot().resolution)
        assertEquals(HeadsetFormFactor.TWS, adapter.snapshot().formFactor)
        assertEquals(null, adapter.snapshot().presentationId)
        assertFalse(adapter.snapshot().capabilities.noiseControl)
        assertEquals(
            listOf(
                EdifierWireCodec.queryDeviceState.toList(),
                EdifierWireCodec.queryAnc.toList(),
                EdifierWireCodec.queryFunction.toList(),
            ),
            adapter.beginHandshake().commands.map(ByteArray::toList),
        )

        adapter.receive(hex("BB EC F2 00 06 A6 C1 C7 A5 A6 B4 CC"))
        val battery = adapter.runtimeState().battery
        assertEquals(100, battery.left.percent)
        assertEquals(98, battery.right.percent)
        assertEquals(null, battery.overall.percent)
        assertEquals(null, battery.case.percent)

        val state = adapter.receive(hex("BB EC CC 00 02 BE A3 D6"))
        assertTrue(state.stateChanged)
        assertEquals(NoiseMode.OFF, adapter.runtimeState().noiseMode)
        assertEquals(
            EdifierMiLinkPresentationIds.FOUR_MODE,
            adapter.snapshot().presentationId,
        )
        assertTrue(adapter.snapshot().capabilities.noiseControl)

        mapOf(
            NoiseMode.ANC to 1,
            NoiseMode.WIND to 4,
            NoiseMode.TRANSPARENCY to 5,
            NoiseMode.OFF to 6,
        ).forEach { (mode, value) ->
            val control = adapter.executeControl(StandardControlRequest.SetNoiseMode(mode))
            assertTrue(control.accepted)
            assertEquals(
                EdifierWireCodec.setAnc(value, ancIndex = 0x1B).toList(),
                control.commands.single().toList(),
            )
        }
    }

    @Test
    fun fitClipUltraUsesDeviceStateBatteryWithoutProbingAnc() {
        val adapter = EdifierFitClipUltraAdapter()

        assertEquals(AdapterResolution.EXACT_MATCH, adapter.snapshot().resolution)
        assertFalse(adapter.snapshot().capabilities.noiseControl)
        assertEquals(
            listOf(
                EdifierWireCodec.queryDeviceState.toList(),
                EdifierWireCodec.queryFunction.toList(),
            ),
            adapter.beginHandshake().commands.map(ByteArray::toList),
        )

        val battery = adapter.receive(hex("BB EC F2 00 06 A6 C1 C7 A5 A6 B4 CC"))

        assertEquals(HandshakeResult.Ready, battery.handshake)
        assertEquals(BatterySource.PRIVATE_PROTOCOL, adapter.snapshot().batterySource)
        assertEquals(100, adapter.runtimeState().battery.left.percent)
        assertEquals(98, adapter.runtimeState().battery.right.percent)
        assertFalse(adapter.snapshot().capabilities.noiseControl)
        assertTrue(adapter.snapshot().supportedNoiseModes.isEmpty())
        assertFalse(
            adapter.executeControl(StandardControlRequest.SetNoiseMode(NoiseMode.ANC)).accepted,
        )
    }

    @Test
    fun fitClipUltraResetRestoresTheSystemBatteryFallback() {
        val adapter = EdifierFitClipUltraAdapter()
        adapter.onSystemBatteryChanged(61)
        adapter.receive(hex("BB EC F2 00 06 A6 C1 C7 A5 A6 B4 CC"))

        adapter.resetProtocolSession()
        adapter.onSystemBatteryChanged(61)

        assertEquals(BatterySource.SYSTEM_AGGREGATE, adapter.snapshot().batterySource)
        assertEquals(61, adapter.runtimeState().battery.left.percent)
        assertEquals(61, adapter.runtimeState().battery.right.percent)
        assertFalse(adapter.snapshot().capabilities.noiseControl)
    }

    @Test
    fun protocolResetRevokesEdifierPrivateEvidenceUntilTheNextHandshake() {
        val adapter = EdifierEvoProAdapter()
        adapter.onSystemBatteryChanged(73)
        adapter.receive(hex("BB EC F2 00 06 A6 C1 C7 A5 A6 B4 CC"))
        adapter.receive(hex("BB EC CC 00 02 BE A3 D6"))

        assertEquals(BatterySource.PRIVATE_PROTOCOL, adapter.snapshot().batterySource)
        assertTrue(adapter.snapshot().capabilities.noiseControl)
        assertEquals(NoiseMode.OFF, adapter.runtimeState().noiseMode)
        assertEquals(EdifierMiLinkPresentationIds.FOUR_MODE, adapter.snapshot().presentationId)

        adapter.resetProtocolSession()

        assertEquals(null, adapter.runtimeState().battery.left.percent)
        assertEquals(null, adapter.runtimeState().battery.right.percent)
        adapter.onSystemBatteryChanged(73)

        assertEquals(BatterySource.SYSTEM_AGGREGATE, adapter.snapshot().batterySource)
        assertFalse(adapter.snapshot().capabilities.noiseControl)
        assertTrue(adapter.snapshot().supportedNoiseModes.isEmpty())
        assertEquals(null, adapter.snapshot().presentationId)
        assertEquals(null, adapter.runtimeState().noiseMode)
        assertEquals(73, adapter.runtimeState().battery.left.percent)
        assertEquals(73, adapter.runtimeState().battery.right.percent)

        adapter.receive(hex("BB EC CC 00 02 BE A3 D6"))

        assertTrue(adapter.snapshot().capabilities.noiseControl)
        assertEquals(NoiseMode.OFF, adapter.runtimeState().noiseMode)
        assertEquals(EdifierMiLinkPresentationIds.FOUR_MODE, adapter.snapshot().presentationId)
    }

    @Test
    fun edifierFamilySelectsTheEvoDialectFromReadOnlyAncEvidence() {
        val adapter = EdifierEarbudAdapter()

        val evidence = adapter.receive(hex("BB EC CC 00 02 BE A3 D6"))
        assertEquals(HandshakeResult.Ready, evidence.handshake)
        assertEquals(NoiseMode.OFF, adapter.runtimeState().noiseMode)
        assertEquals(
            EdifierMiLinkPresentationIds.FOUR_MODE,
            adapter.snapshot().presentationId,
        )
        assertEquals(
            setOf(NoiseMode.ANC, NoiseMode.OFF, NoiseMode.TRANSPARENCY, NoiseMode.WIND),
            adapter.snapshot().supportedNoiseModes,
        )

        val control = adapter.executeControl(StandardControlRequest.SetNoiseMode(NoiseMode.WIND))
        assertEquals(
            EdifierWireCodec.setAnc(ancValue = 4, ancIndex = 0x1B).toList(),
            control.commands.single().toList(),
        )
    }

    @Test
    fun boseProductIdentityAtomicallyReplacesAdapterAndReusesProtocolSession() {
        val family = BoseHeadphonesAdapter()
        val protocolSession = family.protocolSession

        val result = family.receive(
            hex("00 03 03 03 40 75 02 02 02 03 04 50 FF FF 00"),
        )
        val replacement = result.handshake as HandshakeResult.Replace
        val adapter = replacement.adapter

        assertEquals(AdapterActivation.KEEP_CHANNEL_READY, replacement.activation)
        assertEquals(BoseQuietComfortHeadphonesAdapter.ID, adapter.id)
        assertEquals(AdapterResolution.PROTOCOL_CONFIRMED, adapter.resolution)
        assertEquals(HeadsetFormFactor.HEADPHONES, adapter.formFactor)
        assertSame(protocolSession, adapter.protocolSession)
        assertEquals(80, adapter.runtimeState().battery.overall.percent)
        assertEquals(BatterySource.PRIVATE_PROTOCOL, adapter.snapshot().batterySource)
        assertTrue(adapter.snapshot().capabilities.noiseControl)
    }

    @Test
    fun disabledBoseModelKeepsFamilyProbeInsteadOfApplyingTheConcreteReplacement() {
        val family = BoseHeadphonesAdapter().apply {
            configureDisabledAdapterIds(setOf(BoseQuietComfortHeadphonesAdapter.ID))
        }

        val identity = family.receive(
            hex("00 03 03 03 40 75 02 02 02 03 04 50 FF FF 00"),
        )
        assertEquals(HandshakeResult.Ready, identity.handshake)
        assertEquals(BoseHeadphonesAdapter.ID, family.id)

        val evidence = family.receive(hex("1F 03 03 01 01"))
        val replacement = evidence.handshake as HandshakeResult.Replace
        assertTrue(replacement.adapter.id.startsWith("bose-discovered-headphones-"))
    }

    @Test
    fun unknownBoseIdentityKeepsFamilyUntilAReadOnlyDialectProbeSucceeds() {
        val family = BoseEarbudAdapter()
        val protocolSession = family.protocolSession

        val identity = family.receive(hex("00 03 03 03 12 34 00"))
        assertEquals(HandshakeResult.Ready, identity.handshake)
        assertFalse(family.snapshot().capabilities.noiseControl)

        val evidence = family.receive(hex("1F 03 03 01 01"))
        val replacement = evidence.handshake as HandshakeResult.Replace

        assertSame(protocolSession, replacement.adapter.protocolSession)
        assertEquals(AdapterResolution.PROTOCOL_CONFIRMED, replacement.adapter.resolution)
        assertEquals(NoiseMode.TRANSPARENCY, replacement.adapter.runtimeState().noiseMode)
        assertEquals(
            setOf(NoiseMode.ANC, NoiseMode.TRANSPARENCY),
            replacement.adapter.snapshot().supportedNoiseModes,
        )
        assertEquals(
            BatterySource.SYSTEM_AGGREGATE,
            replacement.adapter.snapshot().batterySource,
        )

        val repeatedEvidence = replacement.adapter.receive(hex("1F 03 03 01 01"))
        assertFalse(repeatedEvidence.handshake is HandshakeResult.Replace)
    }

    @Test
    fun edifierServiceEvidenceSelectsConservativeFamilyProbe() {
        val adapter = requireNotNull(EarbudAdapterRegistry.resolve(
            EarbudIdentity(
                deviceName = "Wireless Audio",
                standardHeadset = true,
                serviceUuids = setOf(EdifierEarbudAdapter.EDF_SPP_UUID.lowercase()),
            ),
        ))

        assertTrue(adapter is EdifierEarbudAdapter)
        assertTrue(adapter.snapshot().capabilities.battery)
        assertEquals(BatterySource.SYSTEM_AGGREGATE, adapter.snapshot().batterySource)
        assertFalse(adapter.snapshot().capabilities.noiseControl)
        assertNull(adapter.snapshot().presentationId)
    }

    @Test
    fun standardAdapterProjectsSystemBatteryWithoutInventingCaseTelemetry() {
        val adapter = StandardEarbudAdapter()

        assertTrue(adapter.onSystemBatteryChanged(73))
        assertEquals(73, adapter.runtimeState().battery.left.percent)
        assertEquals(73, adapter.runtimeState().battery.right.percent)
        assertEquals(null, adapter.runtimeState().battery.case.percent)
        assertFalse(adapter.privateProtocolRequired)
    }

    @Test
    fun capabilityEvidenceAloneDoesNotReplaceTheCurrentBatterySource() {
        val adapter = TestRoseEarfreeProtocolFamilyAdapter()

        assertEquals(BatterySource.SYSTEM_AGGREGATE, adapter.snapshot().batterySource)
        assertFalse(adapter.snapshot().capabilities.noiseControl)
        assertEquals(null, adapter.snapshot().presentationId)

        val result = adapter.confirm(
            battery = true,
            noiseModes = setOf(
                NoiseMode.ANC,
                NoiseMode.OFF,
                NoiseMode.TRANSPARENCY,
                NoiseMode.WIND,
            ),
        )

        assertEquals(null, result)
        assertEquals(BatterySource.SYSTEM_AGGREGATE, adapter.snapshot().batterySource)
        assertTrue(adapter.snapshot().capabilities.noiseControl)
        assertTrue(adapter.snapshot().capabilities.windNoiseControl)
        assertEquals(
            RoseEarfreeProtocolFamilyAdapter.PRESENTATION_ID,
            adapter.snapshot().presentationId,
        )
    }

    @Test
    fun rosePrivateUuidsSelectTheirProtocolFamiliesWithoutAProductLineName() {
        val earfree = EarbudAdapterRegistry.resolve(
            EarbudIdentity(
                deviceName = "EARFREE Collaboration Edition",
                standardHeadset = true,
                serviceUuids = setOf(RoseEarfreeProtocolFamilyAdapter.SERVICE_UUID),
            ),
        )
        val budsFeel = EarbudAdapterRegistry.resolve(
            EarbudIdentity(
                deviceName = "Collaboration Edition",
                standardHeadset = true,
                serviceUuids = setOf(RoseBudsFeelProtocolFamilyAdapter.DATA_CHANNEL_UUID),
            ),
        )

        assertTrue(earfree is RoseEarfreeProtocolFamilyAdapter)
        assertTrue(budsFeel is RoseBudsFeelProtocolFamilyAdapter)
    }

    @Test
    fun roseCeramicsNameSelectsLuliUltraBudsFeelAdapterWithoutCachedUuid() {
        val adapter = requireNotNull(
            EarbudAdapterRegistry.resolve(
                EarbudIdentity(
                    deviceName = "ROSE Ceramics U",
                    standardHeadset = true,
                    serviceUuids = emptySet(),
                ),
            ),
        )

        assertTrue(adapter is RoseLuliUltraAdapter)
        assertEquals(AdapterResolution.EXACT_MATCH, adapter.resolution)
        assertTrue(adapter.privateProtocolRequired)
        assertFalse(adapter.snapshot().capabilities.noiseControl)
        assertTrue(adapter.snapshot().supportedNoiseModes.isEmpty())
        assertEquals(BatterySource.SYSTEM_AGGREGATE, adapter.snapshot().batterySource)
        assertEquals(
            RoseBudsFeelProtocolFamilyAdapter.DATA_CHANNEL_UUID,
            (adapter.transports.single() as RfcommEndpointSpec.ServiceUuid).uuid,
        )
    }

    @Test
    fun roseCeramicsUltraVariantNameSelectsLuliUltraAdapter() {
        val adapter = requireNotNull(
            EarbudAdapterRegistry.resolve(
                EarbudIdentity(
                    deviceName = "ROSE Ceramics Ultra",
                    standardHeadset = true,
                    serviceUuids = emptySet(),
                ),
            ),
        )

        assertTrue(adapter is RoseLuliUltraAdapter)
    }

    @Test
    fun roseCeramicsNameDoesNotCaptureUnrelatedHeadsets() {
        val adapter = requireNotNull(
            EarbudAdapterRegistry.resolve(
                EarbudIdentity(
                    deviceName = "Some Other Headset",
                    standardHeadset = true,
                ),
            ),
        )

        assertTrue(adapter is StandardEarbudAdapter)
    }

    @Test
    fun roseCeramicsWithoutUltraMarkerDoesNotSelectLuliAdapter() {
        val plain = requireNotNull(
            EarbudAdapterRegistry.resolve(
                EarbudIdentity(
                    deviceName = "ROSE Ceramics",
                    standardHeadset = true,
                    serviceUuids = emptySet(),
                ),
            ),
        )
        val luli = requireNotNull(
            EarbudAdapterRegistry.resolve(
                EarbudIdentity(
                    deviceName = "ROSE Luli",
                    standardHeadset = true,
                    serviceUuids = emptySet(),
                ),
            ),
        )

        assertFalse(plain is RoseLuliUltraAdapter)
        assertFalse(luli is RoseLuliUltraAdapter)
        assertTrue(plain is StandardEarbudAdapter)
        assertTrue(luli is StandardEarbudAdapter)
    }

    @Test
    fun roseCeramicsNearNamesDoNotCaptureOtherRoseModels() {
        val budsFeelMk2 = requireNotNull(
            EarbudAdapterRegistry.resolve(
                EarbudIdentity(
                    deviceName = "ROSE BudsFeel MK2",
                    standardHeadset = true,
                    serviceUuids = emptySet(),
                ),
            ),
        )
        val earfreeI5 = requireNotNull(
            EarbudAdapterRegistry.resolve(
                EarbudIdentity(
                    deviceName = "ROSE Earfree i5",
                    standardHeadset = true,
                    serviceUuids = emptySet(),
                ),
            ),
        )

        assertTrue(budsFeelMk2 is RoseBudsFeelMk2Adapter)
        assertTrue(earfreeI5 is RoseEarfreeI5Adapter)
    }

    @Test
    fun roseLuliRemainsDormantAfterBoundedFailure() {
        assertEquals(
            InitialProtocolFailureResolution.KeepDormant,
            RoseLuliUltraAdapter().onInitialProtocolUnavailable(),
        )
    }

    @Test
    fun furinaNameSelectsVerifiedBudsFeelAdapterWithoutCachedUuid() {
        val adapter = requireNotNull(
            EarbudAdapterRegistry.resolve(
                EarbudIdentity(
                    deviceName = "Furina Endless Solo of Solitude",
                    standardHeadset = true,
                    serviceUuids = emptySet(),
                ),
            ),
        )

        assertTrue(adapter is FurinaEndlessAdapter)
        assertEquals(AdapterResolution.EXACT_MATCH, adapter.resolution)
        assertTrue(adapter.privateProtocolRequired)
        assertFalse(adapter.snapshot().capabilities.noiseControl)
        assertTrue(adapter.snapshot().supportedNoiseModes.isEmpty())
        assertEquals(BatterySource.SYSTEM_AGGREGATE, adapter.snapshot().batterySource)
        assertEquals(
            RoseBudsFeelProtocolFamilyAdapter.DATA_CHANNEL_UUID,
            (adapter.transports.single() as RfcommEndpointSpec.ServiceUuid).uuid,
        )
    }

    @Test
    fun furinaNameDoesNotCaptureUnrelatedHeadsets() {
        val adapter = requireNotNull(
            EarbudAdapterRegistry.resolve(
                EarbudIdentity(
                    deviceName = "Collaboration Edition",
                    standardHeadset = true,
                ),
            ),
        )

        assertTrue(adapter is StandardEarbudAdapter)
    }

    @Test
    fun furinaPublishesModesOnlyAfterValidBudsFeelEvidence() {
        val adapter = FurinaEndlessAdapter()

        val result = adapter.receive(budsFeelStatusResponse())

        assertEquals(HandshakeResult.Ready, result.handshake)
        assertEquals(BatterySource.PRIVATE_PROTOCOL, adapter.snapshot().batterySource)
        assertTrue(adapter.snapshot().capabilities.noiseControl)
        assertEquals(NoiseMode.WIND, adapter.runtimeState().noiseMode)
        assertEquals(NoiseMode.entries.toSet(), adapter.snapshot().supportedNoiseModes)
    }

    @Test
    fun furinaDecodesCapturedIndependentBatteryResponse() {
        val adapter = FurinaEndlessAdapter()

        val result = adapter.receive(hex("DD FC 04 0C 63 63 00 AF AA"))

        assertEquals(HandshakeResult.Ready, result.handshake)
        assertEquals(BatterySource.PRIVATE_PROTOCOL, adapter.snapshot().batterySource)
        assertEquals(99, adapter.runtimeState().battery.left.percent)
        assertEquals(99, adapter.runtimeState().battery.right.percent)
        assertEquals(0, adapter.runtimeState().battery.case.percent)
    }

    @Test
    fun roseLuliUltraMapsExtendedAncVariantAndMaskedBatteryIntoStandardState() {
        val adapter = RoseLuliUltraAdapter()

        val result = adapter.receive(
            hex(
                "DD 01 15 01 01 05 02 07 03 02 04 06 05 00 11 04 12 01 13 03 " +
                    "14 08 15 00 02 07 00 02 09 06 04 0C E4 E4 5E 04 0D 00 03 " +
                    "04 02 0E 00 02 12 01 02 2A 00 02 2B 00 02 2C 05 02 2D 05 " +
                    "02 2E 00 02 31 00 02 32 01 02 33 00 05 36 01 01 01 01 DF AA",
            ),
        )

        assertEquals(HandshakeResult.Ready, result.handshake)
        assertTrue(adapter.snapshot().capabilities.noiseControl)
        assertEquals(NoiseMode.ANC, adapter.runtimeState().noiseMode)
        assertEquals(100, adapter.runtimeState().battery.left.percent)
        assertFalse(adapter.runtimeState().battery.left.charging)
        assertEquals(100, adapter.runtimeState().battery.right.percent)
        assertFalse(adapter.runtimeState().battery.right.charging)
        assertEquals(94, adapter.runtimeState().battery.case.percent)
        assertFalse(adapter.runtimeState().battery.case.charging)
    }

    @Test
    fun furinaRemainsDormantAfterBoundedFailure() {
        assertEquals(
            InitialProtocolFailureResolution.KeepDormant,
            FurinaEndlessAdapter().onInitialProtocolUnavailable(),
        )
    }

    @Test
    fun unconfirmedRoseFamilyFallsBackToConservativeRoseIntegration() {
        val family = RoseEarfreeProtocolFamilyAdapter()
        family.onSystemBatteryChanged(68)

        val resolution = family.onInitialProtocolUnavailable()

        assertTrue(resolution is InitialProtocolFailureResolution.FallbackTo)
        val fallback = (resolution as InitialProtocolFailureResolution.FallbackTo).adapter
        assertTrue(fallback is RoseEarbudAdapter)
        assertFalse(fallback.privateProtocolRequired)
        assertFalse(fallback.snapshot().capabilities.noiseControl)
        assertEquals(BatterySource.SYSTEM_AGGREGATE, fallback.snapshot().batterySource)
        assertEquals(68, fallback.runtimeState().battery.overall.percent)
    }

    @Test
    fun disabledDeclaredProtocolFallbackResolvesDirectlyToStandardAdapter() {
        val family = RoseEarfreeProtocolFamilyAdapter().apply {
            configureDisabledAdapterIds(setOf(RoseEarbudAdapter.ID))
        }

        val resolution = family.resolveInitialProtocolFailure()

        assertTrue(resolution is InitialProtocolFailureResolution.FallbackTo)
        val fallback = (resolution as InitialProtocolFailureResolution.FallbackTo).adapter
        assertEquals(StandardEarbudAdapter.ID, fallback.id)
        assertEquals(AdapterResolution.STANDARD, fallback.resolution)
    }

    @Test
    fun disabledDeclaredAndStandardFallbacksKeepFailedAdapterDormant() {
        val family = RoseEarfreeProtocolFamilyAdapter().apply {
            configureDisabledAdapterIds(
                setOf(RoseEarbudAdapter.ID, StandardEarbudAdapter.ID),
            )
        }

        assertEquals(
            InitialProtocolFailureResolution.KeepDormant,
            family.resolveInitialProtocolFailure(),
        )
    }

    @Test
    fun exactRoseModelRetainsItsProtocolCandidateAfterTransportFailure() {
        assertEquals(
            InitialProtocolFailureResolution.KeepDormant,
            RoseEarfreeI5Adapter().onInitialProtocolUnavailable(),
        )
        assertEquals(
            InitialProtocolFailureResolution.KeepDormant,
            RoseBudsFeelMk2Adapter().onInitialProtocolUnavailable(),
        )
    }

    @Test
    fun boseProductCatalogNeverReturnsSharedAdapterInstances() {
        val first = BoseBmapModelRegistry.adapters.first()
        val second = BoseBmapModelRegistry.adapters.first()

        assertNotSame(first, second)
        assertNotSame(first.protocolSession, second.protocolSession)
    }

    @Test
    fun huaweiFreebudsPro3StartsWithLockedStandardCapabilities() {
        val adapter = HuaweiFreebudsPro3Adapter()

        assertEquals(AdapterResolution.EXACT_MATCH, adapter.snapshot().resolution)
        assertEquals(HeadsetFormFactor.TWS, adapter.snapshot().formFactor)
        assertEquals(null, adapter.snapshot().presentationId)
        assertFalse(adapter.snapshot().capabilities.noiseControl)
        assertTrue(adapter.snapshot().capabilities.battery)
        assertEquals(BatterySource.SYSTEM_AGGREGATE, adapter.snapshot().batterySource)
        assertTrue(adapter.snapshot().privateProtocolRequired)
        assertTrue(adapter.snapshot().supportedNoiseModes.isEmpty())
        assertEquals(
            listOf(
                HuaweiFreebudsSppCodec.queryBattery.toList(),
                HuaweiFreebudsSppCodec.queryNoiseState.toList(),
            ),
            adapter.beginHandshake().commands.map(ByteArray::toList),
        )
    }

    @Test
    fun huaweiBatteryEvidenceOpensPrivateBatteryAndConfirmsHandshake() {
        val adapter = HuaweiFreebudsPro3Adapter()
        val frame = HuaweiFreebudsSppCodec.packet(
            0x0108,
            listOf(
                1 to byteArrayOf(0x40),
                2 to byteArrayOf(0x10, 0x20, 0x30),
                3 to byteArrayOf(0x00, 0x01, 0x00),
            ),
        )

        val result = adapter.receive(frame)

        assertEquals(HandshakeResult.Ready, result.handshake)
        assertEquals(BatterySource.PRIVATE_PROTOCOL, adapter.snapshot().batterySource)
        val battery = adapter.runtimeState().battery
        assertEquals(16, battery.left.percent)
        assertEquals(32, battery.right.percent)
        assertEquals(48, battery.case.percent)
        assertEquals(64, battery.overall.percent)
        assertTrue(battery.overall.charging)
        assertFalse(battery.left.charging)
        assertFalse(battery.right.charging)
        assertFalse(battery.case.charging)
    }

    @Test
    fun huaweiNoiseEvidenceOpensThreeStateControlAndAncLevelFeature() {
        val adapter = HuaweiFreebudsPro3Adapter()

        val result = adapter.receive(
            HuaweiFreebudsSppCodec.packet(0x2B2A, listOf(1 to byteArrayOf(0x02, 0x01))),
        )

        assertEquals(HandshakeResult.Ready, result.handshake)
        assertTrue(adapter.snapshot().capabilities.noiseControl)
        assertEquals(
            setOf(NoiseMode.ANC, NoiseMode.OFF, NoiseMode.TRANSPARENCY),
            adapter.snapshot().supportedNoiseModes,
        )
        assertEquals(NoiseMode.ANC, adapter.runtimeState().noiseMode)
        val ancLevel = adapter.runtimeState().features.get<HuaweiAncLevelFeatureState>()
        assertEquals(HuaweiAncLevel.ULTRA, ancLevel?.current)
        assertEquals(HuaweiAncLevel.entries.toSet(), ancLevel?.supported)

        val modeResult = adapter.executeControl(
            StandardControlRequest.SetNoiseMode(NoiseMode.TRANSPARENCY),
        )
        assertTrue(modeResult.accepted)
        assertEquals(
            HuaweiFreebudsSppCodec
                .noiseModeCommand(HuaweiFreebudsSppCodec.NoiseMode.TRANSPARENCY)
                .toList(),
            modeResult.commands.single().toList(),
        )
        assertEquals(
            HuaweiFreebudsSppCodec.queryNoiseState.toList(),
            modeResult.readback.single().toList(),
        )

        adapter.receive(
            HuaweiFreebudsSppCodec.packet(0x2B2A, listOf(1 to byteArrayOf(0x01, 0x02))),
        )
        val levelResult = adapter.executeControl(
            HuaweiControlRequest.SetAncLevel(HuaweiAncLevel.VOICE_BOOST),
        )
        assertTrue(levelResult.accepted)
        assertEquals(
            HuaweiFreebudsSppCodec
                .noiseLevelCommand(HuaweiFreebudsSppCodec.NoiseMode.TRANSPARENCY, 1)
                .toList(),
            levelResult.commands.single().toList(),
        )
    }

    @Test
    fun huaweiModeChangeNotificationTriggersStateRefresh() {
        val adapter = HuaweiFreebudsPro3Adapter()

        val result = adapter.receive(
            HuaweiFreebudsSppCodec.packet(0x2B03, listOf(1 to byteArrayOf(0x01))),
        )

        assertTrue(
            result.commands.any {
                it.toList() == HuaweiFreebudsSppCodec.queryNoiseState.toList()
            },
        )
    }

    @Test
    fun huaweiAncLevelRejectedWhenModeDomainDoesNotMatch() {
        val adapter = HuaweiFreebudsPro3Adapter()
        adapter.receive(
            HuaweiFreebudsSppCodec.packet(0x2B2A, listOf(1 to byteArrayOf(0x02, 0x01))),
        )

        val result = adapter.executeControl(
            HuaweiControlRequest.SetAncLevel(HuaweiAncLevel.VOICE_BOOST),
        )

        assertFalse(result.accepted)
    }

    @Test
    fun huaweiFamilyMatchesAudioProductLinesWithoutCapturingHonorOrGenericHuaweiDevices() {
        assertTrue(resolve("HUAWEI FreeBuds Pro", standard = true) is HuaweiFreebudsFamilyAdapter)
        assertTrue(resolve("FreeBuds Unknown", standard = true) is HuaweiFreebudsFamilyAdapter)
        assertTrue(resolve("HUAWEI FreeClip", standard = true) is HuaweiFreebudsFamilyAdapter)
        assertTrue(resolve("FreeLace Pro", standard = true) is HuaweiFreebudsFamilyAdapter)
        assertFalse(resolve("HONOR Unknown", standard = true) is HuaweiFreebudsFamilyAdapter)
        assertFalse(resolve("HUAWEI Sound Joy", standard = true) is HuaweiFreebudsFamilyAdapter)
    }

    @Test
    fun huaweiFreebudsPro3RemainsDormantAfterBoundedFailure() {
        assertEquals(
            InitialProtocolFailureResolution.KeepDormant,
            HuaweiFreebudsPro3Adapter().onInitialProtocolUnavailable(),
        )
    }

    @Test
    fun huaweiFreeBuds4StartsWithLockedStandardCapabilities() {
        val adapter = HuaweiFreeBuds4Adapter()

        assertEquals(AdapterResolution.EXACT_MATCH, adapter.snapshot().resolution)
        assertEquals(HeadsetFormFactor.TWS, adapter.snapshot().formFactor)
        assertEquals(null, adapter.snapshot().presentationId)
        assertFalse(adapter.snapshot().capabilities.noiseControl)
        assertTrue(adapter.snapshot().capabilities.battery)
        assertEquals(BatterySource.SYSTEM_AGGREGATE, adapter.snapshot().batterySource)
        assertTrue(adapter.snapshot().privateProtocolRequired)
        assertTrue(adapter.snapshot().supportedNoiseModes.isEmpty())
        assertEquals(
            listOf(
                HuaweiFreebudsSppCodec.queryBattery.toList(),
                HuaweiFreebudsSppCodec.queryNoiseState.toList(),
            ),
            adapter.beginHandshake().commands.map(ByteArray::toList),
        )
    }

    @Test
    fun huaweiFreeBuds4BatteryEvidenceConfirmsHandshake() {
        val adapter = HuaweiFreeBuds4Adapter()
        val frame = HuaweiFreebudsSppCodec.packet(
            0x0108,
            listOf(
                1 to byteArrayOf(0x40),
                2 to byteArrayOf(0x10, 0x20, 0x30),
                3 to byteArrayOf(0x00, 0x01, 0x00),
            ),
        )

        val result = adapter.receive(frame)

        assertEquals(HandshakeResult.Ready, result.handshake)
        assertEquals(BatterySource.PRIVATE_PROTOCOL, adapter.snapshot().batterySource)
        val battery = adapter.runtimeState().battery
        assertEquals(16, battery.left.percent)
        assertEquals(32, battery.right.percent)
        assertEquals(48, battery.case.percent)
        assertEquals(64, battery.overall.percent)
        assertTrue(battery.overall.charging)
        assertFalse(battery.left.charging)
        assertFalse(battery.right.charging)
        assertFalse(battery.case.charging)
    }

    @Test
    fun huaweiFreeBuds4RemainsDormantAfterBoundedFailure() {
        assertEquals(
            InitialProtocolFailureResolution.KeepDormant,
            HuaweiFreeBuds4Adapter().onInitialProtocolUnavailable(),
        )
    }

    @Test
    fun huaweiFamilyOwnsOneOrderedChannelFallbackAndStartsLocked() {
        val adapter = HuaweiFreebudsFamilyAdapter()

        assertEquals(AdapterResolution.FAMILY_MATCH, adapter.snapshot().resolution)
        assertEquals(HeadsetFormFactor.TWS, adapter.snapshot().formFactor)
        assertEquals(null, adapter.snapshot().presentationId)
        assertFalse(adapter.snapshot().capabilities.noiseControl)
        assertTrue(adapter.snapshot().capabilities.battery)
        assertEquals(BatterySource.SYSTEM_AGGREGATE, adapter.snapshot().batterySource)
        assertTrue(adapter.snapshot().privateProtocolRequired)
        assertTrue(adapter.snapshot().supportedNoiseModes.isEmpty())
        assertEquals(
            listOf(1, 16),
            adapter.transports.map { (it as RfcommEndpointSpec.Channel).number },
        )
        assertEquals(
            listOf(
                HuaweiFreebudsSppCodec.queryBattery.toList(),
                HuaweiFreebudsSppCodec.queryNoiseState.toList(),
            ),
            adapter.beginHandshake().commands.map(ByteArray::toList),
        )
    }

    @Test
    fun huaweiFamilyUnlocksObservedProtocolWithoutClaimingAncDepth() {
        val adapter = HuaweiFreebudsFamilyAdapter()

        val result = adapter.receive(
            HuaweiFreebudsSppCodec.packet(0x2B2A, listOf(1 to byteArrayOf(0x02, 0x01))),
        )

        assertEquals(HandshakeResult.Ready, result.handshake)
        assertEquals(NoiseMode.ANC, adapter.runtimeState().noiseMode)
        assertEquals(
            setOf(NoiseMode.ANC, NoiseMode.OFF, NoiseMode.TRANSPARENCY),
            adapter.snapshot().supportedNoiseModes,
        )
        assertNull(adapter.runtimeState().features.get<HuaweiAncLevelFeatureState>())
        assertFalse(
            adapter.executeControl(
                HuaweiControlRequest.SetAncLevel(HuaweiAncLevel.ULTRA),
            ).accepted,
        )
    }

    @Test
    fun huaweiFreeBuds4UnlocksOnlyItsTwoConfirmedModes() {
        val adapter = HuaweiFreeBuds4Adapter()

        adapter.receive(
            HuaweiFreebudsSppCodec.packet(0x2B2A, listOf(1 to byteArrayOf(0x00, 0x01))),
        )

        assertEquals(setOf(NoiseMode.ANC, NoiseMode.OFF), adapter.snapshot().supportedNoiseModes)
        assertTrue(adapter.executeControl(StandardControlRequest.SetNoiseMode(NoiseMode.OFF)).accepted)
        assertFalse(
            adapter.executeControl(
                StandardControlRequest.SetNoiseMode(NoiseMode.TRANSPARENCY),
            ).accepted,
        )
    }

    private fun resolve(name: String, standard: Boolean = false): EarbudAdapter =
        requireNotNull(EarbudAdapterRegistry.resolve(identity(name, standard)))

    private fun identity(name: String, standard: Boolean = false): EarbudIdentity =
        EarbudIdentity(
            deviceName = name,
            standardHeadset = standard,
        )

    private fun hex(value: String): ByteArray = value
        .trim()
        .split(Regex("\\s+"))
        .map { it.toInt(16).toByte() }
        .toByteArray()

    private fun budsFeelStatusResponse(): ByteArray {
        val body = byteArrayOf(
            0xDD.toByte(),
            0x2A,
            0x15,
            0x04,
            0x0C,
            91,
            82,
            67,
            0x02,
            0x09,
            0x04,
        ) + ByteArray(13)
        val checksum = body.sumOf { it.toInt() and 0xFF }.and(0xFF).toByte()
        return body + byteArrayOf(checksum, 0xAA.toByte())
    }

    private class TestRoseEarfreeProtocolFamilyAdapter :
        RoseEarfreeProtocolFamilyAdapter() {
        fun confirm(battery: Boolean, noiseModes: Set<NoiseMode>): HandshakeResult? =
            onCapabilitiesIdentified(battery, noiseModes)
    }
}
