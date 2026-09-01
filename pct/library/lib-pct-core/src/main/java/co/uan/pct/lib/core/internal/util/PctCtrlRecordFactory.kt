package co.uan.pct.lib.core.internal.util

import co.uan.pct.lib.core.internal.p2p.model.PctCtrlRecord

object PctCtrlRecordFactory {

    private const val DEFAULT_CTRL_PORT = 8765

    fun fromInstancePayload(
        payload: PctInstanceCodec.Payload,
        deviceAddress: String,
        fullNid: String? = null,
    ): PctCtrlRecord = PctCtrlRecord(
        // Instance DNS-SD solo trae 8 hex; NUNCA rellenar con ceros (UUID falso).
        nid = when {
            fullNid != null && PctNid.isFull(fullNid) -> PctNid.normalize(fullNid)
            else -> PctNid.normalize(payload.nidShort)
        },
        role = "ROOT",
        hop = 0,
        epoch = 0L,
        ctrlPort = DEFAULT_CTRL_PORT,
        goSsid = payload.goSsid,
        goPsk = payload.goPsk,
        deviceAddress = deviceAddress,
    )

    fun merge(base: PctCtrlRecord, overlay: PctCtrlRecord): PctCtrlRecord {
        val nid = when {
            PctNid.isFull(overlay.nid) -> PctNid.normalize(overlay.nid)
            PctNid.isFull(base.nid) -> PctNid.normalize(base.nid)
            overlay.nid.isNotBlank() -> PctNid.normalize(overlay.nid)
            else -> base.nid
        }
        return base.copy(
            nid = nid,
            role = overlay.role.ifBlank { base.role },
            hop = if (overlay.hop > 0 || overlay.role.isNotBlank()) overlay.hop else base.hop,
            epoch = if (overlay.epoch > 0) overlay.epoch else base.epoch,
            ctrlPort = if (overlay.ctrlPort > 0) overlay.ctrlPort else base.ctrlPort,
            goSsid = overlay.goSsid.ifBlank { base.goSsid },
            goPsk = overlay.goPsk.ifBlank { base.goPsk },
        )
    }
}
