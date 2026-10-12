package co.uan.pct.lib.core.types

/**
 * Progreso de la sesión. Cada modo lleva el [Role] que hoy ven Link, Data y el TXT DNS-SD.
 * `ISLAND` mientras no hay GO; `ROOT`/`BRIDGE` en cuanto el grupo existe.
 */
sealed class SessionStatus {
    abstract val role: Role

    data object Stopped : SessionStatus() {
        override val role: Role = Role.ISLAND
    }

    sealed class Booting : SessionStatus() {
        data class Browsing(
            val elapsedSec: Int = 0,
            val windowSec: Int = 15,
            val candidates: Int = 0,
        ) : Booting() {
            override val role: Role = Role.ISLAND
        }

        data class JoiningParent(val ssid: String) : Booting() {
            override val role: Role = Role.ISLAND
        }

        data object CreatingGo : Booting() {
            override val role: Role = Role.ISLAND
        }

        data class WaitingGoAddress(
            override val role: Role,
            val parentSsid: String?,
        ) : Booting()

        data class OpeningListeners(
            override val role: Role,
            val parentSsid: String?,
        ) : Booting()

        data class Publishing(
            override val role: Role,
            val parentSsid: String?,
        ) : Booting()
    }

    data class Online(
        override val role: Role,
        val parentSsid: String?,
        val listen: ListenHealth,
        val upstream: UpstreamHealth,
    ) : SessionStatus()

    data class Failed(
        override val role: Role,
        val step: BootStep,
        val message: String,
        val retryable: Boolean,
    ) : SessionStatus()
}

enum class ListenHealth { WAITING, IPV4_ONLY, READY, BIND_FAILED }

enum class UpstreamHealth { NONE, CONNECTING, JOINED, RETRYING }

enum class BootStep { BROWSE, JOIN_PARENT, CREATE_GO, GO_ADDRESS, LISTEN, PUBLISH }

internal fun mergeListen(link: ListenHealth, data: ListenHealth): ListenHealth {
    if (link == ListenHealth.BIND_FAILED || data == ListenHealth.BIND_FAILED) {
        return ListenHealth.BIND_FAILED
    }
    if (link == ListenHealth.WAITING || data == ListenHealth.WAITING) {
        return ListenHealth.WAITING
    }
    if (link == ListenHealth.READY && data == ListenHealth.READY) {
        return ListenHealth.READY
    }
    return ListenHealth.IPV4_ONLY
}

internal fun mergeUpstream(link: UpstreamHealth, data: UpstreamHealth): UpstreamHealth {
    if (link == UpstreamHealth.RETRYING || data == UpstreamHealth.RETRYING) {
        return UpstreamHealth.RETRYING
    }
    if (link == UpstreamHealth.CONNECTING || data == UpstreamHealth.CONNECTING) {
        return UpstreamHealth.CONNECTING
    }
    if (link == UpstreamHealth.NONE && data == UpstreamHealth.NONE) {
        return UpstreamHealth.NONE
    }
    if (link == UpstreamHealth.JOINED && data == UpstreamHealth.JOINED) {
        return UpstreamHealth.JOINED
    }
    // BRIDGE: L2 ya unido y L3 todavía sin vecinos → seguimos “conectando”.
    return UpstreamHealth.CONNECTING
}

internal fun SessionStatus.refine(listen: ListenHealth, upstream: UpstreamHealth): SessionStatus =
    when (this) {
        is SessionStatus.Booting.OpeningListeners ->
            if (listen == ListenHealth.WAITING) this
            else SessionStatus.Online(role, parentSsid, listen, upstream)
        is SessionStatus.Online -> copy(listen = listen, upstream = upstream)
        else -> this
    }
