package com.nathanael.aether.backend

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.cio.EngineMain
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

fun main(args: Array<String>) {
    EngineMain.main(args)
}

fun Application.module() {
    val config = BackendConfig.fromEnvironment()
    val json = Json {
        ignoreUnknownKeys = true
    }
    val client = HttpClient(CIO) {
        install(ClientContentNegotiation) {
            json(json)
        }
    }

    install(ContentNegotiation) {
        json(json)
    }

    routing {
        get("/health") {
            call.respond(
                HealthResponse(
                    status = "ok",
                    upstreamBaseUrl = config.upstreamBaseUrl,
                    defaultModel = config.defaultModel,
                    localFallback = config.allowLocalFallback
                )
            )
        }

        post("/v1/chat/completions") {
            val request = call.receive<ChatCompletionRequest>()
            val upstreamRequest = request.withDefaults(config.defaultModel)

            val upstream = runCatching {
                client.post(config.chatCompletionsUrl) {
                    contentType(ContentType.Application.Json)
                    header("User-Agent", "AetherChat/1.0")
                    config.upstreamApiKey?.let { bearerAuth(it) }
                    setBody(upstreamRequest)
                }
            }

            val response = upstream.getOrNull()
            if (response != null && response.status.isSuccess()) {
                call.respondTextBody(response.body())
                return@post
            }

            if (!config.allowLocalFallback) {
                val detail = upstream.exceptionOrNull()?.message ?: response?.status?.description ?: "Unknown upstream error"
                call.respond(HttpStatusCode.BadGateway, ErrorResponse(error = "upstream_unavailable", message = detail))
                return@post
            }

            call.respond(fallbackCompletion(upstreamRequest, response?.status, upstream.exceptionOrNull()))
        }
    }
}

private suspend fun io.ktor.server.application.ApplicationCall.respondTextBody(body: String) {
    respondText(body, ContentType.Application.Json, HttpStatusCode.OK)
}

private fun io.ktor.http.HttpStatusCode.isSuccess(): Boolean = value in 200..299

private data class BackendConfig(
    val host: String,
    val port: Int,
    val upstreamBaseUrl: String,
    val upstreamApiKey: String?,
    val defaultModel: String,
    val allowLocalFallback: Boolean
) {
    val chatCompletionsUrl: String = normalizeChatUrl(upstreamBaseUrl)

    companion object {
        fun fromEnvironment(): BackendConfig {
            return BackendConfig(
                host = env("AETHER_HOST", "0.0.0.0"),
                port = env("AETHER_PORT", "8787").toIntOrNull() ?: 8787,
                upstreamBaseUrl = env("AETHER_UPSTREAM_BASE_URL", "http://127.0.0.1:11434/v1"),
                upstreamApiKey = System.getenv("AETHER_UPSTREAM_API_KEY")?.takeIf { it.isNotBlank() },
                defaultModel = env("AETHER_MODEL", "aether-local"),
                allowLocalFallback = env("AETHER_ALLOW_LOCAL_FALLBACK", "true").toBooleanStrictOrNull() ?: true
            )
        }

        private fun env(name: String, fallback: String): String =
            System.getenv(name)?.takeIf { it.isNotBlank() } ?: fallback
    }
}

private fun normalizeChatUrl(baseUrl: String): String {
    val trimmed = baseUrl.trim().trimEnd('/')
    return when {
        trimmed.endsWith("/chat/completions") -> trimmed
        trimmed.endsWith("/v1") -> "$trimmed/chat/completions"
        else -> "$trimmed/v1/chat/completions"
    }
}

@Serializable
private data class HealthResponse(
    val status: String,
    val upstreamBaseUrl: String,
    val defaultModel: String,
    val localFallback: Boolean
)

@Serializable
private data class ErrorResponse(
    val error: String,
    val message: String
)

@Serializable
private data class ChatCompletionRequest(
    val model: String? = null,
    val messages: List<ChatMessageDto> = emptyList(),
    val temperature: Double? = null,
    @SerialName("max_tokens") val maxTokens: Int? = null,
    val stream: Boolean? = null
) {
    fun withDefaults(defaultModel: String): ChatCompletionRequest =
        copy(model = model?.takeIf { it.isNotBlank() } ?: defaultModel, stream = false)
}

@Serializable
private data class ChatMessageDto(
    val role: String,
    val content: String
)

@Serializable
private data class ChatCompletionResponse(
    val id: String,
    @SerialName("object") val objectType: String = "chat.completion",
    val created: Long,
    val model: String,
    val choices: List<ChatChoice>
)

@Serializable
private data class ChatChoice(
    val index: Int,
    val message: ChatMessageDto,
    @SerialName("finish_reason") val finishReason: String = "stop"
)

private fun fallbackCompletion(
    request: ChatCompletionRequest,
    upstreamStatus: HttpStatusCode?,
    upstreamError: Throwable?
): ChatCompletionResponse {
    val lastUser = request.messages.lastOrNull { it.role == "user" }?.content?.trim().orEmpty()
    val reason = upstreamError?.message ?: upstreamStatus?.description ?: "no upstream response"
    val content = buildString {
        append("Aether backend is running, but the upstream model did not answer")
        append(" (")
        append(reason.take(160))
        append(").")
        if (lastUser.isNotBlank()) {
            append("\n\nYour message reached the Kotlin backend: ")
            append(lastUser.take(500))
        }
    }
    return ChatCompletionResponse(
        id = "aether-local-${System.currentTimeMillis()}",
        created = System.currentTimeMillis() / 1000,
        model = request.model ?: "aether-local",
        choices = listOf(ChatChoice(index = 0, message = ChatMessageDto(role = "assistant", content = content)))
    )
}
