package com.bkpsoft.erunning.ble

import android.os.Handler
import android.os.Looper
import com.bkpsoft.erunning.ble.Protocol.State
import kotlin.math.floor
import kotlin.math.roundToInt

/** A fake treadmill speaking the same protocol, for trying the app without hardware. */
class SimulatorLink(private val listener: LinkListener) : Link {
    override val name = "Demo treadmill"
    override val busy = false
    private val main = Handler(Looper.getMainLooper())
    private var state = State.NORMAL
    private val minSpeed = 10
    private val maxSpeed = 120
    private val maxIncline = 12
    private var speed = 0; private var target = 0; private var incline = 0; private var countdown = 0
    private var time = 0; private var dist = 0.0; private var cal = 0.0; private var steps = 0.0; private var hr = 0
    private var running = true

    private val clock = object : Runnable {
        override fun run() { if (running) { second(); main.postDelayed(this, 1000) } }
    }

    fun start() {
        main.postDelayed(clock, 1000)
        main.post { listener.onLinkReady(this) }
    }

    override fun close() { running = false; main.removeCallbacksAndMessages(null) }

    private fun reply(p: IntArray) = main.postDelayed({ if (running) listener.onLinkBytes(Protocol.frame(p)) }, 20)
    private fun le16(v: Int) = intArrayOf(v and 0xff, (v shr 8) and 0xff)

    override fun write(bytes: ByteArray) {
        val p = Protocol.unframe(bytes) ?: return
        when (p[0]) {
            Protocol.STATUS -> reply(status())
            Protocol.INFO -> when (p[1]) {
                0 -> reply(intArrayOf(Protocol.INFO, 0, 0x1a) + le16(301) + intArrayOf(0x39, 0x30, 0, 0))
                2 -> reply(intArrayOf(Protocol.INFO, 2, maxSpeed, minSpeed, 0))
                3 -> reply(intArrayOf(Protocol.INFO, 3, maxIncline, 0))
                4 -> reply(intArrayOf(Protocol.INFO, 4, 0x2c, 0x01, 0, 0))
            }
            Protocol.DATA -> reply(intArrayOf(Protocol.DATA, 0) + le16(time) + le16(dist.toInt()) + le16(cal.toInt()) + le16(steps.toInt()))
            Protocol.CONTROL -> {
                when (p[1]) {
                    1 -> if (state == State.NORMAL) {
                        speed = 0; target = 0; incline = 0; time = 0; dist = 0.0; cal = 0.0; steps = 0.0
                        state = State.START; countdown = 3
                    }
                    3 -> if (state == State.RUNNING || state == State.START) { state = State.STOPPING; target = 0 }
                    2 -> if (state == State.RUNNING) {
                        target = p[2].coerceIn(minSpeed, maxSpeed)
                        if (p.size > 3) incline = p[3].coerceAtMost(maxIncline)
                    }
                }
                reply(intArrayOf(Protocol.CONTROL, p[1]))
            }
        }
    }

    private fun status(): IntArray = when (state) {
        State.START -> intArrayOf(Protocol.STATUS, state, countdown)
        State.RUNNING, State.STOPPING, State.END -> intArrayOf(Protocol.STATUS, state, speed, incline) +
            le16(time) + le16(floor(dist).toInt()) + le16(floor(cal).toInt()) + le16(floor(steps).toInt()) + intArrayOf(hr, 0)
        else -> intArrayOf(Protocol.STATUS, state)
    }

    private fun second() {
        when (state) {
            State.START -> { if (--countdown <= 0) { state = State.RUNNING; speed = minSpeed; target = minSpeed }; return }
            State.END -> { state = State.NORMAL; return }
            State.RUNNING, State.STOPPING -> Unit
            else -> return
        }
        if (speed < target) speed = minOf(target, speed + 5)
        if (speed > target) speed = maxOf(target, speed - if (state == State.STOPPING) 30 else 5)
        if (state == State.STOPPING && speed == 0) { state = State.END; return }
        val kmh = speed / 10.0
        time++
        dist += kmh / 3.6
        cal += kmh * (1 + incline / 10.0) * 0.3
        steps += if (kmh < 6) 1.8 else 2.7
        hr = (80 + kmh * 7 + incline * 2).roundToInt()
    }
}
