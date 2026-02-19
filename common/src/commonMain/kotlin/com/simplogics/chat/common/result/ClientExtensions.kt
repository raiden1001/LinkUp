package com.simplogics.chat.common.result

import io.ktor.client.call.body
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess

suspend inline fun <reified T> HttpResponse.toResult(): Result<T> {
    val statusCode = status.value

    // Best case: backend always returns the shared Result envelope.
    try {
        val decoded = body<Result<T>>()
        // If server accidentally returns ok=true with a non-2xx status, normalize to an error.
        if (!status.isSuccess() && decoded.ok) {
            return Result.error(message = "HTTP $statusCode ${status.description}", status = statusCode)
        }
        // If server returns ok=false but forgets to attach status, attach it here.
        if (!decoded.ok && decoded.error?.status == null) {
            return decoded.copy(error = decoded.error?.copy(status = statusCode))
        }
        return decoded
    } catch (_: Exception) {
        // Fall back: map HTTP status -> Result automatically, even if body isn't JSON.
        val bodyText =
            try {
                bodyAsText()
            } catch (_: Exception) {
                null
            }

        val message =
            when {
                !bodyText.isNullOrBlank() -> bodyText
                status.isSuccess() -> "Decoding error"
                else -> "HTTP $statusCode ${status.description}"
            }

        return Result.error(message = message, status = statusCode)
    }
}
