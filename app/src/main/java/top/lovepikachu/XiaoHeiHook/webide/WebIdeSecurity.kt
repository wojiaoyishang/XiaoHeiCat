package top.lovepikachu.XiaoHeiHook.webide

import android.content.Context
import java.security.SecureRandom

object WebIdeSecurity {
    private const val PREFS = "xhh_webide_security"
    private const val KEY_TOKEN = "token"
    private val random = SecureRandom()

    fun token(context: Context): String {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val existing = prefs.getString(KEY_TOKEN, null)
        if (!existing.isNullOrBlank()) return existing
        val bytes = ByteArray(24)
        random.nextBytes(bytes)
        val generated = bytes.joinToString("") { "%02x".format(it) }
        prefs.edit().putString(KEY_TOKEN, generated).commit()
        return generated
    }

    fun isValid(context: Context, value: String?): Boolean {
        val expected = token(context)
        return !value.isNullOrBlank() && constantTimeEquals(expected, value.trim())
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        val aa = a.toByteArray(Charsets.UTF_8)
        val bb = b.toByteArray(Charsets.UTF_8)
        var diff = aa.size xor bb.size
        val max = maxOf(aa.size, bb.size)
        for (i in 0 until max) {
            val x = if (i < aa.size) aa[i].toInt() else 0
            val y = if (i < bb.size) bb[i].toInt() else 0
            diff = diff or (x xor y)
        }
        return diff == 0
    }
}
