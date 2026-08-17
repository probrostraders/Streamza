package com.streamza.loop.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.streamKeyStore by preferencesDataStore(name = "stream_keys")

/** Remembers the last stream key (and, for Custom RTMP, the URL) typed per platform, so going live
 *  again doesn't mean retyping it every time — matches how every other streaming app handles this.
 *  Local-only, keyed by platform name so switching platforms never leaks one platform's key into
 *  another's field. */
class StreamKeyStore(private val context: Context) {
    suspend fun savedKey(platformName: String): String =
        context.streamKeyStore.data.first()[stringPreferencesKey("key_$platformName")] ?: ""

    suspend fun savedUrl(platformName: String): String =
        context.streamKeyStore.data.first()[stringPreferencesKey("url_$platformName")] ?: ""

    suspend fun save(platformName: String, key: String, url: String? = null) {
        context.streamKeyStore.edit {
            it[stringPreferencesKey("key_$platformName")] = key
            if (url != null) it[stringPreferencesKey("url_$platformName")] = url
        }
    }
}
