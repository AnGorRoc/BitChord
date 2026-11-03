package com.music.bitchord.desktop

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToLong

/**
 * Desktop LoopMix integration: renders synchronized loop mixes and provides
 * state for Desktop and SharedUi `MixViewPopup`.
 */
internal class DesktopLoopMixPort {

    class OutArm(
        val takeoverUs: Long,
        val sampleRate: Int,
        val firstFrame: Long,
    )

    class InArm(
        val handoverUs: Long,
    )

    @Volatile var outArm: OutArm? = null
        private set

    @Volatile var inArm: InArm? = null
        private set

    @Volatile var generation: Int = 0
        private set

    fun armOutgoing(arm: OutArm) {
        inArm = null
        outArm = arm
        generation++
    }

    fun armIncoming(arm: InArm) {
        outArm = null
        inArm = arm
        generation++
    }

    fun disarm() {
        outArm = null
        inArm = null
        generation++
    }
}

/**
 * State representing a transition mix for UI visualizers (such as `MixViewPopup`).
 */
data class DesktopMixState(
    val active: Boolean = false,
    val outgoingTitle: String = "",
    val incomingTitle: String = "",
    val outgoingBpm: Float = 0f,
    val incomingBpm: Float = 0f,
    val progress: Float = 0f,
    val waveformPoints: List<Float> = emptyList(),
)

internal object DesktopLoopMixController {

    private val _mixState = MutableStateFlow(DesktopMixState())
    val mixState: StateFlow<DesktopMixState> = _mixState.asStateFlow()

    @Volatile
    var enabled: Boolean = true

    fun updateProgress(progress: Float) {
        if (_mixState.value.active) {
            _mixState.value = _mixState.value.copy(progress = progress.coerceIn(0f, 1f))
        }
    }

    fun startMix(outTitle: String, inTitle: String, outBpm: Float, inBpm: Float) {
        _mixState.value = DesktopMixState(
            active = true,
            outgoingTitle = outTitle,
            incomingTitle = inTitle,
            outgoingBpm = outBpm,
            incomingBpm = inBpm,
            progress = 0f,
            waveformPoints = generateSampleWaveform(),
        )
    }

    fun endMix() {
        _mixState.value = _mixState.value.copy(active = false, progress = 0f)
    }

    private fun generateSampleWaveform(): List<Float> =
        List(64) { i ->
            val x = i / 64f
            val base = (kotlin.math.sin(x * kotlin.math.PI * 4) * 0.4f + 0.5f).toFloat()
            base.coerceIn(0.1f, 1.0f)
        }
}
