package com.realping.app.crypto

import android.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

private val BASE64_CHARS = ("ABCDEFGHIJKLMNOPQRSTUVWXYZ" +
        "abcdefghijklmnopqrstuvwxyz" +
        "0123456789+/").toSet()

/** فقط کاراکترهای معتبر Base64 و '=' و فاصله‌های سفید رو نگه می‌داره */
private fun sanitizeBase64(input: String): String =
    buildString(input.length) {
        for (ch in input) {
            if (ch in BASE64_CHARS || ch == '=' || ch.isWhitespace()) append(ch)
        }
    }

/** اگر رشته تقریباً فقط کاراکترهای Base64 داشت true می‌شه */
private fun looksLikeBase64(input: String): Boolean {
    if (input.isEmpty()) return false
    val ok = input.count { it in BASE64_CHARS || it == '=' || it.isWhitespace() }
    return ok >= input.length * 0.9
}

object AesGcm {
    // کلید ثابت (نمونه) — AES-256 Base64
    private val KEY_BYTES: ByteArray by lazy {
        Base64.decode(
            "kBP7z0eoHL85fdKzEbGtTiT40Y4qV18/mU+KZvEanJk=",
            Base64.DEFAULT
        )
    }

    fun decryptBytes(ivPlusCiphertext: ByteArray, aad: ByteArray? = null): ByteArray {
        require(ivPlusCiphertext.size > 12) { "Invalid payload" }
        val iv = ivPlusCiphertext.copyOfRange(0, 12)
        val ciphertext = ivPlusCiphertext.copyOfRange(12, ivPlusCiphertext.size)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(KEY_BYTES, "AES"),
            GCMParameterSpec(128, iv)
        )
        if (aad != null) cipher.updateAAD(aad)
        return cipher.doFinal(ciphertext)
    }

    fun decryptBase64ToUtf8(b64: String): String {
        val cleaned = sanitizeBase64(b64.trim())
        require(looksLikeBase64(cleaned)) { "Input is not valid Base64" }
        val data = Base64.decode(cleaned, Base64.DEFAULT)
        return decryptBytes(data).decodeToString()
    }

    /** اگر ورودی بایت خام بود مستقیماً دیکریپت می‌کنه؛ اگر شبیه Base64 بود اول decode */
    fun decryptAutoToUtf8(input: ByteArray): String {
        val asText = input.toString(Charsets.US_ASCII).trim()
        return try {
            if (looksLikeBase64(asText)) decryptBase64ToUtf8(asText)
            else decryptBytes(input).decodeToString()
        } catch (_: Throwable) {
            decryptBytes(input).decodeToString()
        }
    }
}
