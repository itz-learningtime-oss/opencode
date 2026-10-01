package ai.opencode.cli.settings

import android.content.Context
import android.content.SharedPreferences

class OpenCodeSettings(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var serverUrl: String
        get() = prefs.getString(KEY_SERVER_URL, "")?.trim().orEmpty()
        set(value) {
            prefs.edit().putString(KEY_SERVER_URL, value.trim()).apply()
        }

    var apiKey: String
        get() = prefs.getString(KEY_API_KEY, "")?.trim().orEmpty()
        set(value) {
            prefs.edit().putString(KEY_API_KEY, value.trim()).apply()
        }

    var extraArgs: String
        get() = prefs.getString(KEY_EXTRA_ARGS, "")?.trim().orEmpty()
        set(value) {
            prefs.edit().putString(KEY_EXTRA_ARGS, value.trim()).apply()
        }

    var maxOldSpaceMb: Int
        get() = prefs.getInt(KEY_MAX_OLD_SPACE, 256)
        set(value) {
            prefs.edit().putInt(KEY_MAX_OLD_SPACE, value.coerceIn(96, 512)).apply()
        }

    fun isConfigured(): Boolean = serverUrl.isNotBlank()

    companion object {
        private const val PREFS = "opencode_settings"
        private const val KEY_SERVER_URL = "opencode_server_url"
        private const val KEY_API_KEY = "opencode_api_key"
        private const val KEY_EXTRA_ARGS = "opencode_extra_args"
        private const val KEY_MAX_OLD_SPACE = "opencode_max_old_space"
    }
}
