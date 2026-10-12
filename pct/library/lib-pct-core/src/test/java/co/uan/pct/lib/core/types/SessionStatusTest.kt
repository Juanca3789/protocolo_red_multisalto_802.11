package co.uan.pct.lib.core.types

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionStatusTest {
    @Test
    fun bootingBeforeGo_isIsland() {
        assertEquals(Role.ISLAND, SessionStatus.Stopped.role)
        assertEquals(Role.ISLAND, SessionStatus.Booting.Browsing().role)
        assertEquals(Role.ISLAND, SessionStatus.Booting.JoiningParent("DIRECT-x").role)
        assertEquals(Role.ISLAND, SessionStatus.Booting.CreatingGo.role)
    }

    @Test
    fun openingListeners_staysUntilListenMoves() {
        val opening = SessionStatus.Booting.OpeningListeners(Role.ROOT, parentSsid = null)
        assertEquals(opening, opening.refine(ListenHealth.WAITING, UpstreamHealth.NONE))
        val online = opening.refine(ListenHealth.IPV4_ONLY, UpstreamHealth.NONE)
        assertTrue(online is SessionStatus.Online)
        assertEquals(Role.ROOT, online.role)
        assertEquals(ListenHealth.IPV4_ONLY, (online as SessionStatus.Online).listen)
    }

    @Test
    fun failed_doesNotRefine() {
        val failed = SessionStatus.Failed(Role.ISLAND, BootStep.CREATE_GO, "go", retryable = true)
        assertEquals(failed, failed.refine(ListenHealth.READY, UpstreamHealth.JOINED))
    }

    @Test
    fun mergeListen_bindFailedWins() {
        assertEquals(
            ListenHealth.BIND_FAILED,
            mergeListen(ListenHealth.READY, ListenHealth.BIND_FAILED),
        )
        assertEquals(
            ListenHealth.READY,
            mergeListen(ListenHealth.READY, ListenHealth.READY),
        )
        assertEquals(
            ListenHealth.WAITING,
            mergeListen(ListenHealth.IPV4_ONLY, ListenHealth.WAITING),
        )
    }

    @Test
    fun mergeUpstream_bridgeDataLagIsConnecting() {
        assertEquals(
            UpstreamHealth.NONE,
            mergeUpstream(UpstreamHealth.NONE, UpstreamHealth.NONE),
        )
        assertEquals(
            UpstreamHealth.CONNECTING,
            mergeUpstream(UpstreamHealth.JOINED, UpstreamHealth.NONE),
        )
        assertEquals(
            UpstreamHealth.RETRYING,
            mergeUpstream(UpstreamHealth.JOINED, UpstreamHealth.RETRYING),
        )
        assertEquals(
            UpstreamHealth.JOINED,
            mergeUpstream(UpstreamHealth.JOINED, UpstreamHealth.JOINED),
        )
    }
}
