package com.tvphoto.data.fn

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

/**
 * In-memory cookie store.
 *
 * OkHttp's default is [CookieJar.NO_COOKIES], which is why the whole access-code
 * flow needs this to exist at all: `GET /access_code_verify` authorises the device
 * by setting a cookie, and that cookie is what lets the later WebSocket upgrade —
 * and every photo request — through the gateway's challenge.
 *
 * Deliberately not cleared on sign-out: the cookie authorises the *device*, not the
 * user, so keeping it means signing back in needs only the account password.
 */
class FnCookieJar : CookieJar {

    private val store = mutableMapOf<String, MutableList<Cookie>>()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (cookies.isEmpty()) return
        synchronized(store) {
            val bucket = store.getOrPut(url.host) { mutableListOf() }
            for (cookie in cookies) {
                bucket.removeAll { it.name == cookie.name }
                bucket.add(cookie)
            }
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> = synchronized(store) {
        store[url.host].orEmpty().filter { it.matches(url) }
    }

    fun clear() = synchronized(store) { store.clear() }

    val isEmpty: Boolean get() = synchronized(store) { store.values.all { it.isEmpty() } }
}
