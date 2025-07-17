package io.github.remmerw.idun

import io.github.remmerw.borr.PeerId
import io.github.remmerw.asen.Peeraddr
import io.github.remmerw.asen.SocketAddress
import io.github.remmerw.idun.core.OCTET_MIME_TYPE
import kotlinx.io.Buffer
import kotlinx.io.buffered
import kotlinx.io.files.SystemFileSystem
import kotlin.random.Random


internal object TestEnv {
    const val ITERATIONS: Int = 4096
    private const val DEBUG = true


    fun randomBytes(number: Int): ByteArray {
        val bytes = ByteArray(number)
        Random.nextBytes(bytes)
        return bytes
    }

    fun randomLong(): Long {
        return Random.nextLong()
    }

    fun createContent(storage: Storage, name: String, data: ByteArray): Node {
        val source = Buffer()
        source.write(data)

        return storage.storeSource(source, name, OCTET_MIME_TYPE)
    }

    fun createContent(storage: Storage, iteration: Int): Node {
        val temp = storage.tempFile()
        SystemFileSystem.sink(temp).buffered().use { source ->
            repeat(iteration) {
                source.write(randomBytes(splitterSize()))
            }
        }

        val node = storage.storeFile(temp, OCTET_MIME_TYPE)

        SystemFileSystem.delete(temp)
        return node
    }


    fun supportLongRunningTests(): Boolean {
        return DEBUG
    }

    internal fun loopbackPeeraddr(peerId: PeerId, port: Int): Peeraddr {
        return Peeraddr(peerId, byteArrayOf(127, 0, 0, 1), port.toUShort())
    }

    fun loopbackAddress(port: Int): List<SocketAddress> {
        return listOf(
            SocketAddress(byteArrayOf(127, 0, 0, 1), port.toUShort())
        )
    }

    fun randomPort(): Int {
        return Random.nextInt(4001, 65535)
    }

    fun createContent(
        storage: Storage,
        name: String,
        mimeType: String,
        data: ByteArray
    ): Node {
        val source = Buffer()
        source.write(data)

        return storage.storeSource(source, name, mimeType)

    }

}
