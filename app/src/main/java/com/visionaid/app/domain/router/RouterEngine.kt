package com.visionaid.app.domain.router

import android.util.Log
import com.visionaid.app.data.model.ClassifyRequest
import com.visionaid.app.data.remote.VisionApiService
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

enum class IntentType {
    VISION, NAVIGATION, LOCATION_INFO, PLACE_SEARCH, GENERAL_CHAT
}

sealed class IntentAction {
    data class Vision(val text: String) : IntentAction()
    data class Navigation(val text: String, val destination: String) : IntentAction()
    data class LocationInfo(val text: String) : IntentAction()
    data class PlaceSearch(val text: String) : IntentAction()
    data class GeneralChat(val text: String, val pregenResponse: String?) : IntentAction()
}

class RouterEngine(private val apiService: VisionApiService) {

    private val visionRegex = Regex("(?:see|look|vision|scan|describe|camera|qr\\s*code|in\\s*front\\s*of\\s*me|read.*in\\s*front)", RegexOption.IGNORE_CASE)
    private val navigationRegex = Regex("(?:navigate\\s+to|directions?\\s+to|take\\s+me\\s+to|route\\s+to)\\s+(.+)", RegexOption.IGNORE_CASE)
    private val locationRegex = Regex("(?:where\\s+am\\s+i|what\\s+is\\s+my\\s+location|current\\s+position)", RegexOption.IGNORE_CASE)

    suspend fun dispatch(text: String, pregenResponse: String? = null, onRoute: suspend (IntentAction) -> Unit) {
        val navMatch = navigationRegex.find(text)

        // Local Fast-Path
        val action = when {
            visionRegex.containsMatchIn(text) -> IntentAction.Vision(text)
            navMatch != null -> IntentAction.Navigation(text, navMatch.groupValues[1].trim())
            locationRegex.containsMatchIn(text) -> IntentAction.LocationInfo(text)
            else -> {
                // Remote Fallback
                try {
                    withTimeout(8000) {
                        val response = apiService.classify(ClassifyRequest(text))
                        if (response.isSuccessful) {
                            val body = response.body()
                            when (body?.intent?.uppercase()) {
                                "VISION" -> IntentAction.Vision(text)
                                "NAVIGATION" -> IntentAction.Navigation(text, body.destination ?: "")
                                "LOCATION_INFO" -> IntentAction.LocationInfo(text)
                                "PLACE_SEARCH" -> IntentAction.PlaceSearch(text)
                                else -> IntentAction.GeneralChat(text, pregenResponse)
                            }
                        } else {
                            IntentAction.GeneralChat(text, pregenResponse)
                        }
                    }
                } catch (e: TimeoutCancellationException) {
                    Log.w("RouterEngine", "Classification timed out, falling back to GENERAL_CHAT")
                    IntentAction.GeneralChat(text, pregenResponse)
                } catch (e: Exception) {
                    Log.e("RouterEngine", "Classification failed", e)
                    IntentAction.GeneralChat(text, pregenResponse)
                }
            }
        }
        
        onRoute(action)
    }
}
