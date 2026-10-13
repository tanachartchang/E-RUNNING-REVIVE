package com.bkpsoft.erunning

import com.bkpsoft.erunning.ble.HeartRateLink
import com.bkpsoft.erunning.ble.Protocol
import com.bkpsoft.erunning.ble.Protocol.Msg
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProtocolTest {
    private fun bytes(hex: String) = hex.split(" ").map { it.toInt(16).toByte() }.toByteArray()

    @Test fun framesMatchOriginalApp() {
        assertEquals("02 51 51 03", Protocol.hex(Protocol.frame(Protocol.status())))
        assertEquals("02 53 01 00 00 00 00 00 00 00 52 03", Protocol.hex(Protocol.frame(Protocol.start())))
        assertEquals("02 53 03 50 03", Protocol.hex(Protocol.frame(Protocol.stop())))
        assertEquals("02 53 02 1e 4f 03", Protocol.hex(Protocol.frame(Protocol.speed(30))))
        assertEquals("02 53 02 1e 04 4b 03", Protocol.hex(Protocol.frame(Protocol.speedIncline(30, 4))))
    }

    @Test fun unframeRejectsBadChecksum() {
        assertNull(Protocol.unframe(bytes("02 53 03 51 03")))
        assertNull(Protocol.unframe(bytes("02 53 03 50")))
        assertArrayEquals(intArrayOf(0x53, 0x03), Protocol.unframe(bytes("02 53 03 50 03")))
    }

    @Test fun parsesRunningStatus() {
        // speed 8.5, incline 2, 600 s, 1234 m, 45.6 kcal, 900 steps, 130 bpm
        val p = intArrayOf(0x51, 3, 85, 2, 0x58, 0x02, 0xd2, 0x04, 0xc8, 0x01, 0x84, 0x03, 130, 0)
        val m = Protocol.parse(Protocol.unframe(Protocol.frame(p))!!) as Msg.Status
        assertEquals(Protocol.Live(85, 2, 600, 1234, 456, 900, 130), m.live)
    }

    @Test fun parsesCountdownErrorAndInfo() {
        assertEquals(3, (Protocol.parse(intArrayOf(0x51, 2, 3)) as Msg.Status).countdown)
        assertEquals(9, (Protocol.parse(intArrayOf(0x51, 5, 9)) as Msg.Status).error)
        assertEquals(Msg.SpeedRange(120, 10, 0), Protocol.parse(intArrayOf(0x50, 2, 120, 10, 0)))
        assertEquals(Msg.InclineRange(12, 0), Protocol.parse(intArrayOf(0x50, 3, 12, 0)))
        assertEquals(Msg.InclineRange(0, 0), Protocol.parse(intArrayOf(0x50, 3)))
        assertEquals(Msg.Total(300), Protocol.parse(intArrayOf(0x50, 4, 0x2c, 0x01, 0, 0)))
        val model = Protocol.parse(intArrayOf(0x50, 0, 0x1a, 0x2d, 0x01, 0x39, 0x30, 0, 0)) as Msg.Model
        assertEquals("1a-301", model.name)
        assertEquals(12345L, model.serial)
    }

    @Test fun parsesSummary() {
        assertEquals(Msg.Summary(21, 32, 48, 46), Protocol.parse(Protocol.unframe(bytes("02 52 00 15 00 20 00 30 00 2e 00 79 03"))!!))
    }

    @Test fun parsesHeartRate() {
        assertEquals(72, HeartRateLink.parse(byteArrayOf(0x00, 72)))
        assertEquals(300, HeartRateLink.parse(byteArrayOf(0x01, 0x2c, 0x01)))
    }
}
