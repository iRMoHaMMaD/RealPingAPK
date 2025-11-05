package com.realping.app.config

object ConfigPatcher {
    /** جایگزینی MTU فقط روی متن کانفیگِ لحظهٔ اتصال (بدون دست‌کاری فایل اصلی) */
    fun overrideMtu(conf: String, mtu: Int): String {
        val re = Regex("""(?im)^(\s*MTU\s*=\s*)(\d+)""")
        return if (re.containsMatchIn(conf)) {
            conf.replace(re) { it.groupValues[1] + mtu }
        } else {
            val ifaceRe = Regex("""(?im)^(\s*\[Interface]\s*\n)""")
            if (ifaceRe.containsMatchIn(conf))
                conf.replace(ifaceRe) { it.value + "MTU = $mtu\n" }
            else conf + "\n[Interface]\nMTU = $mtu\n"
        }
    }
}
