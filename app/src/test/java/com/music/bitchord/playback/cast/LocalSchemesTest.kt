package com.music.bitchord.playback.cast

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which tracks a receiver could never be given.
 *
 * Written after asking "is this http(s)?" refused every track in the app:
 * `bitchord://` is how a normal track is spelled, and the resolver expands it
 * before a receiver sees it. So the question has to be which schemes stay on the
 * phone, not which ones already look like a stream.
 */
class LocalSchemesTest {

    @Test
    fun `a file on the phone cannot reach a receiver`() {
        assertFalse(receiverCanOpen("file"))
    }

    @Test
    fun `a MediaStore track cannot reach a receiver`() {
        assertFalse(receiverCanOpen("content"))
    }

    @Test
    fun `a network share cannot reach a receiver`() {
        assertFalse(receiverCanOpen("smb"))
        assertFalse(receiverCanOpen("webdav"))
    }

    /**
     * The one that broke it. Nearly every track the app owns is spelled this
     * way, and the resolver expands it into a stream the receiver can fetch.
     */
    @Test
    fun `our own address is not one of them`() {
        assertTrue(receiverCanOpen("bitchord"))
    }

    @Test
    fun `a plain stream is not one of them`() {
        assertTrue(receiverCanOpen("https"))
        assertTrue(receiverCanOpen("http"))
    }

    @Test
    fun `a track with no scheme cannot be sent anywhere`() {
        assertFalse(receiverCanOpen(null))
    }
}
