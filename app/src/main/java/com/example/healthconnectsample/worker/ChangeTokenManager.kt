package com.example.healthconnectsample.worker

import android.content.Context
import android.content.SharedPreferences

/**
 * Administra tokens para differential changes de Health Connect
 */
class ChangeTokenManager(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(
        "health_connect_tokens",
        Context.MODE_PRIVATE
    )

    companion object {
        private const val KEY_CHANGES_TOKEN = "changes_token"
        private const val KEY_LAST_EXPORT_TIME = "last_export_time"
    }

    /**
     * Guarda el token de cambios
     */
    fun saveChangesToken(token: String) {
        prefs.edit()
            .putString(KEY_CHANGES_TOKEN, token)
            .putLong(KEY_LAST_EXPORT_TIME, System.currentTimeMillis())
            .apply()
    }

    /**
     * Obtiene el token guardado, o null si es primera vez
     */
    fun getChangesToken(): String? {
        return prefs.getString(KEY_CHANGES_TOKEN, null)
    }

    /**
     * Verifica si es la primera ejecución (no hay token guardado)
     */
    fun isFirstExport(): Boolean {
        return getChangesToken() == null
    }

    /**
     * Obtiene timestamp del último export
     */
    fun getLastExportTime(): Long {
        return prefs.getLong(KEY_LAST_EXPORT_TIME, 0L)
    }

    /**
     * Limpia el token (útil para reset o debugging)
     */
    fun clearToken() {
        prefs.edit()
            .remove(KEY_CHANGES_TOKEN)
            .remove(KEY_LAST_EXPORT_TIME)
            .apply()
    }
}