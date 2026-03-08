package com.adex.app.util

import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom

object PinSecurity {
    private val pinRegex = Regex("^\\d{4,12}$")

    fun isValidPin(pin: String): Boolean = pinRegex.matches(pin)

    fun generateSalt(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    fun hashPin(pin: String, saltBase64: String): String {
        val salt = Base64.decode(saltBase64, Base64.DEFAULT)
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(salt)
        digest.update(pin.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(digest.digest(), Base64.NO_WRAP)
    }

    fun constantTimeEquals(left: String, right: String): Boolean {
        val leftBytes = left.toByteArray(Charsets.UTF_8)
        val rightBytes = right.toByteArray(Charsets.UTF_8)
        return MessageDigest.isEqual(leftBytes, rightBytes)
    }
}
