package com.radiosuite.pro.stream

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.radiosuite.pro.model.BroadcastProfile
import com.radiosuite.pro.model.CodecType
import org.json.JSONArray
import org.json.JSONObject

class BroadcastProfileStore(context: Context) {
    private val appContext = context.applicationContext
    @Volatile private var encryptedPreferences: SharedPreferences? = null

    fun loadProfiles(): List<BroadcastProfile> {
        val preferences = preferences() ?: return emptyList()
        return try {
            val profiles = JSONArray(preferences.getString(PROFILES_KEY, "[]") ?: "[]")
            (0 until profiles.length()).mapNotNull { index ->
                val item = profiles.optJSONObject(index) ?: return@mapNotNull null
                val codec = runCatching { CodecType.valueOf(item.optString("codec", CodecType.Opus.name)) }
                    .getOrDefault(CodecType.Opus)
                BroadcastProfile(
                    name = item.optString("name"),
                    host = item.optString("host"),
                    port = item.optInt("port", 8000),
                    mount = item.optString("mount", "/live"),
                    password = item.optString("password"),
                    codec = codec,
                    bitrate = item.optInt("bitrate", 128),
                    sampleRate = item.optInt("sampleRate", 48000),
                    channels = item.optInt("channels", 2),
                ).takeIf { it.name.isNotBlank() && it.host.isNotBlank() }
            }
        } catch (error: Exception) {
            Log.e(TAG, "No se pudieron descifrar los perfiles Icecast", error)
            emptyList()
        }
    }

    fun lastUsedProfileName(): String? = runCatching {
        preferences()?.getString(LAST_PROFILE_KEY, null)
    }.onFailure { Log.e(TAG, "No se pudo leer el último perfil Icecast", it) }.getOrNull()

    fun setLastUsedProfile(name: String) {
        preferences()?.edit()?.putString(LAST_PROFILE_KEY, name)?.apply()
    }

    fun saveProfile(profile: BroadcastProfile): Boolean {
        val preferences = preferences() ?: return false
        val updated = loadProfiles()
            .filterNot { it.name.equals(profile.name, ignoreCase = true) } + profile
        return runCatching {
            preferences.edit().putString(PROFILES_KEY, encode(updated).toString()).apply()
            true
        }.onFailure { Log.e(TAG, "No se pudo guardar el perfil Icecast", it) }.getOrDefault(false)
    }

    fun deleteProfile(name: String): Boolean {
        val preferences = preferences() ?: return false
        val updated = loadProfiles().filterNot { it.name == name }
        return runCatching {
            preferences.edit()
                .putString(PROFILES_KEY, encode(updated).toString())
                .apply()
            if (preferences.getString(LAST_PROFILE_KEY, null) == name) {
                preferences.edit().remove(LAST_PROFILE_KEY).apply()
            }
            true
        }.onFailure { Log.e(TAG, "No se pudo borrar el perfil Icecast", it) }.getOrDefault(false)
    }

    private fun preferences(): SharedPreferences? {
        encryptedPreferences?.let { return it }
        return synchronized(this) {
            encryptedPreferences ?: runCatching {
                val masterKey = MasterKey.Builder(appContext)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                EncryptedSharedPreferences.create(
                    appContext,
                    PREFERENCES_FILE,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
                )
            }.onFailure { Log.e(TAG, "No se pudo abrir el almacén cifrado", it) }
                .getOrNull()
                .also { encryptedPreferences = it }
        }
    }

    private fun encode(profiles: List<BroadcastProfile>) = JSONArray().apply {
        profiles.forEach { profile ->
            put(
                JSONObject()
                    .put("name", profile.name)
                    .put("host", profile.host)
                    .put("port", profile.port)
                    .put("mount", profile.mount)
                    .put("password", profile.password)
                    .put("codec", profile.codec.name)
                    .put("bitrate", profile.bitrate)
                    .put("sampleRate", profile.sampleRate)
                    .put("channels", profile.channels),
            )
        }
    }

    private companion object {
        const val TAG = "BroadcastProfileStore"
        const val PREFERENCES_FILE = "broadcast_profiles_encrypted"
        const val PROFILES_KEY = "profiles"
        const val LAST_PROFILE_KEY = "last_profile"
    }
}