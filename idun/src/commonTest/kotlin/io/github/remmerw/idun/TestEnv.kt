package io.github.remmerw.idun

import io.github.remmerw.asen.Peeraddr
import io.github.remmerw.borr.PeerId
import io.github.remmerw.idun.core.OCTET_MIME_TYPE
import kotlinx.io.buffered
import kotlinx.io.files.SystemFileSystem
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.random.Random


internal object TestEnv {
    const val ITERATIONS: Int = 4096

    fun randomBytes(number: Int): ByteArray {
        val bytes = ByteArray(number)
        Random.nextBytes(bytes)
        return bytes
    }

    fun randomLong(): Long {
        return Random.nextLong()
    }

    fun createContent(storage: Storage, name: String, data: ByteArray): Node {
        val temp = tempFile(name)
        SystemFileSystem.sink(temp).buffered().use { source ->
            source.write(data)
        }

        val node = storage.storeFile(temp, OCTET_MIME_TYPE)

        SystemFileSystem.delete(temp)
        return node
    }

    fun createContent(storage: Storage, iteration: Int): Node {
        val temp = tempFile()
        SystemFileSystem.sink(temp).buffered().use { source ->
            repeat(iteration) {
                source.write(randomBytes(splitterSize()))
            }
        }

        val node = storage.storeFile(temp, OCTET_MIME_TYPE)

        SystemFileSystem.delete(temp)
        return node
    }


    internal fun loopbackPeeraddr(peerId: PeerId, port: Int): Peeraddr {
        return Peeraddr(peerId, byteArrayOf(127, 0, 0, 1), port.toUShort())
    }

    fun loopbackAddress(port: Int): List<InetSocketAddress> {
        return listOf(
            InetSocketAddress(InetAddress.getLoopbackAddress(), port)
        )
    }

    fun createContent(
        storage: Storage,
        name: String,
        mimeType: String,
        data: ByteArray
    ): Node {
        val temp = tempFile(name)
        SystemFileSystem.sink(temp).buffered().use { source ->
            source.write(data)
        }

        val node = storage.storeFile(temp, mimeType)

        SystemFileSystem.delete(temp)
        return node

    }

}
