package io.github.remmerw.idun.core

import io.github.remmerw.idun.Node

internal data class Raw(
    override val cid: Long,
    override val size: Long,
    override val name: String,
    override val mimeType: String,
    val data: ByteArray,
) : Node {

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as Raw

        if (cid != other.cid) return false
        if (!data.contentEquals(other.data)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = cid.hashCode()
        result = 31 * result + data.contentHashCode()
        return result
    }
}
