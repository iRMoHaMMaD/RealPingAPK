package com.realping.app.util

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow

fun formatElapsed(ms: Long): String {
    val s = ms / 1000
    val h = s / 3600
    val m = (s % 3600) / 60
    val ss = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, ss) else "%02d:%02d".format(m, ss)
}

fun humanBytes(bytes: Long): String {
    val units = arrayOf("B","KB","MB","GB","TB")
    if (bytes < 1024) return "$bytes B"
    val exp = (log10(bytes.toDouble()) / log10(1024.0)).toInt().coerceAtMost(units.lastIndex)
    val value = bytes / 1024.0.pow(exp.toDouble())
    return "%.1f %s".format(value, units[exp])
}
