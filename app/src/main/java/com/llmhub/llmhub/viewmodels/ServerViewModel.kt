package com.llmhub.llmhub.viewmodels

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.net.Uri
import com.google.gson.Gson
import com.llmhub.llmhub.data.LLMModel
import com.llmhub.llmhub.data.ModelRepository
import com.llmhub.llmhub.data.ModelRequirements
import com.llmhub.llmhub.data.ThemePreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface

class ServerViewModel(private val context: Context) : ViewModel() {
    private val themePreferences = ThemePreferences(context)

    private val _serverEnabled = MutableStateFlow(false)
    val serverEnabled: StateFlow<Boolean> = _serverEnabled.asStateFlow()

    private val _serverPort = MutableStateFlow(8080)
    val serverPort: StateFlow<Int> = _serverPort.asStateFlow()

    private val _serverSelectedModel = MutableStateFlow<String?>(null)
    val serverSelectedModel: StateFlow<String?> = _serverSelectedModel.asStateFlow()

    private val _serverApiType = MutableStateFlow("OpenAI")
    val serverApiType: StateFlow<String> = _serverApiType.asStateFlow()

    init {
        viewModelScope.launch {
            themePreferences.serverEnabled.collect { enabled ->
                _serverEnabled.value = enabled
            }
        }
        viewModelScope.launch {
            themePreferences.serverPort.collect { port ->
                _serverPort.value = port
            }
        }
        viewModelScope.launch {
            themePreferences.serverSelectedModel.collect { model ->
                _serverSelectedModel.value = model
            }
        }
        viewModelScope.launch {
            themePreferences.serverApiType.collect { apiType ->
                _serverApiType.value = apiType
            }
        }
    }

    fun setServerEnabled(enabled: Boolean) {
        viewModelScope.launch {
            themePreferences.setServerEnabled(enabled)
            _serverEnabled.value = enabled
        }
    }

    fun setServerPort(port: Int) {
        viewModelScope.launch {
            themePreferences.setServerPort(port)
            _serverPort.value = port
        }
    }

    fun setServerSelectedModel(modelName: String?) {
        viewModelScope.launch {
            themePreferences.setServerSelectedModel(modelName)
            _serverSelectedModel.value = modelName
        }
    }

    fun setServerApiType(apiType: String) {
        viewModelScope.launch {
            themePreferences.setServerApiType(apiType)
            _serverApiType.value = apiType
        }
    }

    fun getAvailableModels(): List<LLMModel> {
        // We use a broader predicate that also includes models that haven't been copied to local storage yet
        // but have a valid content:// URI.
        // Note: ModelRepository.getAvailableModels currently filters for file existence.
        // We might need to handle this here.
        val bundledAndDownloaded = ModelRepository.getAvailableModels(context)

        // Also manually load imported models from prefs because ModelRepository might filter out un-copied ones
        val prefs = context.getSharedPreferences("model_prefs", Context.MODE_PRIVATE)
        val importedJson = prefs.getString("imported_models", null)
        val imported = if (importedJson != null) {
            try {
                Gson().fromJson(importedJson, Array<LLMModel>::class.java).toList()
            } catch (e: Exception) {
                emptyList()
            }
        } else {
            emptyList()
        }

        return (bundledAndDownloaded + imported).distinctBy { it.name }
    }

    fun importGguf(uri: Uri, fileName: String) {
        viewModelScope.launch {
            val modelName = fileName.substringBeforeLast(".")
            val newModel = LLMModel(
                name = "Imported: $modelName",
                description = "Locally selected GGUF model",
                url = uri.toString(),
                category = "text",
                sizeBytes = 0, // Will be updated on load
                source = "Custom",
                supportsVision = false,
                supportsAudio = false,
                supportsGpu = true,
                requirements = ModelRequirements(4, 8),
                contextWindowSize = 2048,
                modelFormat = "gguf",
                isDownloaded = true
            )

            // Save to imported models in shared prefs
            val prefs = context.getSharedPreferences("model_prefs", Context.MODE_PRIVATE)
            val importedJson = prefs.getString("imported_models", null)
            val imported = if (importedJson != null) {
                try {
                    Gson().fromJson(importedJson, Array<LLMModel>::class.java).toMutableList()
                } catch (e: Exception) {
                    mutableListOf()
                }
            } else {
                mutableListOf()
            }

            if (!imported.any { it.name == newModel.name }) {
                imported.add(newModel)
                prefs.edit().putString("imported_models", Gson().toJson(imported)).apply()
            }

            // Select this model for the server
            setServerSelectedModel(newModel.name)
        }
    }

    fun getLocalIpAddress(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (!address.isLoopbackAddress && address is Inet4Address) {
                        return address.hostAddress
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }
}
