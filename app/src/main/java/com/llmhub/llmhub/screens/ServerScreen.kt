package com.llmhub.llmhub.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.llmhub.llmhub.R
import com.llmhub.llmhub.data.ModelData
import com.llmhub.llmhub.data.localFileName
import com.llmhub.llmhub.service.LlmServerService
import com.llmhub.llmhub.viewmodels.ServerViewModel
import com.llmhub.llmhub.viewmodels.ServerViewModelFactory
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerScreen(
    onNavigateBack: () -> Unit,
    serverViewModel: ServerViewModel
) {
    val context = LocalContext.current
    val serverEnabled by serverViewModel.serverEnabled.collectAsState()
    val serverPort by serverViewModel.serverPort.collectAsState()
    val serverSelectedModel by serverViewModel.serverSelectedModel.collectAsState()
    val serverApiType by serverViewModel.serverApiType.collectAsState()

    var portText by remember { mutableStateOf(serverPort.toString()) }
    var showModelDialog by remember { mutableStateOf(false) }
    var showApiTypeDialog by remember { mutableStateOf(false) }

    val localIp = remember { serverViewModel.getLocalIpAddress() }

    // Get downloaded models only
    val downloadedModels = remember(context) {
        ModelData.models
            .filter { it.category == "text" || it.category == "multimodal" }
            .filter { model ->
                val modelsDir = File(context.filesDir, "models")
                val modelFile = File(modelsDir, model.localFileName())
                modelFile.exists() && modelFile.length() > 0
            }
            .map { it.name }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Local API Server") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Server Status Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (serverEnabled)
                        MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (serverEnabled) "Server is Running" else "Server is Stopped",
                            style = MaterialTheme.typography.titleLarge
                        )
                        if (serverEnabled && localIp != null) {
                            Text(
                                text = "URL: http://$localIp:$serverPort/v1",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                    Switch(
                        checked = serverEnabled,
                        onCheckedChange = { enabled ->
                            serverViewModel.setServerEnabled(enabled)
                            if (enabled) {
                                LlmServerService.start(context)
                            } else {
                                LlmServerService.stop(context)
                            }
                        }
                    )
                }
            }

            // Configuration Section
            Text(
                text = "Configuration",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )

            // Port Setting
            OutlinedTextField(
                value = portText,
                onValueChange = {
                    if (it.isEmpty() || it.all { char -> char.isDigit() }) {
                        portText = it
                        it.toIntOrNull()?.let { port ->
                            serverViewModel.setServerPort(port)
                        }
                    }
                },
                label = { Text("Server Port") },
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                enabled = !serverEnabled
            )

            // API Type Setting
            Card(
                onClick = { if (!serverEnabled) showApiTypeDialog = true },
                modifier = Modifier.fillMaxWidth(),
                enabled = !serverEnabled
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Api, contentDescription = null)
                    Spacer(modifier = Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("API Provider Type", style = MaterialTheme.typography.bodyLarge)
                        Text(serverApiType, style = MaterialTheme.typography.bodySmall)
                    }
                    Icon(Icons.Default.KeyboardArrowRight, contentDescription = null)
                }
            }

            // Model Selection
            Card(
                onClick = { if (!serverEnabled) showModelDialog = true },
                modifier = Modifier.fillMaxWidth(),
                enabled = !serverEnabled
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.ModelTraining, contentDescription = null)
                    Spacer(modifier = Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Model to Serve", style = MaterialTheme.typography.bodyLarge)
                        Text(serverSelectedModel ?: "Default (from request)", style = MaterialTheme.typography.bodySmall)
                    }
                    Icon(Icons.Default.KeyboardArrowRight, contentDescription = null)
                }
            }

            // Information
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Notes",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "• The server runs in the background and will stay active as long as the notification is visible.\n" +
                               "• Tool calling is supported via the OpenAI API format.\n" +
                               "• Streaming is supported for real-time responses.\n" +
                               "• Ensure your client device is on the same network as your phone.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }

    // API Type Selection Dialog
    if (showApiTypeDialog) {
        AlertDialog(
            onDismissRequest = { showApiTypeDialog = false },
            title = { Text("Select API Type") },
            text = {
                Column {
                    listOf("OpenAI").forEach { type ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = serverApiType == type,
                                onClick = {
                                    serverViewModel.setServerApiType(type)
                                    showApiTypeDialog = false
                                }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(type)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showApiTypeDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Model Selection Dialog
    if (showModelDialog) {
        AlertDialog(
            onDismissRequest = { showModelDialog = false },
            title = { Text("Select Model") },
            text = {
                LazyColumn {
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = serverSelectedModel == null,
                                onClick = {
                                    serverViewModel.setServerSelectedModel(null)
                                    showModelDialog = false
                                }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Default (from request)")
                        }
                    }
                    items(downloadedModels) { modelName ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = serverSelectedModel == modelName,
                                onClick = {
                                    serverViewModel.setServerSelectedModel(modelName)
                                    showModelDialog = false
                                }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(modelName)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showModelDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}
