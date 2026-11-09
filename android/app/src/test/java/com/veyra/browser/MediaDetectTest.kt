package com.veyra.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaDetectTest {
    @Test fun keepsDirectVideoAndAudio() {
        val r = MediaDetect.parse("""{"items":[
            {"url":"https://cdn.example.com/a/clip.mp4?token=1","tag":"video","title":"Clip"},
            {"url":"https://cdn.example.com/song.mp3","tag":"link","title":"Song"}]}""")
        assertEquals(2, r.items.size)
        assertEquals(DetectedMedia.Kind.VIDEO, r.items[0].kind)
        assertEquals(DetectedMedia.Kind.AUDIO, r.items[1].kind)
        assertEquals("clip.mp4", r.items[0].fileName)
        assertTrue(r.items[0].downloadable)
    }

    @Test fun hlsIsPlayOnly() {
        val r = MediaDetect.parse("""{"items":[{"url":"https://x.example/live/master.m3u8","tag":"video"}]}""")
        assertEquals(DetectedMedia.Kind.STREAM, r.items.single().kind)
        assertFalse(r.items.single().downloadable)
    }

    @Test fun extensionlessVideoTagUsesTag() {
        val r = MediaDetect.parse("""{"items":[{"url":"https://x.example/watch/123","tag":"video"}]}""")
        assertEquals(DetectedMedia.Kind.VIDEO, r.items.single().kind)
    }

    @Test fun extensionlessLinkIsIgnored() {
        val r = MediaDetect.parse("""{"items":[{"url":"https://x.example/page","tag":"link"}]}""")
        assertTrue(r.items.isEmpty())
    }

    @Test fun blobDashAndNonHttpAreCountedNotListed() {
        val r = MediaDetect.parse("""{"blobs":1,"items":[
            {"url":"blob:https://x.example/abc","tag":"video"},
            {"url":"https://x.example/manifest.mpd","tag":"video"},
            {"url":"file:///sdcard/a.mp4","tag":"video"},
            {"url":"javascript:alert(1)","tag":"video"}]}""")
        assertTrue(r.items.isEmpty())
        assertEquals(3, r.unsupported)
    }

    @Test fun deduplicatesAndCaps() {
        val items = (1..50).joinToString(",") { """{"url":"https://x.example/$it.mp4","tag":"video"}""" }
        val dup = """{"url":"https://x.example/1.mp4","tag":"video"}"""
        val r = MediaDetect.parse("""{"items":[$dup,$items]}""")
        assertEquals(20, r.items.size)
        assertEquals(r.items.map { it.url }.toSet().size, r.items.size)
    }

    @Test fun garbageInputIsSafe() {
        assertTrue(MediaDetect.parse("not json").items.isEmpty())
        assertTrue(MediaDetect.parse("{}").items.isEmpty())
    }
}
