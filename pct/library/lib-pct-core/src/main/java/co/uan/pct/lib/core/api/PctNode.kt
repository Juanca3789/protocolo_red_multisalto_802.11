package co.uan.pct.lib.core.api

import co.uan.pct.lib.core.link.LinkSnapshot
import co.uan.pct.lib.core.net.UserMessage
import co.uan.pct.lib.core.physical.PhysicalSnapshot
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

interface PctNode {
    val nodeId: String
    val snapshot: StateFlow<PhysicalSnapshot>
    val link: StateFlow<LinkSnapshot>
    val logs: SharedFlow<String>
    val inbox: SharedFlow<UserMessage>

    fun init(context: android.content.Context)
    fun start()
    fun close()
    fun sendUser(destinationNid: String, payload: ByteArray)
}
