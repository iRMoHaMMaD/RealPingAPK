package com.realping.app.ui.util

fun humanBytes(b: Long): String {
    val KB = 1024.0
    val MB = KB * 1024
    val GB = MB * 1024
    return when {
        b >= GB -> String.format("%.2f GB", b / GB)
        b >= MB -> String.format("%.2f MB", b / MB)
        b >= KB -> String.format("%.2f KB", b / KB)
        else -> "$b B"
    }
}

fun formatElapsed(ms: Long): String {
    val s = (ms / 1000).toInt()
    val hh = s / 3600
    val mm = (s % 3600) / 60
    val ss = s % 60
    return if (hh > 0) String.format("%02d:%02d:%02d", hh, mm, ss)
    else String.format("%02d:%02d", mm, ss)
}
