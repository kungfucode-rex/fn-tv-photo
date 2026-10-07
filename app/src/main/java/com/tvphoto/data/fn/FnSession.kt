package com.tvphoto.data.fn

/** An authenticated fnOS session. [baseUrl] never carries a trailing slash. */
data class FnSession(
    val baseUrl: String,
    val token: String,
    val secret: String,
    val backId: String,
    val uid: Long,
    val isAdmin: Boolean,
    val userName: String,
)
