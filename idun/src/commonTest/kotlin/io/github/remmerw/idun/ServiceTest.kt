package io.github.remmerw.idun

import io.github.remmerw.asen.core.AddressUtil
import io.github.remmerw.asen.createPeeraddr
import io.github.remmerw.asen.parsePeeraddr
import io.github.remmerw.idun.core.extractCidFromRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class ServiceTest {


    @Test
    fun stressPeerIdUris() {
        repeat(TestEnv.ITERATIONS) {
            val peerId = TestEnv.randomPeerId()

            val cid = TestEnv.randomLong()

            val test = createRequest(peerId, cid)
            assertNotNull(test)

            val extractPeeraddr = extractPeerId(test)
            assertEquals(extractPeeraddr, peerId)

            val extractCid = extractCid(test)
            assertEquals(extractCid, cid)
        }
    }

    @Test
    fun stressPeeraddrUris() {
        repeat(TestEnv.ITERATIONS) {
            val peerId = TestEnv.randomPeerId()

            val peeraddr = createPeeraddr(
                peerId, byteArrayOf(127, 0, 0, 1), 4001.toUShort()
            )
            assertNotNull(peeraddr)

            val cid = TestEnv.randomLong()

            val test = createRequest(peeraddr, cid)
            assertNotNull(test)

            val extractPeeraddr = extractPeeraddr(test)
            assertEquals(extractPeeraddr, peeraddr)

            val extractCid = extractCidFromRequest(test)
            assertEquals(extractCid, cid)
        }
    }


    @Test
    fun testAddress() {
        val peerId = TestEnv.randomPeerId()
        val address = AddressUtil.textToNumericFormatV6("2804:d41:432f:3f00:ccbd:8e0d:a023:376b")
        assertNotNull(address)
        val peeraddr = createPeeraddr(
            peerId.toBase58(), address, 4001.toUShort()
        )
        assertNotNull(peeraddr)


        val cmp = parsePeeraddr(peerId, peeraddr.encoded())
        assertNotNull(cmp)
        assertEquals(peeraddr.peerId, cmp.peerId)
        assertEquals(peeraddr, cmp)

        val uri = createRequest(peeraddr)
        assertNotNull(uri)

        val extractPeeraddr = extractPeeraddr(uri)
        assertEquals(extractPeeraddr, peeraddr)
    }

}
