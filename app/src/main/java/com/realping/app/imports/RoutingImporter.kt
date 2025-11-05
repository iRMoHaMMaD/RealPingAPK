package com.realping.app.imports

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import com.realping.app.crypto.AesGcm

object RoutingImporter {
    /** راه اول: فایل .conf معمولی */
    fun importPlainConf(context: Context, uri: Uri): String {
        return context.contentResolver.openInputStream(uri)!!.use { it.readBytes().decodeToString() }
    }

    /** راه دوم: فایل .realping رمزگذاری‌شده (Base64 داخلش) */
    fun importEncryptedFile(context: Context, uri: Uri): String {
        val data = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
        // decryptAutoToUtf8 خودش تشخیص Base64/خام را انجام می‌دهد
        return AesGcm.decryptAutoToUtf8(data)
    }

    /**
     * راه سوم: از کلیپ‌بورد.
     * فرمت ویژه:  n : <نام> : <Base64-Encrypted>
     * هرچیزی بعد از قسمت دوم (با «:» جدا شده) یکی می‌شود و به‌صورت Base64 AES-GCM رمزگشایی می‌شود.
     * خروجی: Pair(name, confText)
     */
    fun importFromClipboard(context: Context): Pair<String, String> {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val item: ClipData.Item = cm.primaryClip?.getItemAt(0) ?: error("کلیپ‌بورد خالی است")
        val raw = item.text?.toString()?.trim() ?: error("متن معتبر در کلیپ‌بورد یافت نشد")

        // تلاش برای الگوی n : <name> : <payload>
        val parts = raw.split(":").map { it.trim() }
        if (parts.size >= 3) {
            // بخش دوم اسم است، مابقی را به payload تبدیل می‌کنیم (ممکن است خود payload شامل «:» باشد)
            val name = parts[1]
            val payload = parts.subList(2, parts.size).joinToString(":")
            val conf = AesGcm.decryptBase64ToUtf8(payload)
            return name to conf
        }

        // اگر الگوی سفارشی نبود، فرض می‌کنیم کل متن Base64 است
        val conf = AesGcm.decryptBase64ToUtf8(raw)
        return "روتینگ" to conf
    }
}
