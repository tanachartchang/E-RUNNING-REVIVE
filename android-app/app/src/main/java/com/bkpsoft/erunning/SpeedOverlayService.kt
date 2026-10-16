package com.bkpsoft.erunning

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.TextUnit
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.bkpsoft.erunning.ble.Protocol.State

/** Floating speed card with [−] [Start/Stop] [+] at the bottom of the screen, shown over other apps (YouTube) while running. */
class SpeedOverlayService : LifecycleService(), SavedStateRegistryOwner {
    private val savedState = SavedStateRegistryController.create(this)
    override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry
    private var view: ComposeView? = null

    override fun onCreate() {
        savedState.performRestore(null)
        super.onCreate()
        val vm = vm ?: return stopSelf()
        val v = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@SpeedOverlayService)
            setViewTreeSavedStateRegistryOwner(this@SpeedOverlayService)
            setContent { SpeedCard(vm) }
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        }
        runCatching { getSystemService(WindowManager::class.java).addView(v, params) }.onSuccess { view = v }.onFailure { stopSelf() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        // Keeps the process (and the treadmill link) alive during a long video. Without Bluetooth permission
        // (demo treadmill) this fails and the overlay still works as a plain service.
        runCatching {
            ServiceCompat.startForeground(this, 1, notification(),
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0)
        }
        return START_NOT_STICKY
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(CHANNEL, "Speed control", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Speed control active")
            .setContentText("Tap to return to E-Running")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        view?.let { runCatching { getSystemService(WindowManager::class.java).removeView(it) } }
        view = null
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "overlay"

        /** Set by MainActivity; the overlay drives the same treadmill connection as the app. */
        var vm: TreadmillViewModel? = null

        fun start(ctx: Context) { ctx.startService(Intent(ctx, SpeedOverlayService::class.java)) }
        fun stop(ctx: Context) { ctx.stopService(Intent(ctx, SpeedOverlayService::class.java)) }
    }
}

private val Green = Color(0xFF35C06F)
private val Red = Color(0xFFE5484D)
private val CardColor = Color(0x33181B21) // 80% transparent; border uses the same color
private val StepColor = Color(0xB3181B21) // − / + buttons: 30% transparent
private const val ButtonAlpha = 0.7f // Start / Stop: 30% transparent

@Composable
private fun SpeedCard(vm: TreadmillViewModel) {
    val st = vm.state
    val running = st == State.RUNNING
    val canStart = st == State.NORMAL || st == State.END
    val canStop = running || st == State.START
    val shape = RoundedCornerShape(28.dp)
    Column(
        Modifier.padding(start = 12.dp, end = 12.dp, bottom = 8.dp)
            .background(CardColor, shape)
            .border(1.dp, CardColor, shape)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            if (running) "SPEED" else if (st == State.START) "STARTING ${vm.countdown}" else "READY",
            color = if (running || st == State.START) Green else Color.White.copy(alpha = 0.55f),
            fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp,
        )
        Row(verticalAlignment = Alignment.Bottom) {
            Text("%.1f".format(vm.shownSpeed() / 10.0), color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.ExtraBold, lineHeight = 36.sp)
            Text(" " + vm.speedUnit(), color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp, modifier = Modifier.padding(bottom = 6.dp))
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OverlayButton("−", StepColor, running, CircleShape, Modifier.size(52.dp), 28.sp) { vm.changeSpeed(-vm.settings.speedStep) }
            if (canStop) OverlayButton("Stop", Red.copy(alpha = ButtonAlpha), true, RoundedCornerShape(26.dp), Modifier.size(width = 112.dp, height = 52.dp), 18.sp) { vm.stop() }
            else OverlayButton("Start", Green.copy(alpha = ButtonAlpha), canStart, RoundedCornerShape(26.dp), Modifier.size(width = 112.dp, height = 52.dp), 18.sp) { vm.start() }
            OverlayButton("+", StepColor, running, CircleShape, Modifier.size(52.dp), 28.sp) { vm.changeSpeed(vm.settings.speedStep) }
        }
    }
}

@Composable
private fun OverlayButton(text: String, color: Color, enabled: Boolean, shape: Shape, modifier: Modifier, fontSize: TextUnit, onClick: () -> Unit) {
    Box(
        modifier.alpha(if (enabled) 1f else 0.35f).clip(shape).background(color).clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = Color.White, fontSize = fontSize, fontWeight = FontWeight.Bold) }
}
