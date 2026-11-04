package app.orbit.tv.settings

import android.content.Context

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("orbit", Context.MODE_PRIVATE)

    var versionEtag: String?
        get() = sp.getString("version_etag", null)
        set(value) = sp.edit().putString("version_etag", value).apply()

    var installedVersion: String?
        get() = sp.getString("catalog_version", null)
        set(value) = sp.edit().putString("catalog_version", value).apply()

    var soundPack: String
        get() = sp.getString("sound_pack", "soft") ?: "soft"
        set(value) = sp.edit().putString("sound_pack", value).apply()
}
