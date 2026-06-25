package co.uan.pct.proto.exp01.gostat.util

import android.Manifest
import android.os.Build

object PermissionsHelper {

  val required: Array<String> = buildList {
    add(Manifest.permission.ACCESS_WIFI_STATE)
    add(Manifest.permission.CHANGE_WIFI_STATE)
    add(Manifest.permission.INTERNET)
    add(Manifest.permission.ACCESS_NETWORK_STATE)
    add(Manifest.permission.CHANGE_NETWORK_STATE)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      add(Manifest.permission.NEARBY_WIFI_DEVICES)
    } else {
      add(Manifest.permission.ACCESS_FINE_LOCATION)
    }
  }.toTypedArray()
}
