package com.bkpsoft.erunning.ble

import java.util.Calendar

/**
 * E-RUNNING treadmill BLE protocol, reverse engineered from E-RUNNING 2.0.8 (com.zhongyang.ttpaobu).
 * See PROTOCOL.md in the repository root.
 *
 * Frame: 0x02 | payload... | XOR(payload) | 0x03
 */
object Protocol {
    const val INFO = 0x50
    const val STATUS = 0x51
    const val DATA = 0x52
    const val CONTROL = 0x53

    object State {
        const val NORMAL = 0
        const val END = 1
        const val START = 2
        const val RUNNING = 3
        const val STOPPING = 4
        const val ERROR = 5
        const val SAFETY = 6
        const val STUDY = 7
    }

    val stateName = mapOf(
        0 to "Ready", 1 to "Finished", 2 to "Starting", 3 to "Running", 4 to "Stopping",
        5 to "Error", 6 to "Safety key off", 7 to "Incline calibration",
    )

    val errors = mapOf(
        0 to "Safety switch is off",
        1 to "System communication error",
        2 to "Lower controller stall protection",
        3 to "Speed sensor failure",
        4 to "Incline calibration failed",
        5 to "Motor overload protection",
        6 to "Motor open-circuit protection",
        7 to "Motor over-voltage protection",
        8 to "Lower controller hardware fault",
        9 to "Treadmill is not powered on",
        10 to "Motor short-circuit protection",
        11 to "External AC over-voltage",
        12 to "Lower controller system fault",
        13 to "System communication error",
    )

    fun frame(payload: IntArray): ByteArray {
        val out = ByteArray(payload.size + 3)
        var x = 0
        out[0] = 0x02
        payload.forEachIndexed { i, b -> out[i + 1] = b.toByte(); x = x xor (b and 0xff) }
        out[payload.size + 1] = x.toByte()
        out[payload.size + 2] = 0x03
        return out
    }

    /** Payload as unsigned ints, or null if the frame is invalid. */
    fun unframe(bytes: ByteArray): IntArray? {
        val n = bytes.size
        if (n < 4 || bytes[0].toInt() != 0x02 || bytes[n - 1].toInt() != 0x03) return null
        var x = 0
        for (i in 1 until n - 2) x = x xor (bytes[i].toInt() and 0xff)
        if (x != (bytes[n - 2].toInt() and 0xff)) return null
        return IntArray(n - 3) { bytes[it + 1].toInt() and 0xff }
    }

    // ---- commands ----
    fun status() = intArrayOf(STATUS)
    fun start() = intArrayOf(CONTROL, 0x01, 0, 0, 0, 0, 0, 0, 0)
    fun stop() = intArrayOf(CONTROL, 0x03)
    /** [raw] speed in 0.1 units (65 = 6.5 km/h). */
    fun speed(raw: Int) = intArrayOf(CONTROL, 0x02, raw)
    fun speedIncline(raw: Int, incline: Int) = intArrayOf(CONTROL, 0x02, raw, incline)
    fun user(uid: Long, weightKg: Int) = intArrayOf(
        CONTROL, 0x00, (uid and 0xff).toInt(), ((uid shr 8) and 0xff).toInt(),
        ((uid shr 16) and 0xff).toInt(), ((uid shr 24) and 0xff).toInt(), weightKg,
    )
    fun infoModel(c: Calendar = Calendar.getInstance()) = intArrayOf(
        INFO, 0x00, c.get(Calendar.YEAR) - 2000, c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH),
        c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), c.get(Calendar.SECOND),
    )
    fun infoSpeed() = intArrayOf(INFO, 0x02)
    fun infoIncline() = intArrayOf(INFO, 0x03)
    fun infoTotal() = intArrayOf(INFO, 0x04)
    fun sportSummary() = intArrayOf(DATA, 0x00)

    // ---- parsing ----
    data class Live(
        val speed: Int,      // 0.1 km/h (mph if treadmill unit is imperial)
        val incline: Int,
        val time: Int,       // s
        val distance: Int,   // m
        val calories: Int,   // 0.1 kcal
        val steps: Int,
        val heartRate: Int,
    )

    sealed interface Msg {
        data class Status(val state: Int, val countdown: Int = 0, val error: Int = 0, val live: Live? = null) : Msg
        data class Model(val model: Int?, val name: String?, val serial: Long?) : Msg
        data class SpeedRange(val max: Int, val min: Int, val unit: Int) : Msg
        data class InclineRange(val max: Int, val min: Int) : Msg
        data class Total(val total: Long?) : Msg
        data class Summary(val time: Int, val distance: Int, val calories: Int, val steps: Int) : Msg
        data class Other(val payload: IntArray) : Msg
    }

    private fun u16(p: IntArray, i: Int) = p[i] or (p[i + 1] shl 8)
    private fun u32(p: IntArray, i: Int) = (p[i].toLong() or (p[i + 1].toLong() shl 8) or (p[i + 2].toLong() shl 16) or (p[i + 3].toLong() shl 24))

    fun parse(p: IntArray): Msg {
        when (p[0]) {
            STATUS -> {
                val s = p.getOrElse(1) { 0 }
                val live = if ((s == State.END || s == State.RUNNING || s == State.STOPPING) && p.size >= 13)
                    Live(p[2], p[3], u16(p, 4), u16(p, 6), u16(p, 8), u16(p, 10), p[12]) else null
                return Msg.Status(
                    state = s,
                    countdown = if (s == State.START) p.getOrElse(2) { 0 } else 0,
                    error = if (s == State.ERROR) p.getOrElse(2) { 0 } else 0,
                    live = live,
                )
            }
            INFO -> when (p.getOrElse(1) { -1 }) {
                0x00 -> return if (p.size == 9 || p.size == 5) {
                    val bc = p[3] or (p[4] shl 8)
                    Msg.Model((p[2] shl 16) + bc, p[2].toString(16) + "-" + bc, if (p.size == 9) u32(p, 5) else null)
                } else Msg.Model(null, null, null)
                0x02 -> if (p.size >= 4) return Msg.SpeedRange(p[2], p[3], p.getOrElse(4) { 0 })
                0x03 -> return if (p.size == 4) Msg.InclineRange(p[2], p[3]) else Msg.InclineRange(0, 0)
                0x04 -> return Msg.Total(if (p.size == 6) u32(p, 2) else null)
            }
            DATA -> if (p.getOrElse(1) { -1 } == 0 && p.size >= 10)
                return Msg.Summary(u16(p, 2), u16(p, 4), u16(p, 6), u16(p, 8))
        }
        return Msg.Other(p)
    }

    fun hex(b: ByteArray) = b.joinToString(" ") { "%02x".format(it.toInt() and 0xff) }
}
