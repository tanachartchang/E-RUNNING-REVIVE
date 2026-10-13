package com.bkpsoft.erunning.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class Settings(
    val miles: Boolean = false,
    val weightKg: Int = 65,
    val speedStep: Int = 5,          // 0.1 units
    val keepScreenOn: Boolean = true,
    val customService: String = "",
    val lastDevice: String = "",     // MAC address for quick reconnect
)

data class Workout(
    val date: Long,
    val time: Int,          // s
    val distance: Int,      // m
    val calories: Double,   // kcal
    val steps: Int,
    val peakSpeed: Double,
    val avgHr: Int,
    val speedUnit: String,
)

/** Everything stays on the phone: no account, no server. */
class Store(context: Context) {
    private val prefs = context.getSharedPreferences("erun", Context.MODE_PRIVATE)

    fun loadSettings() = Settings(
        miles = prefs.getBoolean("miles", false),
        weightKg = prefs.getInt("weight", 65),
        speedStep = prefs.getInt("step", 5),
        keepScreenOn = prefs.getBoolean("awake", true),
        customService = prefs.getString("service", "") ?: "",
        lastDevice = prefs.getString("lastDevice", "") ?: "",
    )

    fun saveSettings(s: Settings) {
        prefs.edit()
            .putBoolean("miles", s.miles).putInt("weight", s.weightKg).putInt("step", s.speedStep)
            .putBoolean("awake", s.keepScreenOn).putString("service", s.customService).putString("lastDevice", s.lastDevice)
            .apply()
    }

    fun loadHistory(): List<Workout> = runCatching {
        val a = JSONArray(prefs.getString("history", "[]"))
        List(a.length()) { i ->
            val o = a.getJSONObject(i)
            Workout(
                o.getLong("date"), o.getInt("time"), o.getInt("distance"), o.getDouble("calories"),
                o.getInt("steps"), o.optDouble("peakSpeed", 0.0), o.optInt("avgHr", 0), o.optString("speedUnit", "km/h"),
            )
        }
    }.getOrDefault(emptyList())

    fun saveHistory(list: List<Workout>) {
        val a = JSONArray()
        list.forEach { w ->
            a.put(
                JSONObject().put("date", w.date).put("time", w.time).put("distance", w.distance)
                    .put("calories", w.calories).put("steps", w.steps).put("peakSpeed", w.peakSpeed)
                    .put("avgHr", w.avgHr).put("speedUnit", w.speedUnit),
            )
        }
        prefs.edit().putString("history", a.toString()).apply()
    }

    companion object {
        fun toCsv(list: List<Workout>): String = buildString {
            appendLine("date,duration_s,distance_m,calories_kcal,steps,peak_speed,speed_unit,avg_hr")
            val fmt = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
            list.forEach { w ->
                appendLine("${fmt.format(java.util.Date(w.date))},${w.time},${w.distance},${"%.1f".format(java.util.Locale.US, w.calories)},${w.steps},${w.peakSpeed},${w.speedUnit},${w.avgHr}")
            }
        }
    }
}
