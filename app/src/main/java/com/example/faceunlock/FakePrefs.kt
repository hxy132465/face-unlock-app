package com.example.faceunlock

import android.content.Context

/**
 * SharedPreferences-backed settings for the fake-desktop flow:
 *   - the real (system) home package captured before this app pins itself
 *   - the user-defined escape password (plain-ish SHA-256, app-private)
 */
object FakePrefs {

    private const val PREFS = "fake_desktop"
    private const val KEY_HOME_PKG = "real_home_pkg"
    private const val KEY_PASSWORD_HASH = "escape_pwd_hash"

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun setHomePackage(c: Context, pkg: String) =
        prefs(c).edit().putString(KEY_HOME_PKG, pkg).apply()

    fun homePackage(c: Context): String? = prefs(c).getString(KEY_HOME_PKG, null)

    fun isPasswordSet(c: Context): Boolean = prefs(c).contains(KEY_PASSWORD_HASH)

    fun setPassword(c: Context, plain: String) =
        prefs(c).edit().putString(KEY_PASSWORD_HASH, sha256(plain)).apply()

    fun matchesPassword(c: Context, plain: String): Boolean =
        prefs(c).getString(KEY_PASSWORD_HASH, null) == sha256(plain)

    private fun sha256(input: String): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
