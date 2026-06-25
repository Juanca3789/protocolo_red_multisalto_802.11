package co.uan.pct.proto.exp01.gostat

import android.app.Application
import co.uan.pct.proto.exp01.gostat.data.p2p.DnsSdRepository
import co.uan.pct.proto.exp01.gostat.data.p2p.GoRepository
import co.uan.pct.proto.exp01.gostat.data.p2p.P2pChannelHolder
import co.uan.pct.proto.exp01.gostat.data.sta.LegacyStaRepository

class PctExp01Application : Application() {

    lateinit var p2pChannelHolder: P2pChannelHolder
        private set

    lateinit var goRepository: GoRepository
        private set

    lateinit var dnsSdRepository: DnsSdRepository
        private set

    lateinit var legacyStaRepository: LegacyStaRepository
        private set

    override fun onCreate() {
        super.onCreate()
        p2pChannelHolder = P2pChannelHolder(this)
        p2pChannelHolder.register()

        goRepository = GoRepository(p2pChannelHolder)
        dnsSdRepository = DnsSdRepository(p2pChannelHolder)
        legacyStaRepository = LegacyStaRepository(this)
    }
}
