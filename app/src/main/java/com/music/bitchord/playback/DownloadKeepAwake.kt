package com.music.bitchord.playback

import android.content.Context
import android.net.wifi.WifiManager
import android.os.PowerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Keeps the CPU and Wi-Fi up for as long as the download scheduler has a
 * download in flight, which during a pause nothing else does - the player lets
 * its own locks go the moment it stops.
 *
 * The CPU lock is taken with a timeout and re-taken at half of it while
 * downloads run, so it can never outlive the interest in it by more than
 * [LOCK_MS]. A Wi-Fi lock has no timeout to take, so what bounds it is the
 * scheduler: no task runs for longer than its own limit, after which [busy]
 * goes false and both are released. Both are released when the collection ends.
 */
class DownloadKeepAwake(
    context: Context,
    private val scope: CoroutineScope,
    private val busy: Flow<Boolean>,
) {
    private val power = context.getSystemService(PowerManager::class.java)
    private val wifi = context.applicationContext.getSystemService(WifiManager::class.java)

    fun start() {
        val cpu = power?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG)?.apply {
            setReferenceCounted(false)
        } ?: return
        // The mode is the one that keeps the radio at full speed with the
        // screen off, which is when a pause finishes its downloads. The newer
        // low-latency mode is only honoured with the screen on.
        @Suppress("DEPRECATION")
        val radio = wifi?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, TAG)?.apply {
            setReferenceCounted(false)
        }
        scope.launch {
            busy.collectLatest { downloading ->
                if (!downloading) {
                    release(cpu, radio)
                    return@collectLatest
                }
                try {
                    while (true) {
                        cpu.acquire(LOCK_MS)
                        radio?.acquire()
                        delay(LOCK_MS / 2)
                    }
                } finally {
                    release(cpu, radio)
                }
            }
        }
    }

    private fun release(cpu: PowerManager.WakeLock, radio: WifiManager.WifiLock?) {
        if (cpu.isHeld) cpu.release()
        if (radio?.isHeld == true) radio.release()
    }

    companion object {
        private const val TAG = "BitChord:downloads"

        /** How long one take of the CPU lock lasts; it is re-taken at half that while downloads run. */
        const val LOCK_MS = 2 * 60 * 1000L
    }
}
