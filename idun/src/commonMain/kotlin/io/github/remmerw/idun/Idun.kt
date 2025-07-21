package io.github.remmerw.idun

import com.eygraber.uri.Uri
import io.github.remmerw.asen.HolePunch
import io.github.remmerw.asen.MemoryPeers
import io.github.remmerw.asen.PeerStore
import io.github.remmerw.asen.Peeraddr
import io.github.remmerw.asen.SocketAddress
import io.github.remmerw.asen.bootstrap
import io.github.remmerw.asen.core.hostname
import io.github.remmerw.asen.decode58
import io.github.remmerw.asen.encode58
import io.github.remmerw.asen.newAsen
import io.github.remmerw.borr.Keys
import io.github.remmerw.borr.PeerId
import io.github.remmerw.borr.generateKeys
import io.github.remmerw.dagr.Acceptor
import io.github.remmerw.dagr.Connection
import io.github.remmerw.dagr.Dagr
import io.github.remmerw.dagr.newDagr
import io.github.remmerw.idun.core.Connector
import io.github.remmerw.idun.core.FetchRequest
import io.github.remmerw.idun.core.Fid
import io.github.remmerw.idun.core.FidChannel
import io.github.remmerw.idun.core.Raw
import io.github.remmerw.idun.core.RawChannel
import io.github.remmerw.idun.core.Stream
import io.github.remmerw.idun.core.createRaw
import io.github.remmerw.idun.core.decodeNode
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
import java.io.InputStream
import java.net.InetSocketAddress
import kotlin.concurrent.Volatile
import kotlin.random.Random
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

internal const val RESOLVE_TIMEOUT: Int = 60
internal const val CONNECT_TIMEOUT: Int = 3

class Idun internal constructor(keys: Keys, bootstrap: List<Peeraddr>, peerStore: PeerStore) {

    private val asen = newAsen(keys, bootstrap, peerStore, object : HolePunch {
        override fun invoke(
            peerId: PeerId,
            addresses: List<SocketAddress>
        ) {
            scope.launch {
                if (dagr != null) {

                    withTimeoutOrNull(1000) {
                        addresses.forEach { socketAddress ->
                            val remoteAddress = InetSocketAddress(
                                hostname(socketAddress.address),
                                socketAddress.port.toInt()
                            )
                            dagr!!.punching(remoteAddress)

                        }
                        delay(Random.nextLong(50, 100))
                    }
                }
            }
        }

    })
    private val scope = CoroutineScope(Dispatchers.IO)
    private val connector = Connector()
    private var dagr: Dagr? = null


    suspend fun observedAddresses(port: Int): List<SocketAddress> {
        return asen.observedAddresses().map { address ->
            SocketAddress(address.bytes, port.toUShort())
        }
    }

    /**
     * starts the server with the given port
     */
    fun startup(storage: Storage, port: Int) {

        dagr = newDagr(port, object : Acceptor {
            override suspend fun accept(connection: Connection) {
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
                            connection.writeInt(storage.blockSize(cid))
                            storage.getBlock(cid).use { source ->
                                connection.writeBuffer(source)
                            }
                        }
                    }
                } catch (_: Throwable) { // ignore happens when connection is closed
                } finally {
                    connection.close()
                }
            }
        })
    }


    suspend fun resolveAddresses(target: PeerId, timeout: Long): List<SocketAddress> {
        return asen.resolveAddresses(target, timeout)
    }

    fun keys(): Keys {
        return asen.keys()
    }

    fun reservations(): List<String> {
        return asen.reservations()
    }

    fun hasReservations(): Boolean {
        return asen.hasReservations()
    }

    fun numReservations(): Int {
        return asen.numReservations()
    }

    fun numIncomingConnections(): Int {
        return incomingConnections().size
    }

    fun incomingConnections(): Set<String> {
        val result: MutableSet<String> = mutableSetOf()
        dagr?.connections()?.forEach { connection ->
            result.add(connection.remoteAddress().toString())
        }
        return result

    }

    fun numOutgoingConnections(): Int {
        return connector.numConnections()
    }

    internal suspend fun fetchRoot(peerId: PeerId): Long {
        val connection = connector.connect(asen, peerId)
        val payload = connection.request(HALO_ROOT)
        payload.use { source ->
            return source.readLong()
        }
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
                totalRead += read
                buffer.transferTo(rawSink)

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
        val fetch = FetchRequest(asen, connector, peerId)
        return createChannel(node, fetch)
    }

    fun peerId(): PeerId {
        return asen.peerId()
    }


    suspend fun publishAddresses(
        addresses: List<SocketAddress>,
        maxPublifications: Int,
        timeout: Int
    ) {
        return asen.makeReservations(addresses, maxPublifications, timeout)
    }


    suspend fun info(peerId: PeerId, cid: Long? = null): Node {
        if (cid != null) {
            val fetch = FetchRequest(asen, connector, peerId)
            val buffer = Buffer()
            fetch.fetchBlock(buffer, cid)
            return decodeNode(cid, buffer)
        } else {
            return info(peerId, fetchRoot(peerId))
        }
    }

    suspend fun fetchData(peerId: PeerId, cid: Long? = null): ByteArray {
        val node = info(peerId, cid)
        if (node is Raw) {
            return node.data()
        }
        throw Exception("Uri does not reference raw node")
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
            dagr?.shutdown()
        } catch (throwable: Throwable) {
            debug(throwable)
        }

        try {
            scope.cancel()
        } catch (throwable: Throwable) {
            debug(throwable)
        }
    }


}


fun newIdun(
    keys: Keys = generateKeys(),
    bootstrap: List<Peeraddr> = bootstrap(),
    peerStore: PeerStore = MemoryPeers()
): Idun {
    return Idun(keys, bootstrap, peerStore)
}


interface Channel {
    fun size(): Long
    fun seek(offset: Long)
    suspend fun next(buffer: Buffer): Int

    /**
     * Read all the bytes from the current offset to the end
     */
    suspend fun readBytes(): ByteArray
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
private const val SPLITTER_SIZE = UShort.MAX_VALUE
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

    fun blockSize(cid: Long): Int {
        require(cid != HALO_ROOT) { "Invalid Cid" }
        val file = path(cid)
        require(SystemFileSystem.exists(file)) { "Block does not exists" }
        return SystemFileSystem.metadataOrNull(file)!!.size.toInt()
    }


    override suspend fun fetchBlock(rawSink: RawSink, cid: Long, offset: Int): Int {
        getBlock(cid).buffered().use { source ->
            if (offset > 0) {
                source.skip(offset.toLong())
            }
            return source.transferTo(rawSink).toInt()

        }
    }

    fun getBlock(cid: Long): RawSource {
        require(cid != HALO_ROOT) { "Invalid Cid" }
        val file = path(cid)
        require(SystemFileSystem.exists(file)) { "Block does not exists" }
        return SystemFileSystem.source(file)
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
        getBlock(cid).use { block ->
            return decodeNode(cid, block)
        }
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

    suspend fun transferTo(node: Node, path: Path) {
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

    suspend fun fetchData(node: Node): ByteArray {
        return io.github.remmerw.idun.core.fetchData(node, this)
    }

    suspend fun fetchText(node: Node): String {
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
    suspend fun fetchBlock(rawSink: RawSink, cid: Long, offset: Int = 0): Int
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
            val cid = path.toLong(radix = 16)
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
    return pnsUri(peerId) + "/" + cid.toString(radix = 16)
}


fun debug(throwable: Throwable) {
    if (ERROR) {
        throwable.printStackTrace()
    }
}

private const val ERROR: Boolean = true
