package com.music.bitchord.playback

import com.music.bitchord.data.settings.AppSettings
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskRemovedPlaybackPolicyTest {

    @Test
    fun stopOnTaskRemovedDefaultsToTrue() {
        // Standard music players (Spotify, YouTube Music, etc.) stop playback
        // when the app is swiped away from recent tasks, while background playback
        // continues seamlessly via the ForegroundService when minimized or screen off.
        assertTrue(
            "stopOnTaskRemoved must default to true so closing the app stops playback",
            AppSettings.stopOnTaskRemoved.value,
        )
    }
}
