// app/src/main/java/com/realping/app/vpn/VpnProtectHolder.kt
package com.realping.app.vpn

/**
 * هولدر سراسری برای تابع protect(fd) از VpnService
 */
object VpnProtectHolder {
    @Volatile
    var protectFd: ((Int) -> Boolean)? = null
}
