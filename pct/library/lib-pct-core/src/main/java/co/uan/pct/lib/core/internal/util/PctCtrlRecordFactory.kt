package co.uan.pct.lib.core.internal.util

import co.uan.pct.lib.core.internal.p2p.model.PctCtrlRecord

object PctCtrlRecordFactory {

    private const val DEFAULT_CTRL_PORT = 8765

    fun fromInstancePayload(
        payload: PctInstanceCodec.Payload,
        deviceAddress: String,
        fullNid: String? = null,
    ): PctCtrlRecord = PctCtrlRecord(
        nid = fullNid ?: payload.nidShort.padEnd(32, '0'),
        role = "ROOT",
        hop = 0,
        epoch = 0L,
        ctrlPort = DEFAULT_CTRL_PORT,
        goSsid = payload.goSsid,
        goPsk = payload.goPsk,
        deviceAddress = deviceAddress,
    )

    fun merge(
        base: PctCtrlRecord,
        fromTxt: PctCtrlRecord,
    ): PctCtrlRecord = base.copy(
        nid = fromTxt.nid.ifBlank { base.nid },
        role = fromTxt.role.ifBlank { base.role },
        hop = fromTxt.hop,
        epoch = fromTxt.epoch,
        ctrlPort = fromTxt.ctrlPort,
        goSsid = fromTxt.goSsid.ifBlank { base.goSsid },
        goPsk = fromTxt.goPsk.ifBlank { base.goPsk },
    )
}
