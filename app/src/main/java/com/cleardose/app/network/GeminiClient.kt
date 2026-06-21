package com.cleardose.app.network

import android.util.Log
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

// Modelos de respuesta de la API de Gemini
data class GeminiResponse(
    @SerializedName("candidates") val candidates: List<Candidate>?
)

data class Candidate(
    @SerializedName("content") val content: Content?
)

data class Content(
    @SerializedName("parts") val parts: List<Part>?
)

data class Part(
    @SerializedName("text") val text: String?
)

// Modelos de error de la API de Gemini
data class GeminiErrorResponse(
    @SerializedName("error") val error: GeminiErrorDetail?
)

data class GeminiErrorDetail(
    @SerializedName("code") val code: Int?,
    @SerializedName("message") val message: String?,
    @SerializedName("status") val status: String?
)

// Modelo local de dimensiones mapeado al del backend
data class VehicleSpecs(
    val length: Double,
    val width: Double,
    val height: Double,
    val totalArea: Double,
    val dirtinessLevel: String?
)

sealed class GeminiResult {
    data class Success(val specs: VehicleSpecs) : GeminiResult()
    data class Error(val message: String) : GeminiResult()
}

class GeminiClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
        
    private val gson = Gson()

    suspend fun analyzeVehicleImage(base64Image: String, apiKey: String, model: String = "gemini-2.5-flash"): GeminiResult = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            return@withContext GeminiResult.Error("API Key de Gemini está vacía. Por favor configúrala en el perfil.")
        }
        
        val url = "https://generativelanguage.googleapis.com/v1/models/$model:generateContent?key=$apiKey"
        
        val promptText = """
            Identify the vehicle in the image. Return a JSON object with:
            1. Its official physical dimensions in meters: 'length' (largo), 'width' (ancho), 'height' (alto), and 'totalArea' (surface area in square meters for vehicle washing, generally length * width * 2 + length * height * 2 + width * height).
            2. The level of vehicle dirtiness (suciedad) observed in the photo, labeled as 'dirtinessLevel'. This must be exactly one of: "LOW" (little/no dirt, light dust), "MEDIUM" (average road dirt, some spots), or "HIGH" (heavy mud, thick dirt layers, or very dirty vehicle).
            
            Return ONLY the raw JSON object, without markdown formatting or backticks.
            Format: {"length": 0.0, "width": 0.0, "height": 0.0, "totalArea": 0.0, "dirtinessLevel": "MEDIUM"}
        """.trimIndent()

        // Crear la estructura de petición JSON para Gemini
        val requestJson = mapOf(
            "contents" to listOf(
                mapOf(
                    "parts" to listOf(
                        mapOf("text" to promptText),
                        mapOf(
                            "inlineData" to mapOf(
                                "mimeType" to "image/jpeg",
                                "data" to base64Image
                            )
                        )
                    )
                )
            )
        )

        val requestBodyString = gson.toJson(requestJson)
        val requestBody = requestBodyString.toRequestBody("application/json; charset=utf-8".toMediaType())

        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .build()

        try {
            client.newCall(request).execute().use { response ->
                val code = response.code
                val responseBody = response.body?.string()
                Log.d("GeminiClient", "API Response Code: $code")
                
                if (!response.isSuccessful || responseBody == null) {
                    Log.e("GeminiClient", "Error response: $responseBody")
                    val errorMsg = try {
                        val errObj = gson.fromJson(responseBody, GeminiErrorResponse::class.java)
                        errObj.error?.message ?: "Código de respuesta HTTP $code"
                    } catch (e: Exception) {
                        "Código de respuesta HTTP $code"
                    }
                    return@withContext GeminiResult.Error(errorMsg)
                }
                
                val geminiResponse = gson.fromJson(responseBody, GeminiResponse::class.java)
                val jsonText = geminiResponse.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text
                
                if (jsonText != null) {
                    Log.d("GeminiClient", "Extracted JSON: $jsonText")
                    // Limpiar posibles bloques markdown si el modelo no los omitió
                    val cleanJson = jsonText.trim()
                        .removePrefix("```json")
                        .removePrefix("```")
                        .removeSuffix("```")
                        .trim()
                    
                    try {
                        val specs = gson.fromJson(cleanJson, VehicleSpecs::class.java)
                        GeminiResult.Success(specs)
                    } catch (e: Exception) {
                        GeminiResult.Error("Error al interpretar las medidas de la IA: ${e.localizedMessage}")
                    }
                } else {
                    Log.e("GeminiClient", "No content text found in candidates")
                    GeminiResult.Error("La IA no devolvió ningún texto descriptivo del vehículo.")
                }
            }
        } catch (e: Exception) {
            Log.e("GeminiClient", "API Request Exception: ${e.message}", e)
            GeminiResult.Error("Error de conexión: ${e.localizedMessage ?: "sin respuesta del servidor"}")
        }
    }
}
