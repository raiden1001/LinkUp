package com.simplogics.chat.common.result

import kotlinx.serialization.Serializable

/**
 * Shared API envelope used across backend + mobile.
 *
 * JSON shape (always the same):
 * - ok: boolean
 * - data: T? (present on success)
 * - error: ApiError? (present on failure)
 */
@Serializable
data class Result<out T>(
    val ok: Boolean,
    val data: T? = null,
    val error: ApiError? = null,
) {
    companion object {
        fun <T> success(data: T): Result<T> = Result(ok = true, data = data)

        fun <T> error(
            message: String,
            code: String? = null,
            status: Int? = null,
        ): Result<T> = Result(ok = false, error = ApiError(message = message, code = code, status = status))
    }
}

@Serializable
data class ApiError(
    val message: String,
    val code: String? = null,
    val status: Int? = null,
)
