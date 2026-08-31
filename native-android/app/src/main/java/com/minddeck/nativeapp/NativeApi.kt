package com.minddeck.nativeapp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class ApiFailure(val status: Int, message: String): Exception(message)
class NativeApi {
    private val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(65,TimeUnit.SECONDS)
        .callTimeout(75,TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build()
    suspend fun config(): JSONObject = request(null)
    suspend fun request(data: JSONObject?, token: String? = null): JSONObject = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(BuildConfig.API_BASE_URL).header("Accept","application/json")
        if(token != null) request.header("Authorization", "Bearer $token")
        if(data != null) request.post(data.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
        try {
            client.newCall(request.build()).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if(body.length > 1_000_000) throw ApiFailure(502,"The response was too large. Please try a smaller chapter.")
                val json = try { JSONObject(body) } catch (_: Exception) { throw ApiFailure(response.code,"The AI server is not ready yet. Your saved cards are still available.") }
                if(!response.isSuccessful) throw ApiFailure(response.code,json.optString("error","The request failed. Please try again."))
                json
            }
        } catch(e: ApiFailure) { throw e }
          catch(_: java.io.IOException) { throw ApiFailure(0,"Couldn't connect. Check your internet and try again. Your saved cards are safe.") }
    }
    fun session(data: JSONObject): UserSession {
        val user=data.getJSONObject("user")
        return UserSession(user.getString("id"),user.optString("name","Student"),data.getString("access_token"),data.getString("refresh_token"))
    }
}
