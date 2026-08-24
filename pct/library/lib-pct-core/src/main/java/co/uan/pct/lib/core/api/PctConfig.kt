package co.uan.pct.lib.core.api

data class PctConfig(
    /** Ventana de escaneo de padres antes de decidir join vs root. */
    val scanSettleMs: Long = 5_000L,
    /** Timeout total del bootstrap (scan + STA + GO). */
    val bootstrapTimeoutMs: Long = 60_000L,
    /** Tras STA, crear GO local y anunciar como BRIDGE. */
    val autoActivateGoAfterSta: Boolean = true,
    /** Puerto TCP control L2. */
    val ctrlPort: Int = 8765,
    /** Puerto TCP datos usuario L3. */
    val dataPort: Int = 8766,
    /** Intervalo PING keepalive canal control. */
    val pingIntervalMs: Long = 5_000L,
)
