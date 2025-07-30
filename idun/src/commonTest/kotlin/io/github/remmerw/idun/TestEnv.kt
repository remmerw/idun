package io.github.remmerw.idun

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


    fun loopbackAddress(port: Int): InetSocketAddress {
        return InetSocketAddress(InetAddress.getLoopbackAddress(), port)
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
