package com.simplogics.chat.common.repository

import com.simplogics.chat.common.models.User
import com.simplogics.chat.common.network.NetworkClient
import com.simplogics.chat.common.result.Result
import com.simplogics.chat.common.result.toResult
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.Serializable

class AuthRepository {
    private val client = NetworkClient.client
    private val baseUrl = "http://192.168.29.143:8081" // Use 192.168.2.21 for network access

    suspend fun login(email: String): Result<User> {
        return try {
            val response =
                client.post("$baseUrl/login") {
                    contentType(ContentType.Application.Json)
                    setBody(LoginRequest(email))
                }

            val loginResult = response.toResult<LoginResponse>()

            if (loginResult.ok && loginResult.data != null) {
                val loginData = loginResult.data
                NetworkClient.setAuthToken(loginData.token)
                // In a real app, generate/retrieve RSA keys here
                Result.success(
                    User(
                        id = loginData.email,
                        email = loginData.email,
                        name = loginData.email.substringBefore("@"),
                        publicKey = "MOCK_KEY",
                        // In real flow, generate and send to server
                    ),
                )
            } else {
                Result.error<User>(
                    loginResult.error?.message ?: "Login failed",
                    status = loginResult.error?.status,
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Result.error<User>("Connection error: ${e.message}")
        }
    }
}

@Serializable
data class LoginRequest(val email: String)

@Serializable
data class LoginResponse(val token: String, val email: String)
