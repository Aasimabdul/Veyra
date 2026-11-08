package com.veyra.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AddressResolverTest {
    @Test
    fun blankInputIsIgnored() {
        assertNull(AddressResolver.resolve("   \n"))
    }

    @Test
    fun validHttpAndHttpsUrlsArePreserved() {
        assertEquals("https://example.com/path?q=one", AddressResolver.resolve("https://example.com/path?q=one"))
        assertEquals("http://example.com", AddressResolver.resolve("http://example.com"))
    }

    @Test
    fun domainNamesReceiveHttps() {
        assertEquals("https://example.com/path", AddressResolver.resolve("example.com/path"))
        assertEquals("https://localhost:8080", AddressResolver.resolve("localhost:8080"))
    }

    @Test
    fun ordinaryTextIsEncodedAsSearch() {
        assertEquals(
            "https://www.google.com/search?q=hello+world",
            AddressResolver.resolve("hello world")
        )
    }

    @Test
    fun unsupportedSchemesAreNeverReturnedAsNavigationTargets() {
        assertEquals(
            "https://www.google.com/search?q=javascript%3Aalert%281%29",
            AddressResolver.resolve("javascript:alert(1)")
        )
        assertEquals(
            "https://www.google.com/search?q=file%3A%2F%2F%2Fprivate.txt",
            AddressResolver.resolve("file:///private.txt")
        )
    }

    @Test
    fun malformedHttpUrlFallsBackToSearch() {
        assertEquals(
            "https://www.google.com/search?q=https%3A%2F%2F",
            AddressResolver.resolve("https://")
        )
    }

    @Test
    fun urlsContainingEmbeddedCredentialsBecomeSearchText() {
        assertEquals(
            "https://www.google.com/search?q=https%3A%2F%2Fuser%3Asecret%40example.com",
            AddressResolver.resolve("https://user:secret@example.com")
        )
    }

    @Test
    fun outOfRangePortsBecomeSearchText() {
        assertEquals(
            "https://www.google.com/search?q=https%3A%2F%2Fexample.com%3A65536",
            AddressResolver.resolve("https://example.com:65536")
        )
        assertEquals(
            "https://www.google.com/search?q=localhost%3A65536",
            AddressResolver.resolve("localhost:65536")
        )
    }

    @Test
    fun zeroPortIsRejectedAsNavigationTarget() {
        assertEquals(
            "https://www.google.com/search?q=https%3A%2F%2Fexample.com%3A0",
            AddressResolver.resolve("https://example.com:0")
        )
    }
}
