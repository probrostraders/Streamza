package com.streamza.loop.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

private val Context.cookieStore by preferencesDataStore(name = "session_cookies")
private val COOKIE_KEY = stringPreferencesKey("sz_session_cookie")

/**
 * Persists the `sz_session` cookie server.js sets on /auth/google (see sessionCookie() in server.js)
 * across app restarts. OkHttp's CookieJar contract is synchronous, so the cookie lives in-memory here
 * and is mirrored to DataStore on every change; build this via [SessionCookieJar.create] so the jar
 * already has any previously-persisted cookie loaded before the first request goes out.
 */
class SessionCookieJar private constructor(
    private val context: Context,
    private val host: String,
    initial: Cookie?,
) : CookieJar {
    private val scope = CoroutineScope(Dispatchers.IO)
    @Volatile private var cached: Cookie? = initial

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val session = cookies.firstOrNull { it.name == "sz_session" } ?: return
        cached = session
        scope.launch { context.cookieStore.edit { it[COOKIE_KEY] = session.value } }
    }

    // Must check the host: requests also go straight to R2's *.r2.cloudflarestorage.com for the actual
    // upload bytes (see StreamzaApi.r2PutPart), and the session cookie must never be sent there.
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val c = cached ?: return emptyList()
        return if (url.host == host) listOf(c) else emptyList()
    }

    val isSignedIn: Boolean get() = cached != null

    fun clear() {
        cached = null
        scope.launch { context.cookieStore.edit { it.remove(COOKIE_KEY) } }
    }

    companion object {
        suspend fun create(context: Context, host: String): SessionCookieJar {
            val stored = context.cookieStore.data.first()[COOKIE_KEY]
            val initial = stored?.let {
                Cookie.Builder().name("sz_session").value(it).domain(host).path("/").httpOnly().secure().build()
            }
            return SessionCookieJar(context, host, initial)
        }
    }
}
