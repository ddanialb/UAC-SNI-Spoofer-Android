package com.uacspoofer.mobile.engine

import com.uacspoofer.mobile.core.ConnectionState
import com.uacspoofer.mobile.vpn.ExitIpInfoRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineModeTest {
    @Test
    fun unknownStoredValueFallsBackToXray() {
        assertEquals(EngineMode.XRAY_CF, EngineMode.fromStored(null))
        assertEquals(EngineMode.XRAY_CF, EngineMode.fromStored(""))
        assertEquals(EngineMode.XRAY_CF, EngineMode.fromStored("cloudflare"))
        assertEquals(EngineMode.XRAY_CF, EngineMode.fromStored("xray_cf"))
        assertEquals(EngineMode.TOR_WEBTUNNEL, EngineMode.fromStored("tor_webtunnel"))
        assertEquals(EngineMode.TOR_WEBTUNNEL, EngineMode.fromStored("TOR_WEBTUNNEL"))
        assertEquals(EngineMode.UAC_POW, EngineMode.fromStored("uac_pow"))
        assertEquals(EngineMode.UAC_POW, EngineMode.fromStored("UAC_POW"))
        assertEquals(EngineMode.UAC_FOXY, EngineMode.fromStored("uac_foxy"))
        assertEquals(EngineMode.UAC_FOXY, EngineMode.fromStored("UAC_FOXY"))
    }

    @Test
    fun engineFlagsAreExclusive() {
        assertTrue(EngineMode.XRAY_CF.isXray)
        assertFalse(EngineMode.XRAY_CF.isTor)
        assertFalse(EngineMode.XRAY_CF.isPow)
        assertFalse(EngineMode.XRAY_CF.isFoxy)
        // Exactly one Foxy mode exists.
        assertEquals(1, EngineMode.entries.count { it.isFoxy })
        assertTrue(EngineMode.TOR_WEBTUNNEL.isTor)
        assertFalse(EngineMode.TOR_WEBTUNNEL.isXray)
        assertFalse(EngineMode.TOR_WEBTUNNEL.isPow)
        assertTrue(EngineMode.UAC_POW.isPow)
        assertFalse(EngineMode.UAC_POW.isXray)
        assertFalse(EngineMode.UAC_POW.isTor)
        assertTrue(EngineMode.UAC_FOXY.isFoxy)
        assertFalse(EngineMode.UAC_FOXY.isXray)
        assertFalse(EngineMode.UAC_FOXY.isTor)
        assertFalse(EngineMode.UAC_FOXY.isPow)
        assertFalse(EngineMode.UAC_FOXY.isDirect)
        assertFalse(EngineMode.UAC_DIRECT.isFoxy)
    }

    @Test
    fun engineCanChangeOnlyWhenIdle() {
        assertTrue(canChangeEngineMode(ConnectionState.DISCONNECTED))
        assertTrue(canChangeEngineMode(ConnectionState.ERROR))
        assertFalse(canChangeEngineMode(ConnectionState.CONNECTING))
        assertFalse(canChangeEngineMode(ConnectionState.CONNECTED))
        assertFalse(canChangeEngineMode(ConnectionState.DISCONNECTING))
    }

    @Test
    fun toggledCyclesAllFiveEngines() {
        assertEquals(EngineMode.TOR_WEBTUNNEL, EngineMode.XRAY_CF.toggled())
        assertEquals(EngineMode.UAC_POW, EngineMode.TOR_WEBTUNNEL.toggled())
        assertEquals(EngineMode.UAC_FOXY, EngineMode.UAC_POW.toggled())
        assertEquals(EngineMode.UAC_DIRECT, EngineMode.UAC_FOXY.toggled())
        assertEquals(EngineMode.XRAY_CF, EngineMode.UAC_DIRECT.toggled())
        // The cycle must visit every mode exactly once before returning to the start.
        var mode = EngineMode.XRAY_CF
        val visited = mutableListOf(mode)
        repeat(EngineMode.entries.size - 1) {
            mode = mode.toggled()
            visited += mode
        }
        assertEquals(EngineMode.entries.toSet(), visited.toSet())
        assertEquals(EngineMode.XRAY_CF, mode.toggled())
    }

    @Test
    fun legacyDirectIdStillResolves() {
        assertEquals(EngineMode.UAC_DIRECT, EngineMode.fromStored("uac_direct"))
        assertEquals(EngineMode.UAC_DIRECT, EngineMode.fromStored("act_hitch"))
        assertTrue(EngineMode.UAC_DIRECT.isDirect)
        assertFalse(EngineMode.UAC_DIRECT.isXray)
    }

    @Test
    fun torExitLookupDoesNotReuseXrayProfileCache() {
        assertEquals(
            "${ExitIpInfoRepository.TOR_LOOKUP_ID}:auto",
            ExitIpInfoRepository.lookupId("builtin:mci", torEngine = true),
        )
        assertEquals(
            "${ExitIpInfoRepository.TOR_LOOKUP_ID}:de",
            ExitIpInfoRepository.lookupId("builtin:mci", torEngine = true, "DE"),
        )
        assertEquals("builtin:mci", ExitIpInfoRepository.lookupId("builtin:mci", torEngine = false))
        assertEquals(
            "${ExitIpInfoRepository.POW_LOOKUP_ID}:auto",
            ExitIpInfoRepository.lookupId("builtin:mci", torEngine = false, powEngine = true),
        )
    }

    @Test
    fun foxyExitLookupIsIsolatedFromOtherEngines() {
        assertEquals(
            "${ExitIpInfoRepository.FOXY_LOOKUP_ID}:auto",
            ExitIpInfoRepository.lookupId("builtin:mci", torEngine = false, foxyEngine = true),
        )
        assertEquals(
            "${ExitIpInfoRepository.FOXY_LOOKUP_ID}:us",
            ExitIpInfoRepository.lookupId(
                "builtin:mci",
                torEngine = false,
                exitCountryCode = "US",
                foxyEngine = true,
            ),
        )
        // Tor keeps precedence when both flags are set, so the keys can never collide.
        assertEquals(
            "${ExitIpInfoRepository.TOR_LOOKUP_ID}:auto",
            ExitIpInfoRepository.lookupId("builtin:mci", torEngine = true, foxyEngine = true),
        )
    }
}
