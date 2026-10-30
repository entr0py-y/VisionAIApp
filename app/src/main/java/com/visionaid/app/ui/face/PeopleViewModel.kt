package com.visionaid.app.ui.face

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.visionaid.app.data.face.FaceMemoryRepository
import com.visionaid.app.data.face.FaceProfile
import com.visionaid.app.util.TtsManager
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class PeopleViewModel(
    val repository: FaceMemoryRepository,
    private val ttsManager: TtsManager
) : ViewModel() {

    val profiles: StateFlow<List<FaceProfile>> = repository.profiles

    fun deleteProfile(id: String, name: String) {
        viewModelScope.launch {
            repository.deleteProfile(id)
            ttsManager.speak("Removed $name from memory.")
        }
    }
}

class PeopleViewModelFactory(
    private val repository: FaceMemoryRepository,
    private val ttsManager: TtsManager
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(PeopleViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return PeopleViewModel(repository, ttsManager) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}
