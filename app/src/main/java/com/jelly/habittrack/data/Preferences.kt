package com.jelly.habittrack.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import java.time.LocalTime

data class ReminderSettings(val interval: Int = 15, val quietStart: String = "23:00", val quietEnd: String = "07:00", val theme: String = "system")

class Preferences(context: Context) {
    private val prefs = context.getSharedPreferences("preferences", Context.MODE_PRIVATE)
    fun read() = ReminderSettings(prefs.getInt("interval", 15), prefs.getString("quietStart", "23:00")!!, prefs.getString("quietEnd", "07:00")!!, prefs.getString("theme", "system")!!)
    fun write(settings: ReminderSettings) {
        require(settings.interval in listOf(0, 15, 30, 60))
        LocalTime.parse(settings.quietStart); LocalTime.parse(settings.quietEnd)
        require(settings.theme in listOf("system", "light", "dark"))
        check(prefs.edit().putInt("interval", settings.interval).putString("quietStart", settings.quietStart).putString("quietEnd", settings.quietEnd).putString("theme", settings.theme).commit())
    }
    fun widget(id: Int) = prefs.getString("widget_$id", "")!!
    fun watchWidget(id: Int) = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "widget_$id" || key == null) trySend(widget(id))
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        trySend(widget(id))
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.distinctUntilChanged()
    fun widget(id: Int, selection: String) { prefs.edit().putString("widget_$id", selection).apply() }
    fun removeWidget(id: Int) { prefs.edit().remove("widget_$id").apply() }
}
