package co.uan.pct.app.pctmesh

import android.app.Application
import co.uan.pct.lib.core.MultiHopProtocol

class PctMeshApplication : Application() {

    lateinit var protocol: MultiHopProtocol
        private set

    override fun onCreate() {
        super.onCreate()
        protocol = MultiHopProtocol("pct").attach(this)
    }
}
