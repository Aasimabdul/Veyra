package com.veyra.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadNamingTest {
    @Test fun usesContentDispositionFirst() {
        assertEquals("report.pdf", DownloadNaming.fileName("attachment; filename=\"report.pdf\"", "https://x.test/a/b"))
    }

    @Test fun fallsBackToUrlPathThenDefault() {
        assertEquals("video.mp4", DownloadNaming.fileName(null, "https://x.test/media/video.mp4?token=1"))
        assertEquals("download", DownloadNaming.fileName(null, "https://x.test/"))
    }

    @Test fun stripsPathSeparatorsAndDecodes() {
        assertEquals("a b.txt", DownloadNaming.fileName(null, "https://x.test/a%20b.txt"))
        assertFalse(DownloadNaming.fileName("attachment; filename=\"../evil/x.sh\"", "https://x.test/").contains("/"))
    }

    @Test fun duplicateNamesGetCounters() {
        assertEquals("a.txt", DownloadNaming.uniqueName("a.txt", emptySet()))
        assertEquals("a (1).txt", DownloadNaming.uniqueName("a.txt", setOf("a.txt")))
        assertEquals("a (2).txt", DownloadNaming.uniqueName("a.txt", setOf("a.txt", "a (1).txt")))
        assertEquals("noext (1)", DownloadNaming.uniqueName("noext", setOf("noext")))
    }

    @Test fun formatsSizesAndSpeed() {
        assertEquals("512 B", DownloadNaming.formatBytes(512))
        assertEquals("1.5 MB", DownloadNaming.formatBytes(1536 * 1024L))
        assertEquals("unknown size", DownloadNaming.formatBytes(-1))
        assertEquals("", DownloadNaming.formatSpeed(0))
        assertEquals("2.0 KB/s", DownloadNaming.formatSpeed(2048))
    }

    @Test fun onlyHttpUrlsAreDownloadable() {
        assertTrue(DownloadNaming.isHttpUrl("https://a.test/x"))
        assertFalse(DownloadNaming.isHttpUrl("file:///sdcard/x"))
        assertFalse(DownloadNaming.isHttpUrl("javascript:alert(1)"))
    }

    @Test fun detectsDirectFileLinks() {
        assertTrue(DownloadNaming.looksLikeDirectFile("https://a.test/v/clip.MP4"))
        assertFalse(DownloadNaming.looksLikeDirectFile("https://a.test/page.html"))
    }
}
