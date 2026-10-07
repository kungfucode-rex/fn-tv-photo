package com.tvphoto.data.fn

/**
 * Constants baked into the fnOS web frontend.
 *
 * [SALT] and [SIGN_SECRET] sign the local `/p` photo API.
 *
 * The FN Connect cloud lookup used to have its own pair here (`CONNECT_SECRET` and the
 * `fn-sign` key). They went with FN ID sign-in, which is no longer supported — the
 * vendor's relay turned out not to proxy the API at all. See docs/fnos-photo-api.md.
 */
internal object FnConfig {
    const val SALT = "NDzZTVxnRKP8Z0jXg1VAMonaG8akvh"
    const val SIGN_SECRET = "EAECCF25-80A6-4666-A7C2-A76904A74AB6"
}
