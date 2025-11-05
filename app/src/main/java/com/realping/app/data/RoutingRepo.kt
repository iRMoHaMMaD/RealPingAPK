package com.realping.app.data

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.realping.app.crypto.AesGcm
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.util.UUID
import com.wireguard.config.Config
import java.io.ByteArrayInputStream


class RoutingRepo(private val ctx: Context) {
    private val dir: File by lazy { File(ctx.filesDir, "routings").apply { mkdirs() } }

    private val _entries = MutableStateFlow<List<RoutingEntry>>(emptyList())
    val entries: StateFlow<List<RoutingEntry>> get() = _entries

    init { refresh() }

    private fun refresh() {
        val items = dir.listFiles { f -> f.name.endsWith(".meta") }?.mapNotNull { meta ->
            runCatching {
                val id = meta.nameWithoutExtension
                val name = meta.readText(Charsets.UTF_8).lineSequence().firstOrNull().orEmpty().ifBlank { "روتینگ" }
                val added = meta.lastModified()
                RoutingEntry(id, name, added)
            }.getOrNull()
        }?.sortedByDescending { it.addedAt }.orEmpty()
        _entries.value = items
    }

    private fun newId() = UUID.randomUUID().toString().replace("-", "")

    private fun writeEntry(name: String, confText: String): RoutingEntry {
        val id = newId()
        File(dir, "$id.conf").writeText(confText, Charsets.UTF_8)
        File(dir, "$id.meta").writeText(name.ifBlank { "روتینگ" }, Charsets.UTF_8)
        val entry = RoutingEntry(id, name.ifBlank { "روتینگ" }, System.currentTimeMillis())
        refresh()
        return entry
    }

    // نام واقعی فایل برای content://
    private fun resolveDisplayName(uri: Uri): String? {
        return try {
            if (uri.scheme == "content") {
                ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0) else null
                }
            } else {
                uri.lastPathSegment
            }
        } catch (_: Throwable) { null }
    }

    // حذف پسوندها به‌صورت امن
    private fun fileNameNoExt(name: String?, vararg exts: String): String? {
        val base = name ?: return null
        var n: String = base
        for (ext in exts) {
            if (n.endsWith(ext, ignoreCase = true)) {
                n = n.substring(0, n.length - ext.length)
                break
            }
        }
        return n
    }

    fun addPlainFromUri(nameHint: String?, uri: Uri): RoutingEntry {
        val text = ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes().decodeToString() }
        val disp = resolveDisplayName(uri)
        val name = nameHint?.takeIf { it.isNotBlank() }
            ?: fileNameNoExt(disp ?: uri.lastPathSegment, ".conf", ".txt")
            ?: "روتینگ"
        return writeEntry(name, text)
    }

    fun addEncryptedFromUri(nameHint: String?, uri: Uri): RoutingEntry {
        val data = ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
        val text = AesGcm.decryptAutoToUtf8(data)
        val disp = resolveDisplayName(uri)
        val name = nameHint?.takeIf { it.isNotBlank() }
            ?: fileNameNoExt(disp ?: uri.lastPathSegment, ".realping")
            ?: "روتینگ رمز"
        return writeEntry(name, text)
    }

    fun addAutoFromUri(nameHint: String?, uri: Uri): RoutingEntry {
        val disp = resolveDisplayName(uri)
        val ext = disp?.substringAfterLast('.', "")?.lowercase() ?: ""
        val data = ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
        val asciiHead = data.toString(Charsets.US_ASCII).take(128_000)

        fun validateWireGuardOrThrow(text: String) {
            runCatching { Config.parse(ByteArrayInputStream(text.toByteArray())) }
                .getOrElse { throw IllegalArgumentException("ساختار فایل WireGuard معتبر نیست.") }
        }

        fun isProbablyEncryptedBlob(s: String): Boolean {
            // اگر پسوند .conf است ولی محتوای Base64 مانند (بدون نشانه‌های کانفیگ) دارد، احتمالاً فایل رمز است
            val cleaned = s.trim().replace("\\s+".toRegex(), "")
            val base64CharsOnly = cleaned.all { it.isLetterOrDigit() || it in "+/=" }
            val hasWireGuardMarkers = s.contains("[Interface]") || s.contains("[Peer]")
            return base64CharsOnly && !hasWireGuardMarkers && cleaned.length >= 16
        }

        return when (ext) {
            "conf", "txt" -> {
                val text = data.decodeToString()
                if (isProbablyEncryptedBlob(text)) {
                    throw IllegalArgumentException("این فایل شبیه فایل رمزنگاری‌شده است؛ پسوند .conf صحیح نیست.")
                }
                validateWireGuardOrThrow(text)
                val name = nameHint?.takeIf { it.isNotBlank() }
                    ?: fileNameNoExt(disp ?: uri.lastPathSegment, ".conf", ".txt")
                    ?: "روتینگ"
                writeEntry(name, text)
            }
            "realping" -> {
                val decrypted = runCatching { com.realping.app.crypto.AesGcm.decryptAutoToUtf8(data) }
                    .getOrElse { throw IllegalArgumentException("محتوای فایل .realping رمزنگاری‌شده نیست یا کلید نامعتبر است.") }
                validateWireGuardOrThrow(decrypted)
                val name = nameHint?.takeIf { it.isNotBlank() }
                    ?: fileNameNoExt(disp ?: uri.lastPathSegment, ".realping")
                    ?: "روتینگ رمز"
                writeEntry(name, decrypted)
            }
            else -> {
                // تلاش برای تشخیص محتوا
                val looksConf = asciiHead.contains("[Interface]") || asciiHead.contains("[Peer]")
                if (looksConf) {
                    val text = data.decodeToString()
                    validateWireGuardOrThrow(text)
                    val name = nameHint?.takeIf { it.isNotBlank() }
                        ?: fileNameNoExt(disp ?: uri.lastPathSegment)
                        ?: "روتینگ"
                    return writeEntry(name, text)
                }
                val decrypted = runCatching { com.realping.app.crypto.AesGcm.decryptAutoToUtf8(data) }.getOrNull()
                if (decrypted != null) {
                    validateWireGuardOrThrow(decrypted)
                    val name = nameHint?.takeIf { it.isNotBlank() }
                        ?: fileNameNoExt(disp ?: uri.lastPathSegment)
                        ?: "روتینگ رمز"
                    return writeEntry(name, decrypted)
                }
                throw IllegalArgumentException("نوع فایل پشتیبانی نمی‌شود. فقط .conf یا .realping")
            }
        }
    }

    fun addFromClipboard(): RoutingEntry {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = cm.primaryClip ?: throw IllegalArgumentException("کلیپ‌بورد خالی است.")
        val item: ClipData.Item = clip.getItemAt(0)

        // متن کلیپ‌بورد رو امن استخراج کن
        val text = (item.text?.toString()
            ?: item.coerceToText(ctx)?.toString()
            ?: "").trim()

        // فرمت لازم: n : <name> : <base64>
        // فاصله‌ها آزاد هستند؛ نام حتماً باید غیرخالی باشد.
        val pattern = Regex("""^\s*n\s*:\s*([^:]+?)\s*:\s*([A-Za-z0-9+/=]+)\s*$""")
        val m = pattern.find(text)
            ?: throw IllegalArgumentException(
                "فرمت ورودی نامعتبر است.\nنمونهٔ صحیح:\n" +
                        "n : نام روتینگ : <متنِ رمزنگاری‌شده (Base64)>"
            )

        val name = m.groupValues[1].trim().ifBlank {
            throw IllegalArgumentException("نام روتینگ خالی است.")
        }
        val b64  = m.groupValues[2].trim().ifBlank {
            throw IllegalArgumentException("متن رمزنگاری‌شده خالی است.")
        }

        // تلاش برای رمزگشایی
        val conf = runCatching { AesGcm.decryptBase64ToUtf8(b64) }
            .getOrElse {
                throw IllegalArgumentException("رمزگشایی ناموفق بود. لطفاً متن صحیح Base64 را وارد کنید.")
            }

        return writeEntry(name, conf)
    }


    fun readConfigText(id: String): String {
        return File(dir, "$id.conf").readText(Charsets.UTF_8)
    }

    fun delete(id: String) {
        runCatching { File(dir, "$id.conf").delete() }
        runCatching { File(dir, "$id.meta").delete() }
        refresh()
    }
}
