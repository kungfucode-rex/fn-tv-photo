package com.tvphoto.ui

/** Loading / loaded / failed, used by every asynchronous screen. */
sealed interface ContentState<out T> {
    data object Loading : ContentState<Nothing>
    data class Ready<T>(val value: T) : ContentState<T>
    data class Failed(val message: String) : ContentState<Nothing>

    val valueOrNull: T?
        get() = (this as? Ready)?.value
}

inline fun <T> ContentState<T>.onReady(block: (T) -> Unit) {
    if (this is ContentState.Ready) block(value)
}
