package com.simplogics.chat.common.repository

import com.simplogics.chat.common.network.NetworkClient
import com.simplogics.chat.common.result.Result
import com.simplogics.chat.common.result.toResult
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.Serializable

class MediaRepository {
    private val client = NetworkClient.client
    private val baseUrl = "http://192.168.29.143:8084" // Media Service

    suspend fun uploadImage(
        byteArray: ByteArray,
        extension: String,
    ): Result<String> {
        return try {
            // 1. Get Presigned URL
            val response =
                client.post("$baseUrl/presign") {
                    contentType(ContentType.Application.Json)
                    setBody(PresignRequest(extension, "image/$extension"))
                }
            val presignResponse = response.toResult<PresignResponse>()

            if (presignResponse.ok && presignResponse.data != null) {
                val uploadUrl = presignResponse.data.uploadUrl
                val objectName = presignResponse.data.objectName

                // 2. Upload to MinIO directly using the URL
                val uploadPutResponse =
                    client.put(uploadUrl) {
                        setBody(byteArray)
                    }

                if (uploadPutResponse.status.isSuccess()) {
                    Result.success(objectName)
                } else {
                    Result.error<String>(
                        "MinIO upload failed: ${uploadPutResponse.status}",
                        status = uploadPutResponse.status.value,
                    )
                }
            } else {
                Result.error<String>(
                    "Failed to get upload URL: ${presignResponse.error?.message ?: "Unknown error"}",
                    status = presignResponse.error?.status,
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Result.error<String>(e.message ?: "Unknown error")
        }
    }
}

@Serializable
data class PresignRequest(val extension: String, val contentType: String)

@Serializable
data class PresignResponse(val uploadUrl: String, val objectName: String)
