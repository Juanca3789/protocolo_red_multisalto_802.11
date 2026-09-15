package co.uan.pct.lib.core.physical

data class PhysicalLayerTimeouts(
    /** Arranque sin grupo propio: si hay anuncio, lo ve; si no, se vuelve raíz. */
    val bootstrapSearchMs: Long = 10_000,
    /** Cadencia de relanzar discoverServices (la de exp01); relanzarlo aborta el GAS del TXT. */
    val discoverRetryMs: Long = 5_000,
    /** Duración fija del pulso de búsqueda con grupo ya activo. */
    val scanMs: Long = 5_000,
    /**
     * Un pulso por ventana. El instante *dentro* de la ventana es al azar
     * (p. ej. 10–15 s una vez, 40–50 s la siguiente) para no sincronizar raíces.
     */
    val pulsePeriodMs: Long = 60_000,
    val staConnectMs: Long = 20_000,
    val goCreateMs: Long = 18_000,
    val groupInfoRetryMs: Long = 500,
    val groupInfoMaxAttempts: Int = 8,
    val busyRetryMs: Long = 400,
    val busyRetries: Int = 5,
)
