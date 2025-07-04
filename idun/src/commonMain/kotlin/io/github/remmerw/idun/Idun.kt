package io.github.remmerw.idun

import io.github.remmerw.asen.Asen
import io.github.remmerw.asen.Keys
import io.github.remmerw.asen.MemoryPeers
import io.github.remmerw.asen.PeerId
import io.github.remmerw.asen.PeerStore
import io.github.remmerw.asen.Peeraddr
import io.github.remmerw.asen.bootstrap
import io.github.remmerw.asen.generateKeys
import io.github.remmerw.asen.newAsen
import io.github.remmerw.idun.core.Connector
import io.github.remmerw.idun.core.FetchRequest
import io.github.remmerw.idun.core.Fid
import io.github.remmerw.idun.core.FidChannel
import io.github.remmerw.idun.core.Raw
import io.github.remmerw.idun.core.RawChannel
import io.github.remmerw.idun.core.createRaw
import io.github.remmerw.idun.core.decodeNode
import io.github.remmerw.idun.core.removeNode
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.ServerSocket
import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.isClosed
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.util.collections.ConcurrentSet
import io.ktor.utils.io.readBuffer
import io.ktor.utils.io.writeBuffer
import io.ktor.utils.io.writeInt
import io.ktor.utils.io.writeLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.io.Buffer
import kotlinx.io.RawSink
import kotlinx.io.RawSource
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.concurrent.Volatile
import kotlin.random.Random
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

internal const val RESOLVE_TIMEOUT: Int = 60

class Idun internal constructor(private val asen: Asen) {

    private val incoming: MutableSet<Socket> = ConcurrentSet()
    private val selectorManager = SelectorManager(Dispatchers.IO)
    private val scope = SelectorManager(Dispatchers.IO)
    private val connector = Connector(selectorManager)
    private var serverSocket: ServerSocket? = null


    suspend fun observedPeeraddrs(port: Int): List<Peeraddr> {
        return asen.observedAddresses().map { address ->
            Peeraddr(peerId(), address, port.toUShort()) }
    }

    /**
     * starts the server with the given port
     */
    suspend fun startup(storage: Storage, port: Int) {

        serverSocket = aSocket(selectorManager).tcp().bind(
            InetSocketAddress("::", port)
        )
        scope.launch {
            try {
                while (isActive) {

                    // accept a connection
                    val socket = serverSocket!!.accept()


                    registerIncoming(socket)
                    launch {
                        handleConnection(storage, socket)
                    }
                }
            } catch (_: Throwable) {
            }
        }
    }


    private suspend fun handleConnection(storage: Storage, socket: Socket) {
        try {

            val receiveChannel = socket.openReadChannel()
            val sendChannel = socket.openWriteChannel(autoFlush = false)

            while (true) {

                val buffer = receiveChannel.readBuffer(Long.SIZE_BYTES)

                val read = buffer.size
                require(read.toInt() == Long.SIZE_BYTES) { "not enough data" }


                val cid = buffer.readLong()
                val root = storage.root()
                if (cid == HALO_ROOT) { // root request
                    // root cid
                    sendChannel.writeInt(Long.SIZE_BYTES)
                    sendChannel.writeLong(root.cid())

                } else {
                    sendChannel.writeInt(storage.blockSize(cid))
                    storage.getBlock(cid).use { source ->
                        sendChannel.writeBuffer(source)
                    }
                }
                sendChannel.flush()
            }
        } catch (_: Throwable) { // ignore happens when connection is closed
        } finally {
            removeIncoming(socket)
            socketClose(socket)
        }
    }

    private fun registerIncoming(socket: Socket) {
        incoming.add(socket)
    }

    private fun removeIncoming(socket: Socket) {
        incoming.remove(socket)
    }

    suspend fun resolveAddresses(relay: Peeraddr, target: PeerId): List<Peeraddr> {
        return asen.resolveAddresses(relay, target)
    }

    suspend fun resolveAddresses(target: PeerId, timeout: Long): List<Peeraddr> {
        return asen.resolveAddresses(target, timeout)
    }

    fun keys(): Keys {
        return asen.keys()
    }

    fun reservations(): List<Peeraddr> {
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

    fun incomingConnections(): List<String> {

        val result = mutableListOf<String>()
        for (connection in incoming) {
            if (!connection.isClosed) {
                result.add(connection.remoteAddress.toString())
            } else {
                incoming.remove(connection)
            }
        }
        return result

    }

    fun numOutgoingConnections(): Int {
        return connector.connections().size
    }

    internal suspend fun fetchRoot(peerId: PeerId): Long {
        val pnsChannel = connector.connect(asen, peerId)
        val payload = pnsChannel.request(HALO_ROOT)
        payload.buffered().use { source ->
            return source.readLong()
        }
    }


    @Suppress("unused")
    suspend fun response(peerId: PeerId, cid: Long? = null): Response {
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
        if (node is Fid) {
            val fetch = FetchRequest(asen, connector, peerId)
            return createChannel(node, fetch)
        }
        throw Exception("Stream on directory or raw not possible")
    }

    fun peerId(): PeerId {
        return asen.peerId()
    }

    /**
     * Makes a reservation o relay nodes with the purpose that other peers can fin you via
     * the nodes peerId
     *
     * @param peeraddrs Own addresses for reservations
     * @param maxReservation number of max reservations
     * @param timeout in seconds between reservations attempts
     */
    suspend fun makeReservations(
        peeraddrs: List<Peeraddr>,
        maxReservation: Int,
        timeout: Int
    ) {
        return asen.makeReservations(peeraddrs, maxReservation, timeout)
    }


    suspend fun info(peerId: PeerId, cid: Long? = null): Node {
        if (cid != null) {
            val fetch = FetchRequest(asen, connector, peerId)
            return decodeNode(cid, fetch.fetchBlock(cid))
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
            incoming.forEach { socket: Socket -> socketClose(socket) }
            incoming.clear()
        } catch (throwable: Throwable) {
            debug(throwable)
        }

        try {
            serverSocket?.close()
        } catch (throwable: Throwable) {
            debug(throwable)
        }

        try {
            scope.close()
        } catch (throwable: Throwable) {
            debug(throwable)
        }

        try {
            selectorManager.close()
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
    return Idun(newAsen(keys, bootstrap, peerStore))
}

internal fun socketClose(socket: Socket) {
    try {
        socket.close()
    } catch (throwable: Throwable) {
        debug(throwable)
    }
}


interface Channel {
    fun size(): Long
    fun seek(offset: Long)
    suspend fun transferTo(rawSink: RawSink, read: (Int) -> Unit)
    suspend fun readAllBytes(): ByteArray
    suspend fun next(): RawSource?
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


interface Node {
    fun cid(): Long
    fun size(): Long
    fun name(): String
    fun mimeType(): String
}

interface Storage : Fetch {

    fun reset()

    fun delete()

    fun directory(): Path

    fun hasBlock(cid: Long): Boolean

    fun blockSize(cid: Long): Int

    override suspend fun fetchBlock(cid: Long): RawSource

    fun getBlock(cid: Long): RawSource

    fun deleteBlock(cid: Long)

    fun nextCid(): Long

    fun storeBlock(cid: Long, buffer: Buffer)

    fun root(data: ByteArray)

    fun root(node: Node)

    fun root(): Node

    fun tempFile(): Path

    fun response(request: Long?): Response {
        val cid = request ?: root().cid()

        val node = info(cid)
        return if (node is Fid) {
            val channel = channel(node)
            contentResponse(channel, node)
        } else {
            val channel = channel(node)
            contentResponse(channel, "OK", 200)
        }
    }

    fun info(cid: Long): Node {
        val block = getBlock(cid)
        return decodeNode(cid, block)
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
            channel(node).transferTo(sink) {}
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

private class StorageImpl(private val directory: Path) : Storage {
    @Volatile
    private var root: Node = createRaw(this, byteArrayOf()) {
        nextCid()
    }

    override fun directory(): Path {
        return directory
    }

    override fun root(data: ByteArray) {
        root(createRaw(this, data) {
            nextCid()
        })
    }

    override fun root(node: Node) {
        root = node
    }

    override fun root(): Node {
        return root
    }

    override fun reset() {
        root(byteArrayOf())
        cleanupDirectory(directory)
    }

    override fun delete() {
        cleanupDirectory(directory)
        SystemFileSystem.delete(directory, false)
    }

    override fun hasBlock(cid: Long): Boolean {
        require(cid != HALO_ROOT) { "Invalid Cid" }
        return SystemFileSystem.exists(path(cid))
    }

    override fun blockSize(cid: Long): Int {
        require(cid != HALO_ROOT) { "Invalid Cid" }
        val file = path(cid)
        require(SystemFileSystem.exists(file)) { "Block does not exists" }
        return SystemFileSystem.metadataOrNull(file)!!.size.toInt()
    }


    override suspend fun fetchBlock(cid: Long): RawSource {
        return getBlock(cid)
    }

    override fun getBlock(cid: Long): RawSource {
        require(cid != HALO_ROOT) { "Invalid Cid" }
        val file = path(cid)
        require(SystemFileSystem.exists(file)) { "Block does not exists" }
        return SystemFileSystem.source(file)
    }

    override fun storeBlock(cid: Long, buffer: Buffer) {
        require(cid != HALO_ROOT) { "Invalid Cid" }
        val file = path(cid)
        SystemFileSystem.sink(file, false).use { sink ->
            sink.write(buffer, buffer.size)
        }
    }

    @OptIn(ExperimentalUuidApi::class)
    override fun tempFile(): Path {
        return Path(directory, Uuid.random().toHexString())
    }

    @OptIn(ExperimentalStdlibApi::class)
    private fun path(cid: Long): Path {
        require(cid != HALO_ROOT) { "Invalid Cid" }
        return Path(directory, cid.toHexString())
    }

    override fun deleteBlock(cid: Long) {
        require(cid != HALO_ROOT) { "Invalid Cid" }
        val file = path(cid)
        SystemFileSystem.delete(file, false)
    }

    override fun nextCid(): Long {
        val cid = Random.nextLong()
        if (cid != HALO_ROOT) {
            val exists = hasBlock(cid)
            if (!exists) {
                return cid
            }
        }
        return nextCid()
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
    return StorageImpl(directory)
}

fun decodeNode(cid: Long, block: Buffer): Node {
    return decodeNode(cid, block)
}

interface Fetch {
    suspend fun fetchBlock(cid: Long): RawSource
}

fun createChannel(node: Node, fetch: Fetch): Channel {
    val size = node.size()
    if (node is Fid) {
        return FidChannel(node, size, fetch)
    }
    val raw = node as Raw
    return RawChannel(raw.data())
}

fun debug(throwable: Throwable) {
    if (ERROR) {
        throwable.printStackTrace()
    }
}

private const val ERROR: Boolean = true
