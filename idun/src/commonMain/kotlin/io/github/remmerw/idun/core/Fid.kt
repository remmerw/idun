package io.github.remmerw.idun.core

import io.github.remmerw.idun.MAX_CHARS_SIZE
import io.github.remmerw.idun.Node


internal data class Fid(
    override val cid: Long,
    override val size: Long,
    override val name: String,
    override val mimeType: String,
    val links: Int
) : Node {
    init {
        require(size >= 0) { "Invalid size" }
        require(name.length <= MAX_CHARS_SIZE) { "Invalid name length" }
    }
}

