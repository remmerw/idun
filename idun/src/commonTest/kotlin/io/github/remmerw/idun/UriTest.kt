package io.github.remmerw.idun

import com.eygraber.uri.Uri
import io.github.remmerw.borr.PeerId
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class UriTest {

    @Test
    fun testUri() {
        val peerId = PeerId(Random.nextBytes(32))
        val cid = 5L
        val uriString = pnsUri(
            peerId, cid, mapOf(
                "a" to "b",
                "c" to "d"
            )
        )


        val uri = Uri.parse(uriString)

        assertEquals(uri.extractCid(), cid)
        assertEquals(uri.extractPeerId(), peerId)

        val b = uri.getQueryParameter("a")
        assertEquals(b, "b")

        val d = uri.getQueryParameter("c")
        assertEquals(d, "d")

        println(uriString)

    }
}