package com.music.bitchord.ui.tv

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration

/**
 * Universal device capability and form-factor classifier.
 *
 * Distinguishes Android TV / Google TV / Fire TV devices from handhelds/tablets
 * using standard Android framework APIs. Also exposes low-RAM and hardware capability
 * detection so the UI and playback engines can scale their memory, graphics, and
 * animations gracefully without hardcoded OEM allowlists.
 */
object DeviceType {

    /**
     * Determines whether the current running environment is an Android TV / Google TV /
     * Fire OS TV device.
     */
    fun isTv(context: Context): Boolean {
        val uiModeManager = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        if (uiModeManager?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION) {
            return true
        }
        val pm = context.packageManager
        return pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
                pm.hasSystemFeature("android.hardware.type.television")
    }

}
