package com.llmhub.llmhub.viewmodels

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.llmhub.llmhub.data.ThemePreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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
