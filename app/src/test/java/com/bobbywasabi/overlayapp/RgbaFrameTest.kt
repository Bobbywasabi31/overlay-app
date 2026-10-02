package com.bobbywasabi.overlayapp

import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

class RgbaFrameTest {
    @Test fun respectsRowAndPixelPaddingWithoutReadingPastFinalPixel() {
        // Two 8-byte pixels per row, 4 bytes of row padding, no final row padding.
        val buffer = ByteBuffer.allocate(32)
        val offsets = intArrayOf(0, 8, 20, 28)
        offsets.forEachIndexed { i, offset ->
            buffer.put(offset, (10 + i).toByte())
            buffer.put(offset + 1, (20 + i).toByte())
            buffer.put(offset + 2, (30 + i).toByte())
            buffer.put(offset + 3, 0xff.toByte())
        }
        val output = IntArray(4)
        RgbaFrame.copy(buffer, 2, 2, 20, 8, output)
        assertArrayEquals(intArrayOf(0xff0a141e.toInt(), 0xff0b151f.toInt(), 0xff0c1620.toInt(), 0xff0d1721.toInt()), output)
    }
    @Test fun respectsBufferPositionAndPreservesIt() {
        val buffer = ByteBuffer.wrap(byteArrayOf(99, 99, -1, 0, 0, -1))
        buffer.position(2)
        val output = IntArray(1)
        RgbaFrame.copy(buffer, 1, 1, 4, 4, output)
        assertEquals(0xffff0000.toInt(), output[0])
        assertEquals(2, buffer.position())
    }
    @Test(expected = IllegalArgumentException::class)
    fun rejectsShortBuffer() { RgbaFrame.copy(ByteBuffer.allocate(3), 1, 1, 4, 4, IntArray(1)) }
    @Test(expected = IllegalArgumentException::class)
    fun rejectsOverlappingRows() { RgbaFrame.copy(ByteBuffer.allocate(16), 2, 2, 4, 4, IntArray(4)) }
}
