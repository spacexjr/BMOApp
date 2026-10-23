package com.spacexjr.bmo.data

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DiagnosticsManager {
    private val _entries = MutableStateFlow<List<String>>(emptyList())
    val entries: StateFlow<List<String>> = _entries.asStateFlow()

    fun info(source: String, message: String) {
        add("I", source, message)
    }

    fun warn(source: String, message: String) {
        add("W", source, message)
    }

    fun error(source: String, message: String, throwable: Throwable? = null) {
        add("E", source, message + (throwable?.message?.let { ": $it" } ?: ""))
        Log.e("BMO/$source", message, throwable)
    }

    private fun add(level: String, source: String, message: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        val line = "$time $level/$source: $message"
        _entries.value = (_entries.value + line).takeLast(200)
        Log.println(if (level == "E") Log.ERROR else Log.INFO, "BMO/$source", message)
    }
}
