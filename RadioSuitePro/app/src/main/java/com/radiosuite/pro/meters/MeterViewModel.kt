package com.radiosuite.pro.meters

import com.radiosuite.pro.dsp.DspEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Publica niveles de audio (RMS y pico) en tiempo real para que los vómetros
 * de la UI (Jetpack Compose) los consuman vía StateFlow. Realiza polling sobre
 * las métricas atómicas del motor C++ a ~30 fps.
 */
class MeterViewModel(private val engine: DspEngine) {

    data class Levels(
        val inputPeakDb: Float,
        val outputPeakDb: Float,
        val outputRmsDb: Float,
        val agcGainDb: Float,
        val brickwallGrDb: Float,
    )

    private val _levels = MutableStateFlow(Levels(-120f, -120f, -120f, 0f, 0f))
    val levels: StateFlow<Levels> = _levels.asStateFlow()

    private var job: Job? = null

    fun start(scope: CoroutineScope) {
        job?.cancel()
        job = scope.launch(Dispatchers.Default) {
            while (isActive) {
                _levels.value = Levels(
                    inputPeakDb = engine.inputPeakDb(),
                    outputPeakDb = engine.outputPeakDb(),
                    outputRmsDb = engine.outputRmsDb(),
                    agcGainDb = engine.agcGainDb(),
                    brickwallGrDb = engine.brickwallGrDb(),
                )
                delay(33)
            }
        }
    }

    fun stop() { job?.cancel(); job = null }
}
