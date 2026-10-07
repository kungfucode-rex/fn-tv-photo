package com.tvphoto.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * One remembered login: the server address together with the account used on it.
 *
 * Keyed by host **and** user, because one NAS commonly has several accounts with
 * different passwords and access codes. Remembering only the host meant a second
 * account silently overwrote the first, and picking a saved address signed in as
 * whichever account happened to be in the form at the time.
 */
data class SavedAccount(
    val host: String,
    val user: String,
    val password: String,
    val accessCode: String = "",
)

/**
 * How many logins are remembered. Enough for a household's accounts across a couple of
 * NASes, and few enough that the login screen's shortcut list still fits on a TV.
 */
const val MAX_SAVED_ACCOUNTS = 5

/**
 * The form of a typed address that a saved account is keyed by: `host:port`, so
 * `192.168.31.14` and `192.168.31.14:50316` do not become two entries for one NAS.
 *
 * Shared with [SessionRepository] so the login screen looks up the same key that was
 * stored.
 */
fun rememberedHost(typed: String): String = displayHost(normalizeBaseUrl(typed.trim()))

/**
 * Whether [host] was saved by a version that still had FN ID sign-in.
 *
 * Those were bare handles — no dot, colon or slash, the shape FN IDs had to have — and
 * the old code traded them for an address through FN Connect. That route is gone, so
 * such an entry can only ever fail and is dropped rather than offered as a shortcut.
 * `localhost` is excluded: it is a real host that merely looks like a handle.
 */
internal fun isLegacyFnIdHost(host: String): Boolean {
    val value = host.trim()
    if (value.isEmpty()) return false
    if (value.any { it == '.' || it == ':' || it == '/' }) return false
    if (value.equals("localhost", ignoreCase = true)) return false
    return value.matches(Regex("^[a-zA-Z][a-zA-Z0-9-]{4,31}$"))
}

/** True when both refer to the same login; hosts and users are matched case-insensitively. */
private fun SavedAccount.sameLoginAs(other: SavedAccount): Boolean =
    host.equals(other.host, ignoreCase = true) && user.equals(other.user, ignoreCase = true)

/**
 * Puts [account] at the front — most recent first — refreshing any earlier entry for the
 * same host and user rather than adding a second one, and dropping the oldest beyond
 * [max].
 */
fun mergeAccount(
    existing: List<SavedAccount>,
    account: SavedAccount,
    max: Int = MAX_SAVED_ACCOUNTS,
): List<SavedAccount> {
    if (account.host.isBlank() || account.user.isBlank()) return existing
    return (listOf(account) + existing.filterNot { it.sameLoginAs(account) }).take(max)
}

/** The login for one host and user, or null when nothing has been saved for that pair. */
fun accountFor(accounts: List<SavedAccount>, host: String, user: String): SavedAccount? {
    val key = rememberedHost(host)
    if (key.isBlank() || user.isBlank()) return null
    val wanted = SavedAccount(host = key, user = user.trim(), password = "")
    return accounts.firstOrNull { it.sameLoginAs(wanted) }
}

/** Serialises the saved logins for `SharedPreferences`. */
internal fun encodeAccounts(accounts: List<SavedAccount>): String {
    val array = JSONArray()
    accounts.forEach { account ->
        array.put(
            JSONObject()
                .put("host", account.host)
                .put("user", account.user)
                .put("password", account.password)
                .put("accessCode", account.accessCode),
        )
    }
    return array.toString()
}

/** Reads back what [encodeAccounts] wrote. Anything unreadable yields an empty list. */
internal fun decodeAccounts(raw: String?): List<SavedAccount> {
    if (raw.isNullOrBlank()) return emptyList()
    return runCatching {
        val array = JSONArray(raw)
        (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val host = item.optString("host")
            val user = item.optString("user")
            // A login without a host or a user cannot be offered as a shortcut.
            if (host.isBlank() || user.isBlank()) return@mapNotNull null
            SavedAccount(
                host = host,
                user = user,
                password = item.optString("password"),
                accessCode = item.optString("accessCode"),
            )
        }
    }.getOrDefault(emptyList())
}
