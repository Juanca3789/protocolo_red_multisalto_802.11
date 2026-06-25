package co.uan.pct.proto.exp01.gostat.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import co.uan.pct.proto.exp01.gostat.PctExp01Application

class Exp01ViewModelFactory(
    private val application: PctExp01Application,
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(Exp01ViewModel::class.java)) {
            return Exp01ViewModel(
                goRepository = application.goRepository,
                dnsSdRepository = application.dnsSdRepository,
                legacyStaRepository = application.legacyStaRepository,
            ) as T
        }
        throw IllegalArgumentException("ViewModel desconocido: ${modelClass.name}")
    }
}
