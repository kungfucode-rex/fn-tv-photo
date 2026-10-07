package com.tvphoto.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Saved logins are keyed by host *and* user, so that a second account on the same NAS
 * does not overwrite the first. These pin the rules that make that true, plus the
 * `SharedPreferences` encoding it is stored through.
 */
class SavedAccountTest {

    private fun account(host: String, user: String, password: String = "pw") =
        SavedAccount(host = host, user = user, password = password)

    @Test
    fun `the most recent login comes first`() {
        val merged = mergeAccount(
            listOf(account("nas-a:5666", "anna")),
            account("nas-b:5666", "bob"),
        )

        assertEquals(listOf("bob", "anna"), merged.map { it.user })
    }

    @Test
    fun `signing in again refreshes rather than duplicates`() {
        val merged = mergeAccount(
            listOf(account("nas-a:5666", "anna", "old"), account("nas-b:5666", "bob")),
            account("nas-a:5666", "anna", "new"),
        )

        assertEquals(2, merged.size)
        assertEquals(listOf("anna", "bob"), merged.map { it.user })
        assertEquals("new", merged.first().password)
    }

    @Test
    fun `two accounts on one NAS are separate entries`() {
        val merged = mergeAccount(
            listOf(account("nas-a:5666", "anna")),
            account("nas-a:5666", "bob", "bobs-pw"),
        )

        // The account just used moves to the front; both are still there.
        assertEquals(listOf("bob", "anna"), merged.map { it.user })
        assertEquals("bobs-pw", accountFor(merged, "nas-a:5666", "bob")?.password)
        assertEquals("pw", accountFor(merged, "nas-a:5666", "anna")?.password)
    }

    @Test
    fun `the same user on two NASes are separate entries`() {
        val merged = mergeAccount(
            listOf(account("nas-a:5666", "anna", "a-pw")),
            account("nas-b:5666", "anna", "b-pw"),
        )

        assertEquals(2, merged.size)
        assertEquals("a-pw", accountFor(merged, "nas-a:5666", "anna")?.password)
        assertEquals("b-pw", accountFor(merged, "nas-b:5666", "anna")?.password)
    }

    @Test
    fun `host and user are matched case-insensitively`() {
        val merged = listOf(account("NAS-A:5666", "Anna"))

        assertEquals("pw", accountFor(merged, "nas-a:5666", "anna")?.password)
    }

    @Test
    fun `a host is matched in the same form the app stores it`() {
        val merged = listOf(account("192.168.31.14:50316", "anna"))

        assertEquals("pw", accountFor(merged, "192.168.31.14:50316", "anna")?.password)
        // The scheme is not part of the key.
        assertEquals("pw", accountFor(merged, "http://192.168.31.14:50316", "anna")?.password)
        // A bare address means the *default* port, which is a different endpoint — a
        // login saved against the real port must not be offered for it.
        assertNull(accountFor(merged, "192.168.31.14", "anna"))
    }

    @Test
    fun `the oldest login is dropped once the cap is reached`() {
        val full = (1..MAX_SAVED_ACCOUNTS).map { account("nas-$it:5666", "user-$it") }
        val merged = mergeAccount(full, account("nas-new:5666", "newest"))

        assertEquals(MAX_SAVED_ACCOUNTS, merged.size)
        assertEquals("newest", merged.first().user)
        assertNull(accountFor(merged, "nas-$MAX_SAVED_ACCOUNTS:5666", "user-$MAX_SAVED_ACCOUNTS"))
    }

    @Test
    fun `an incomplete login is not stored`() {
        val existing = listOf(account("nas-a:5666", "anna"))

        assertEquals(existing, mergeAccount(existing, account("", "bob")))
        assertEquals(existing, mergeAccount(existing, account("nas-a:5666", "")))
    }

    @Test
    fun `a lookup with nothing typed finds nothing`() {
        val accounts = listOf(account("nas-a:5666", "anna"))

        assertNull(accountFor(accounts, "", "anna"))
        assertNull(accountFor(accounts, "nas-a:5666", ""))
        assertNull(accountFor(accounts, "nas-a:5666", "nobody"))
    }

    @Test
    fun `saved logins survive a round trip through storage`() {
        val accounts = listOf(
            SavedAccount("kungfucode", "seis", "pw-1", "1234"),
            SavedAccount("192.168.31.14:50316", "anna", "pw-2", ""),
        )

        assertEquals(accounts, decodeAccounts(encodeAccounts(accounts)))
    }

    @Test
    fun `unreadable stored data yields no accounts instead of throwing`() {
        assertEquals(emptyList<SavedAccount>(), decodeAccounts(null))
        assertEquals(emptyList<SavedAccount>(), decodeAccounts(""))
        assertEquals(emptyList<SavedAccount>(), decodeAccounts("not json"))
        // Entries missing a host or a user are dropped rather than offered as shortcuts.
        assertEquals(
            emptyList<SavedAccount>(),
            decodeAccounts("""[{"host":"","user":"anna"},{"user":"bob"},{"host":"nas"}]"""),
        )
    }

    @Test
    fun `an address is remembered as host and port`() {
        assertEquals("192.168.31.14:50316", rememberedHost(" 192.168.31.14:50316 "))
        // A bare address takes the secure default, since the app is HTTPS only.
        assertEquals("192.168.31.14:5667", rememberedHost("192.168.31.14"))
        assertEquals("nas.example.com:5667", rememberedHost("https://nas.example.com"))
    }

    @Test
    fun `handles saved by the FN ID version are recognised`() {
        // The shape the old FN ID login accepted — and nothing that is a real address,
        // so removing FN ID does not discard logins that still work.
        assertTrue(isLegacyFnIdHost("kungfucode"))
        assertTrue(isLegacyFnIdHost("my-home-2"))

        assertFalse(isLegacyFnIdHost("192.168.31.14:50316"))
        assertFalse(isLegacyFnIdHost("nas.example.com"))
        assertFalse(isLegacyFnIdHost("nas:5666"))
        assertFalse(isLegacyFnIdHost("http://nas"))
        // A real host that merely looks like a handle.
        assertFalse(isLegacyFnIdHost("localhost"))
        assertFalse(isLegacyFnIdHost("ab"))
        assertFalse(isLegacyFnIdHost(""))
    }
}
