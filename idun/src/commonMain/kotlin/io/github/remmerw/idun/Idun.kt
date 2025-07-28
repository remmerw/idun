package io.github.remmerw.idun

import com.eygraber.uri.Uri
import io.github.remmerw.asen.HolePunch
import io.github.remmerw.asen.MemoryPeers
import io.github.remmerw.asen.PeerStore
import io.github.remmerw.asen.Peeraddr
import io.github.remmerw.asen.bootstrap
import io.github.remmerw.asen.newAsen
import io.github.remmerw.borr.Keys
import io.github.remmerw.borr.PeerId
import io.github.remmerw.borr.decode58
import io.github.remmerw.borr.encode58
import io.github.remmerw.borr.generateKeys
import io.github.remmerw.dagr.Acceptor
import io.github.remmerw.dagr.Connection
import io.github.remmerw.dagr.newDagr
import io.github.remmerw.idun.core.Connector
import io.github.remmerw.idun.core.Fid
import io.github.remmerw.idun.core.FidChannel
import io.github.remmerw.idun.core.Raw
import io.github.remmerw.idun.core.RawChannel
import io.github.remmerw.idun.core.Stream
import io.github.remmerw.idun.core.Type
import io.github.remmerw.idun.core.createRaw
import io.github.remmerw.idun.core.decodeNode
import io.github.remmerw.idun.core.decodeType
import io.github.remmerw.idun.core.removeNode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.Buffer
import kotlinx.io.RawSink
import kotlinx.io.RawSource
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.readByteArray
import java.io.InputStream
import java.net.InetSocketAddress
import kotlin.concurrent.Volatile
import kotlin.random.Random
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

internal const val RESOLVE_TIMEOUT: Int = 60
internal const val CONNECT_TIMEOUT: Int = 3

class Idun internal constructor(
    val storage: Storage?,
    port: Int,
    keys: Keys,
    bootstrap: List<Peeraddr>,
    peerStore: PeerStore
) : Acceptor {

    private val asen = newAsen(keys, bootstrap, peerStore, object : HolePunch {
        override fun invoke(
            peerId: PeerId,
            addresses: List<InetSocketAddress>
        ) {
            scope.launch {

                withTimeoutOrNull(1000) {
                    addresses.forEach { remoteAddress ->
                        try {
                            dagr.punching(remoteAddress)
                        } catch (throwable: Throwable) {
                            debug(throwable)
                        }

                    }
                    delay(Random.nextLong(50, 100))
                }

            }
        }
    })
    private val scope = CoroutineScope(Dispatchers.IO)
    private var dagr = newDagr(port, this)
    private val connector = Connector(dagr)


    fun localPort(): Int {
        return dagr.localPort()
    }

    suspend fun observedAddresses(): List<InetSocketAddress> {
        return asen.observedAddresses().map { address ->
            InetSocketAddress(address, localPort())
        }
    }

    suspend fun resolveAddresses(target: PeerId, timeout: Long): List<InetSocketAddress> {
        return asen.resolveAddresses(target, timeout)
    }

    fun keys(): Keys {
        return asen.keys()
    }


    fun numReservations(): Int {
        return asen.numReservations()
    }

    fun numIncomingConnections(): Int {
        return dagr.numIncomingConnections()
    }

    fun numOutgoingConnections(): Int {
        return dagr.numOutgoingConnections()
    }

    internal suspend fun fetchRoot(peerId: PeerId): Long {
        val connection = connector.connect(asen, peerId)
        val sink = Buffer()
        connection.fetchBlock(sink, HALO_ROOT)
        return sink.readLong()
    }


    suspend fun transferTo(rawSink: RawSink, request: String, progress: (Float) -> Unit = {}) {

        val uri = Uri.parse(request)
        val cid = uri.extractCid()
        val peerId = uri.extractPeerId()

        val channel = channel(peerId, cid)
        val size = channel.size()
        var remember = 0
        var totalRead = 0L
        val buffer = Buffer()
        do {
            val read = channel.next(buffer)
            if (read > 0) {
                totalRead += buffer.transferTo(rawSink)

                if (totalRead > 0) {
                    val percent = ((totalRead * 100.0f) / size).toInt()
                    if (percent > remember) {
                        remember = percent
                        progress.invoke(percent / 100.0f)
                    }
                }
            }
        } while (read > 0)
    }

    @Suppress("unused")
    suspend fun info(request: String): Node {
        val uri = Uri.parse(request)
        val cid = uri.extractCid()
        val peerId = uri.extractPeerId()
        return info(peerId, cid)
    }


    suspend fun request(request: String): Response {
        val uri = Uri.parse(request)
        val cid = uri.extractCid()
        val peerId = uri.extractPeerId()
        return request(peerId, cid)
    }

    suspend fun request(peerId: PeerId, cid: Long? = null): Response {
        try {
            val node = info(peerId, cid) // is resolved
            return if (node is Fid) {
                val channel = channel(peerId, cid)
                contentResponse(channel, node)
            } else {
                contentResponse(RawChannel((node as Raw).data()), "OK", 200)
            }
        } catch (throwable: Throwable) {
            var message = throwable.message
            if (message.isNullOrEmpty()) {
                message = "Service unavailable"
            }
            return contentResponse(RawChannel(byteArrayOf()), message, 500)

        }
    }


    suspend fun channel(peerId: PeerId, cid: Long? = null): Channel {
        val node = info(peerId, cid)
        val connection = connector.connect(asen, peerId)
        return createChannel(node, connection)
    }

    fun peerId(): PeerId {
        return asen.peerId()
    }


    suspend fun publishAddresses(
        addresses: List<InetSocketAddress>,
        maxPublifications: Int,
        timeout: Int
    ) {
        return asen.makeReservations(addresses, maxPublifications, timeout)
    }


    suspend fun info(peerId: PeerId, cid: Long? = null): Node {
        if (cid != null) {
            val connection = connector.connect(asen, peerId)
            val buffer = Buffer()
            connection.fetchBlock(buffer, cid)
            return decodeNode(cid, buffer)
        } else {
            return info(peerId, fetchRoot(peerId))
        }
    }

    suspend fun fetchRaw(peerId: PeerId, cid: Long): ByteArray {
        val connection = connector.connect(asen, peerId)
        val buffer = Buffer()
        connection.fetchBlock(buffer, cid)
        val type: Type = decodeType(buffer.readByte())

        require(type == Type.RAW) { "cid does not reference a raw node" }
        return buffer.readByteArray()
    }

    // this is just for testing purpose
    fun reachable(peeraddr: Peeraddr) {
        connector.reachable(peeraddr)
    }


    suspend fun shutdown() {

        try {
            asen.shutdown()
        } catch (throwable: Throwable) {
            debug(throwable)
        }

        try {
            connector.shutdown()
        } catch (throwable: Throwable) {
            debug(throwable)
        }

        try {
            dagr.shutdown()
        } catch (throwable: Throwable) {
            debug(throwable)
        }

        try {
            scope.cancel()
        } catch (throwable: Throwable) {
            debug(throwable)
        }
    }

    override fun accept(connection: Connection) {
        if (storage != null) {
            scope.launch {
                try {
                    while (true) {
                        val cid = connection.readLong()
                        val root = storage.root()
                        if (cid == HALO_ROOT) { // root request
                            // root cid
                            val buffer = Buffer()
                            buffer.writeInt(Long.SIZE_BYTES)
                            buffer.writeLong(root.cid())
                            connection.writeBuffer(buffer)

                        } else {
                            val sink = Buffer()
                            storage.fetchBlock(sink, cid)
                            connection.writeInt(sink.size.toInt())
                            connection.writeBuffer(sink)
                        }
                        connection.flush()
                    }
                } catch (_: InterruptedException) {
                    // nothing to do here (connection was closed)
                } catch (throwable: Throwable) {
                    debug(throwable)
                } finally {
                    connection.close()
                }
            }
        }
    }
}


fun newIdun(
    storage: Storage? = null,
    port: Int = 0,
    keys: Keys = generateKeys(),
    bootstrap: List<Peeraddr> = bootstrap(),
    peerStore: PeerStore = MemoryPeers()
): Idun {
    return Idun(storage, port, keys, bootstrap, peerStore)
}


interface Channel {
    fun size(): Long
    fun seek(offset: Long)
    fun next(buffer: Buffer): Int

    /**
     * Read all the bytes from the current offset to the end
     */
    fun readBytes(): ByteArray
}

data class Response(
    val mimeType: String,
    val encoding: String,
    val status: Int,
    val reason: String,
    val headers: Map<String, String>,
    val channel: Channel
)

internal const val MAX_CHARS_SIZE = 4096
private const val SPLITTER_SIZE = Short.MAX_VALUE
const val HALO_ROOT = 0L

fun splitterSize(): Int {
    return SPLITTER_SIZE.toInt()
}

// this is only temporary (will be replaced when kotlinx io has seekable stream)
fun Channel.asInputStream(): InputStream {
    return Stream(this)
}

interface Node {
    fun cid(): Long
    fun size(): Long
    fun name(): String
    fun mimeType(): String
}

data class Storage(private val directory: Path) : Fetch {
    @Volatile
    private var root: Node = createRaw(this, byteArrayOf()) {
        nextCid()
    }

    fun directory(): Path {
        return directory
    }

    fun root(data: ByteArray) {
        root(createRaw(this, data) {
            nextCid()
        })
    }

    fun root(node: Node) {
        root = node
    }

    fun root(): Node {
        return root
    }

    fun reset() {
        root(byteArrayOf())
        cleanupDirectory(directory)
    }

    fun delete() {
        cleanupDirectory(directory)
        SystemFileSystem.delete(directory, false)
    }

    fun hasBlock(cid: Long): Boolean {
        require(cid != HALO_ROOT) { "Invalid Cid" }
        return SystemFileSystem.exists(path(cid))
    }

    override fun fetchBlock(sink: Buffer, cid: Long) {
        require(cid != HALO_ROOT) { "Invalid Cid" }
        val file = path(cid)
        require(SystemFileSystem.exists(file)) { "Block does not exists" }

        SystemFileSystem.source(file).buffered().use { source ->
            source.transferTo(sink)
        }
    }

    fun storeBlock(cid: Long, buffer: Buffer) {
        require(cid != HALO_ROOT) { "Invalid Cid" }
        val file = path(cid)
        SystemFileSystem.sink(file, false).use { sink ->
            sink.write(buffer, buffer.size)
        }
    }

    @OptIn(ExperimentalUuidApi::class)
    fun tempFile(): Path {
        return Path(directory, Uuid.random().toHexString())
    }

    @OptIn(ExperimentalStdlibApi::class)
    private fun path(cid: Long): Path {
        require(cid != HALO_ROOT) { "Invalid Cid" }
        return Path(directory, cid.toHexString())
    }

    fun deleteBlock(cid: Long) {
        require(cid != HALO_ROOT) { "Invalid Cid" }
        val file = path(cid)
        SystemFileSystem.delete(file, false)
    }

    fun nextCid(): Long {
        val cid = Random.nextLong()
        if (cid != HALO_ROOT) {
            val exists = hasBlock(cid)
            if (!exists) {
                return cid
            }
        }
        return nextCid()
    }


    fun info(cid: Long): Node {
        val sink = Buffer()
        fetchBlock(sink, cid)
        return decodeNode(cid, sink)
    }

    // Note: remove the cid block (add all links blocks recursively)
    fun delete(node: Node) {
        removeNode(this, node)
    }

    fun storeData(data: ByteArray): Node {
        return createRaw(this, data) {
            nextCid()
        }
    }

    fun storeText(data: String): Node {
        return storeData(data.encodeToByteArray())
    }

    fun storeFile(path: Path, mimeType: String): Node {
        require(SystemFileSystem.exists(path)) { "Path does not exists" }
        val metadata = SystemFileSystem.metadataOrNull(path)
        checkNotNull(metadata) { "Path has no metadata" }
        require(metadata.isRegularFile) { "Path is not a regular file" }
        require(mimeType.isNotBlank()) { "MimeType is blank" }
        SystemFileSystem.source(path).use { source ->
            return storeSource(source, path.name, mimeType)
        }

    }

    fun transferTo(node: Node, path: Path) {
        SystemFileSystem.sink(path, false).use { sink ->
            val buffer = Buffer()
            val channel = channel(node)
            do {
                val data = channel.next(buffer)
                if (data > 0) {
                    buffer.transferTo(sink)
                }
            } while (data > 0)
        }
    }

    fun channel(node: Node): Channel {
        return createChannel(node, this)
    }

    fun storeSource(source: RawSource, name: String, mimeType: String): Node {
        return io.github.remmerw.idun.core.storeSource(this, source, name, mimeType) {
            nextCid()
        }
    }

    fun fetchData(node: Node): ByteArray {
        return io.github.remmerw.idun.core.fetchData(node, this)
    }

    fun fetchText(node: Node): String {
        return fetchData(node).decodeToString()
    }

}


fun contentResponse(
    channel: Channel, msg: String, status: Int
): Response {
    return Response(
        "text/html", "UTF-8", status,
        msg, emptyMap(), channel
    )
}

const val CONTENT_TYPE: String = "Content-Type"
const val CONTENT_LENGTH: String = "Content-Length"
const val CONTENT_TITLE: String = "Content-Title"

fun contentResponse(channel: Channel, node: Node): Response {
    val mimeType = node.mimeType()
    val responseHeaders: MutableMap<String, String> = mutableMapOf()
    responseHeaders[CONTENT_LENGTH] = node.size().toString()
    responseHeaders[CONTENT_TYPE] = mimeType
    responseHeaders[CONTENT_TITLE] = node.name()
    return Response(
        mimeType, "UTF-8", 200,
        "OK", responseHeaders, channel
    )
}

fun cleanupDirectory(dir: Path) {
    if (SystemFileSystem.exists(dir)) {
        val files = SystemFileSystem.list(dir)
        for (file in files) {
            SystemFileSystem.delete(file)
        }
    }
}

fun newStorage(): Storage {
    return newStorage(tempDirectory())
}

@OptIn(ExperimentalUuidApi::class)
private fun tempDirectory(): Path {
    val path = Path(SystemTemporaryDirectory, Uuid.random().toHexString())
    SystemFileSystem.createDirectories(path)
    return path
}


fun newStorage(directory: Path): Storage {
    SystemFileSystem.createDirectories(directory)
    require(
        SystemFileSystem.metadataOrNull(directory)?.isDirectory == true
    ) {
        "Path is not a directory."
    }
    return Storage(directory)
}

fun decodeNode(cid: Long, block: Buffer): Node {
    return decodeNode(cid, block)
}

interface Fetch {
    fun fetchBlock(sink: Buffer, cid: Long)
}

fun Uri.extractPeerId(): PeerId {
    val host = validate(this)
    return PeerId(decode58(host))
}

private fun validate(pns: Uri): String {
    checkNotNull(pns.scheme) { "Scheme not defined" }
    require(pns.scheme == "pns") { "Scheme not pns" }
    val host = pns.host
    checkNotNull(host) { "Host not defined" }
    require(host.isNotBlank()) { "Host is empty" }
    return host
}

fun Uri.extractCid(): Long? {
    var path = this.path
    if (path != null) {
        path = path.trim().removePrefix("/")
        if (path.isNotBlank()) {
            val cid = path.hexToLong()
            return cid
        }
    }
    return null
}

fun createChannel(node: Node, fetch: Fetch): Channel {
    val size = node.size()
    if (node is Fid) {
        return FidChannel(node, size, fetch)
    }
    val raw = node as Raw
    return RawChannel(raw.data())
}

fun pnsUri(peerId: PeerId): String {
    return "pns://" + encode58(peerId.hash)
}

fun pnsUri(peerId: PeerId, cid: Long): String {
    return pnsUri(peerId) + "/" + cid.toHexString()
}


fun debug(throwable: Throwable) {
    if (ERROR) {
        throwable.printStackTrace()
    }
}

private const val ERROR: Boolean = true
