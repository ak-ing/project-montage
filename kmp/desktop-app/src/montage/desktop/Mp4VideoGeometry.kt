// SPDX-License-Identifier: Apache-2.0
package montage.desktop

import java.io.File
import java.io.RandomAccessFile

/** Reads bounded ISO-BMFF metadata; skips mdat without loading or decoding the video into memory. */
internal data class Mp4VideoGeometry(val width: Int, val height: Int, val rotation: Int?) {
    companion object {
        fun read(file: File): Mp4VideoGeometry? = runCatching {
            RandomAccessFile(file, "r").use { input ->
                data class Atom(val kind: String, val payload: Long, val end: Long)
                var remaining = 2048
                fun children(start: Long, end: Long): List<Atom> {
                    val result = mutableListOf<Atom>()
                    var offset = start
                    while (end - offset >= 8 && --remaining > 0) {
                        input.seek(offset)
                        var size = input.readInt().toLong() and 0xffffffffL
                        val kind = ByteArray(4).also { input.readFully(it) }.toString(Charsets.ISO_8859_1)
                        var header = 8L
                        if (size == 1L) { size = input.readLong(); header = 16 }
                        if (size == 0L) size = end - offset
                        if (size < header || size > end - offset) return emptyList()
                        result += Atom(kind, offset + header, offset + size)
                        offset += size
                    }
                    return result
                }
                val movie = children(0, input.length()).firstOrNull { it.kind == "moov" } ?: return@use null
                for (track in children(movie.payload, movie.end).filter { it.kind == "trak" }) {
                    val atoms = children(track.payload, track.end)
                    val media = atoms.firstOrNull { it.kind == "mdia" } ?: continue
                    val handler = children(media.payload, media.end).firstOrNull { it.kind == "hdlr" } ?: continue
                    if (handler.end - handler.payload < 12) continue
                    input.seek(handler.payload + 8)
                    if (input.readInt() != 0x76696465) continue // vide
                    val header = atoms.firstOrNull { it.kind == "tkhd" } ?: continue
                    input.seek(header.payload)
                    val version = input.readUnsignedByte()
                    val matrix = header.payload + when (version) { 0 -> 40; 1 -> 52; else -> continue }
                    if (header.end - matrix < 44) continue
                    input.seek(matrix)
                    val a = input.readInt(); val b = input.readInt(); input.readInt()
                    val c = input.readInt(); val d = input.readInt()
                    val rotation = when (listOf(a, b, c, d)) {
                        listOf(65536, 0, 0, 65536) -> 0
                        listOf(0, 65536, -65536, 0) -> 90
                        listOf(-65536, 0, 0, -65536) -> 180
                        listOf(0, -65536, 65536, 0) -> 270
                        else -> null // Mirroring or arbitrary transforms are not reported as an invented rotation.
                    }
                    input.seek(matrix + 36)
                    val width = input.readInt() ushr 16
                    val height = input.readInt() ushr 16
                    if (width in 1..16384 && height in 1..16384) return@use Mp4VideoGeometry(width, height, rotation)
                }
                null
            }
        }.getOrNull()
    }
}
