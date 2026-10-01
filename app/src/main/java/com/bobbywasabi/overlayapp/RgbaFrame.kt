package com.bobbywasabi.overlayapp

import java.nio.ByteBuffer

/** Copies RGBA_8888 without treating row padding as visible pixels. */
object RgbaFrame {
    fun copy(buffer: ByteBuffer, width: Int, height: Int, rowStride: Int, pixelStride: Int, output: IntArray) {
        require(width > 0 && height > 0 && width.toLong() * height <= output.size)
        require(pixelStride >= 4 && rowStride.toLong() >= (width - 1L) * pixelStride + 4)
        val start = buffer.position()
        val end = start + (height - 1L) * rowStride + (width - 1L) * pixelStride + 4
        require(end <= buffer.limit()) { "Incomplete RGBA frame" }
        var destination = 0
        for (y in 0 until height) {
            var offset = start + y * rowStride
            for (x in 0 until width) {
                val red = buffer.get(offset).toInt() and 255
                val green = buffer.get(offset + 1).toInt() and 255
                val blue = buffer.get(offset + 2).toInt() and 255
                output[destination++] = (0xff shl 24) or (red shl 16) or (green shl 8) or blue
                offset += pixelStride
            }
        }
    }
}
