package co.uan.pct.lib.core

import android.Manifest
import android.os.Build
import co.uan.pct.lib.core.api.PctNode
import co.uan.pct.lib.core.internal.PctNodeImpl

object PctCore {
    private val locationPermissions = arrayOf(
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.ACCESS_FINE_LOCATION,
    )

    val requiredPermissions: Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES, *locationPermissions)
        } else {
            locationPermissions
        }

    fun create(): PctNode = PctNodeImpl()
}
