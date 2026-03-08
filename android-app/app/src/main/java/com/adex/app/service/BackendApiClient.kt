package com.adex.app.service

import android.content.Context
import com.adex.app.data.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

// BackendApiClient handles HTTPS operations separate from WebSocket transport.
class BackendApiClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    suspend fun requestPairingCode(
        settings: SettingsStore,
        model: String,
        androidVersion: String,
        appVersion: String
    ): PairingInfo = withContext(Dispatchers.IO) {
        val bodyJson = JSONObject().apply {
            put("deviceId", settings.stableDeviceId)
            settings.deviceToken?.let { put("deviceToken", it) }
            settings.enrollmentToken.takeIf { it.isNotBlank() }?.let { put("enrollmentToken", it) }
            put("name", "A-Dex Android")
            put("model", model)
            put("androidVersion", androidVersion)
            put("appVersion", appVersion)
        }

        val request = Request.Builder()
            .url("${settings.backendHttpUrl}/api/v1/pairing/code")
            .post(bodyJson.toString().toRequestBody("application/json".toMediaTypeOrNull()))
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("Pairing code request failed with ${response.code}")
            }

            val text = response.body?.string() ?: throw IllegalStateException("Empty pairing response")
            val json = JSONObject(text)
            PairingInfo(
                deviceId = json.getString("deviceId"),
                deviceToken = json.getString("deviceToken"),
                pairCode = json.getString("pairCode"),
                expiresAt = json.getLong("expiresAt"),
                autoEnrolled = json.optBoolean("autoEnrolled", false),
                autoEnrollGuildId = json.optString("autoEnrollGuildId").takeIf { it.isNotBlank() },
                autoEnrollChannelId = json.optString("autoEnrollChannelId").takeIf { it.isNotBlank() },
                autoEnrollBound = json.optBoolean("autoEnrollBound", false)
            )
        }
    }

    suspend fun uploadMedia(
        settings: SettingsStore,
        commandId: String,
        mediaFile: File,
        mimeType: String
    ): String = withContext(Dispatchers.IO) {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "file",
                mediaFile.name,
                mediaFile.asRequestBody(mimeType.toMediaTypeOrNull())
            )
            .build()

        val token = settings.deviceToken ?: throw IllegalStateException("Missing device token")
        val request = Request.Builder()
            .url("${settings.backendHttpUrl}/api/v1/commands/$commandId/media")
            .header("x-device-id", settings.stableDeviceId)
            .header("Authorization", "Bearer $token")
            .post(body)
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("Media upload failed with ${response.code}")
            }

            val text = response.body?.string() ?: throw IllegalStateException("Empty media response")
            JSONObject(text).getString("mediaId")
        }
    }

    suspend fun downloadImageToCache(context: Context, imageUrl: String): File = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(imageUrl).get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("Image download failed with ${response.code}")
            }

            val bytes = response.body?.bytes() ?: throw IllegalStateException("Empty image body")
            val outFile = File(context.cacheDir, "show_${System.currentTimeMillis()}.img")
            FileOutputStream(outFile).use { it.write(bytes) }
            outFile
        }
    }

    suspend fun downloadImageToBitmap(context: Context, imageUrl: String): android.graphics.Bitmap? = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder().url(imageUrl).get().build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@runCatching null
                val bytes = response.body?.bytes() ?: return@runCatching null
                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }
        }.getOrNull()
    }

    suspend fun downloadUrlToCache(context: Context, fileUrl: String): File = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(fileUrl).get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("File download failed with ${response.code}")
            }

            val bytes = response.body?.bytes() ?: throw IllegalStateException("Empty file body")
            val outFile = File(context.cacheDir, "download_${System.currentTimeMillis()}.bin")
            FileOutputStream(outFile).use { it.write(bytes) }
            outFile
        }
    }
}

data class PairingInfo(
    val deviceId: String,
    val deviceToken: String,
    val pairCode: String,
    val expiresAt: Long,
    val autoEnrolled: Boolean = false,
    val autoEnrollGuildId: String? = null,
    val autoEnrollChannelId: String? = null,
    val autoEnrollBound: Boolean = false,
)
