package com.visionaid.app.util

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

class TtsManager(context: Context) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    private var isInitialized = false
    private var pendingText: String? = null
    private var onStartAction: (() -> Unit)? = null
    private var onDoneAction: (() -> Unit)? = null

    init {
        tts = TextToSpeech(context.applicationContext, this)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.US
            isInitialized = true
            
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    onStartAction?.invoke()
                }

                override fun onDone(utteranceId: String?) {
                    onDoneAction?.invoke()
                }

                @Deprecated("Deprecated in Java", ReplaceWith("Log.e(\"TtsManager\", \"Error in TTS\")"))
                override fun onError(utteranceId: String?) {
                    Log.e("TtsManager", "Error in TTS for utteranceId: $utteranceId")
                }
                
                override fun onError(utteranceId: String?, errorCode: Int) {
                    Log.e("TtsManager", "Error in TTS for utteranceId: $utteranceId, code: $errorCode")
                }
            })
            
            pendingText?.let {
                speak(it, onStartAction ?: {}, onDoneAction ?: {})
                pendingText = null
            }
        } else {
            Log.e("TtsManager", "TTS Initialization failed")
        }
    }

    fun speak(text: String, onStart: () -> Unit = {}, onDone: () -> Unit = {}) {
        onStartAction = onStart
        onDoneAction = onDone
        
        if (isInitialized) {
            val params = Bundle()
            params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "TTS_ID_${System.currentTimeMillis()}")
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, "TTS_ID_${System.currentTimeMillis()}")
        } else {
            pendingText = text
        }
    }

    fun stop() {
        if (isInitialized) {
            tts?.stop()
        }
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
    }
}
