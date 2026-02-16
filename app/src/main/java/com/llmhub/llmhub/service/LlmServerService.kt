package com.llmhub.llmhub.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.llmhub.llmhub.LlmHubApplication
import com.llmhub.llmhub.R
import com.llmhub.llmhub.data.LLMModel
import com.llmhub.llmhub.data.ModelData
import com.llmhub.llmhub.data.ThemePreferences
import com.llmhub.llmhub.data.api.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import java.util.*

class LlmServerService : Service() {

    private val json = Json {
        prettyPrint = true
        isLenient = true
        ignoreUnknownKeys = true
    }
    private var server: NettyApplicationEngine? = null
    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    companion object {
        private const val TAG = "LlmServerService"
        private const val CHANNEL_ID = "llm_server_channel"
        private const val NOTIFICATION_ID = 1002

        fun start(context: Context) {
            val intent = Intent(context, LlmServerService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, LlmServerService::class.java)
            context.stopService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification("Starting server..."))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startServer()
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        stopServer()
        serviceJob.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startServer() {
        if (server != null) return

        serviceScope.launch {
            val prefs = ThemePreferences(this@LlmServerService)
            val port = prefs.serverPort.first()
            val apiType = prefs.serverApiType.first()

            withContext(Dispatchers.Main) {
                updateNotification("Server running on port $port ($apiType)")
            }

            server = embeddedServer(Netty, port = port) {
                install(ContentNegotiation) {
                    json(this@LlmServerService.json)
                }
                install(CORS) {
                    anyHost()
                    allowHeader(HttpHeaders.ContentType)
                    allowHeader(HttpHeaders.Authorization)
                }
                routing {
                    get("/") {
                        call.respondText("LLM Hub Local Server is running!", contentType = ContentType.Text.Plain)
                    }

                    if (apiType == "OpenAI") {
                        route("/v1") {
                            post("/chat/completions") {
                                handleChatCompletions(call)
                            }
                            get("/models") {
                                handleGetModels(call)
                            }
                        }
                    }
                }
            }.start(wait = false)

            Log.d(TAG, "Server started on port $port")
        }
    }

    private fun stopServer() {
        server?.stop(1000, 2000)
        server = null
        Log.d(TAG, "Server stopped")
    }

    private suspend fun handleChatCompletions(call: ApplicationCall) {
        val request = call.receive<ChatCompletionRequest>()
        val prefs = ThemePreferences(this@LlmServerService)
        val selectedModelName = prefs.serverSelectedModel.first() ?: request.model

        val availableModels = com.llmhub.llmhub.data.ModelRepository.getAvailableModels(this@LlmServerService)
        val model = availableModels.find { it.name == selectedModelName }
            ?: availableModels.find { it.name.contains(request.model, ignoreCase = true) }
            ?: availableModels.firstOrNull { it.category == "text" || it.category == "multimodal" }

        if (model == null) {
            call.respond(HttpStatusCode.NotFound, "Model not found")
            return
        }

        val inferenceService = (applicationContext as LlmHubApplication).inferenceService

        // Ensure model is loaded
        val loaded = inferenceService.loadModel(model)
        if (!loaded) {
            call.respond(HttpStatusCode.InternalServerError, "Failed to load model ${model.name}")
            return
        }

        val prompt = buildPrompt(request)

        if (request.stream) {
            call.respondBytesWriter(contentType = ContentType.Text.EventStream) {
                val flow = inferenceService.generateResponseStream(prompt, model)
                val id = "chatcmpl-" + UUID.randomUUID().toString()
                val created = System.currentTimeMillis() / 1000

                flow.collect { chunk ->
                    val streamResponse = ChatCompletionStreamResponse(
                        id = id,
                        created = created,
                        model = model.name,
                        choices = listOf(
                            ChatStreamChoice(
                                index = 0,
                                delta = ChatMessageDelta(content = chunk),
                                finish_reason = null
                            )
                        )
                    )
                    writeStringUtf8("data: ${this@LlmServerService.json.encodeToString(streamResponse)}\n\n")
                    flush()
                }

                val finalResponse = ChatCompletionStreamResponse(
                    id = id,
                    created = created,
                    model = model.name,
                    choices = listOf(
                        ChatStreamChoice(
                            index = 0,
                            delta = ChatMessageDelta(),
                            finish_reason = "stop"
                        )
                    )
                )
                writeStringUtf8("data: ${this@LlmServerService.json.encodeToString(finalResponse)}\n\n")
                writeStringUtf8("data: [DONE]\n\n")
                flush()
            }
        } else {
            val responseText = inferenceService.generateResponse(prompt, model)

            // Check for tool calling in response if tools were provided
            val toolCalls = if (request.tools != null) {
                extractToolCalls(responseText)
            } else null

            val content = if (toolCalls != null) null else responseText

            val response = ChatCompletionResponse(
                id = "chatcmpl-" + UUID.randomUUID().toString(),
                created = System.currentTimeMillis() / 1000,
                model = model.name,
                choices = listOf(
                    ChatChoice(
                        index = 0,
                        message = ChatMessage(
                            role = "assistant",
                            content = content,
                            tool_calls = toolCalls
                        ),
                        finish_reason = if (toolCalls != null) "tool_calls" else "stop"
                    )
                ),
                usage = Usage(
                    prompt_tokens = prompt.length / 4,
                    completion_tokens = responseText.length / 4,
                    total_tokens = (prompt.length + responseText.length) / 4
                )
            )
            call.respond(response)
        }
    }

    private suspend fun handleGetModels(call: ApplicationCall) {
        val models = ModelData.models.filter { it.category == "text" || it.category == "multimodal" }
        val response = mapOf(
            "object" to "list",
            "data" to models.map { model ->
                mapOf(
                    "id" to model.name,
                    "object" to "model",
                    "created" to 1677610602,
                    "owned_by" to model.source
                )
            }
        )
        call.respond(response)
    }

    private fun buildPrompt(request: ChatCompletionRequest): String {
        val builder = StringBuilder()

        // Add system message if present
        request.messages.find { it.role == "system" }?.let {
            builder.append("system: ${it.content}\n")
        }

        // Add conversation history
        request.messages.filter { it.role != "system" }.forEach { msg ->
            builder.append("${msg.role}: ${msg.content}\n")
        }

        // Add instruction for tool calling if tools are provided
        if (request.tools != null) {
            builder.append("\nYou have access to the following tools. If you need to use a tool, respond with <tool_call>{\"name\": \"function_name\", \"arguments\": \"{...}\"}</tool_call>.\n")
            request.tools.forEach { tool ->
                builder.append("- ${tool.function.name}: ${tool.function.description}\n")
                builder.append("  Parameters: ${tool.function.parameters}\n")
            }
        }

        builder.append("assistant: ")
        return builder.toString()
    }

    private fun extractToolCalls(text: String): List<ToolCall>? {
        val regex = Regex("<tool_call>(.*?)</tool_call>")
        val matches = regex.findAll(text)
        if (matches.none()) return null

        return matches.mapIndexed { index, match ->
            try {
                val jsonStr = match.groupValues[1]
                val functionCall = json.decodeFromString<FunctionCall>(jsonStr)
                ToolCall(
                    id = "call_" + UUID.randomUUID().toString().substring(0, 8),
                    type = "function",
                    function = functionCall
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse tool call: ${e.message}")
                null
            }
        }.filterNotNull().toList().ifEmpty { null }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "LLM Hub Server Channel",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    private fun createNotification(content: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("LLM Hub Local Server")
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .build()
    }

    private fun updateNotification(content: String) {
        val notification = createNotification(content)
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
    }
}
