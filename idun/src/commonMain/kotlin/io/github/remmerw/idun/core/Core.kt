package io.github.remmerw.idun.core

import com.eygraber.uri.Uri
import io.github.remmerw.asen.PeerId
import io.github.remmerw.asen.Peeraddr
import io.github.remmerw.asen.decode58
import io.github.remmerw.asen.encode58
import io.github.remmerw.asen.parsePeeraddr
import io.github.remmerw.idun.PNS
import io.github.remmerw.idun.debug
import kotlin.io.encoding.ExperimentalEncodingApi

const val EOF: Int = -1

internal fun encodePeeraddr(peeraddr: Peeraddr): String {
    return encode58(peeraddr.encoded())

}

internal fun encodePeerId(peerId: PeerId): String {
    return encode58(peerId.hash)
}

@OptIn(ExperimentalStdlibApi::class)
internal fun encodeCid(cid: Long): String {
    return cid.toString(radix = 16)
}

@OptIn(ExperimentalEncodingApi::class)
internal fun decodeCid(data: String): Long {
    return data.toLong(radix = 16)
}

internal fun extractCidFromRequest(request: String): Long? {
    val pns = Uri.parse(request)
    validate(pns)
    var path = pns.path
    if (path != null) {
        path = path.trim().removePrefix("/")
        if (path.isNotBlank()) {
            val cid = decodeCid(path)
            return cid
        }
    }
    return null
}


internal fun extractPeeraddrFromRequest(request: String): Peeraddr? {
    val pns = Uri.parse(request)
    val host = validate(pns)
    val userInfo = pns.userInfo
    if (userInfo != null && userInfo.isNotBlank()) {
        try {
            val raw = decode58(userInfo)
            val peerId = decodePeerId(host)
            return parsePeeraddr(peerId, raw)
        } catch (throwable: Throwable) {
            debug(throwable)
        }
    }
    return null
}

internal fun validate(pns: Uri): String {
    checkNotNull(pns.scheme) { "Scheme not defined" }
    require(pns.scheme == PNS) { "Scheme not pns" }
    val host = pns.host
    checkNotNull(host) { "Host not defined" }
    require(host.isNotBlank()) { "Host is empty" }
    return host
}

internal fun extractPeerIdFromRequest(request: String): PeerId {
    val pns = Uri.parse(request)
    val host = validate(pns)
    return decodePeerId(host)
}

private fun decodePeerId(data: String): PeerId {
    return PeerId(decode58(data))
}



