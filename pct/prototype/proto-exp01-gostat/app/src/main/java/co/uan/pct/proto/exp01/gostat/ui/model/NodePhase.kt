package co.uan.pct.proto.exp01.gostat.ui.model

enum class NodePhase {
    /** Sin upstream; puede escanear padres o iniciar como raíz. */
    ISLAND,
    /** Escaneando candidatos (GO apagado). */
    SCANNING,
    /** Padre elegido, pendiente o en conexión STA. */
    JOINING,
    /** STA al padre + GO propio operativo (o en progreso). */
    MEMBER,
    /** GO raíz sin padre upstream. */
    ROOT,
}
