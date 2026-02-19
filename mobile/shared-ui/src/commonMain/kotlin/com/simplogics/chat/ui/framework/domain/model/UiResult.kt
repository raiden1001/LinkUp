package com.simplogics.chat.ui.framework.domain.model

sealed interface UiResult<out T> {
    data class Success<T>(val data: T) : UiResult<T>

    data class Error(
        val message: String,
        val cause: Throwable? = null,
    ) : UiResult<Nothing>
}
