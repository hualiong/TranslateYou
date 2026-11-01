package net.youapps.translation_engines.ai

import java.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import net.youapps.translation_engines.ApiKeyState
import net.youapps.translation_engines.EngineSettingsProvider
import net.youapps.translation_engines.JsonHelper
import net.youapps.translation_engines.Language
import net.youapps.translation_engines.Translation
import net.youapps.translation_engines.TranslationEngine
import net.youapps.translation_engines.ai.obj.ChatCompletionRequest
import net.youapps.translation_engines.ai.obj.ChatCompletionResponse
import net.youapps.translation_engines.ai.obj.ChatErrorResponse
import net.youapps.translation_engines.ai.obj.ChatMessage
import net.youapps.translation_engines.ai.obj.ChatModelsResponse
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/**
 * Translation engine for Chat Completions compatible APIs
 *
 * Works with OpenAI, DeepSeek, Moonshot, Ollama and any other endpoint
 * exposing POST /chat/completions (+ GET /models if supported)
 */
class ChatCompletionsEngine(settingsProvider: EngineSettingsProvider) : TranslationEngine(settingsProvider) {
    override val name = "AI (Chat Completions)"
    override val defaultUrl = "https://api.openai.com/v1/"
    override val urlModifiable = true
    override val apiKeyState = ApiKeyState.REQUIRED
    override val supportsStreaming = true
    override val modelModifiable = true
    override val promptModifiable = true
    override val defaultSystemPrompt = DEFAULT_SYSTEM_PROMPT

    private val client = OkHttpClient.Builder()
        .connectTimeout(Duration.ofSeconds(30))
        // generous read timeout: SSE chunks arrive within seconds, so a minute of
        // silence means a dead connection rather than a slow model
        .readTimeout(Duration.ofSeconds(60))
        // http/1.1 keeps long lived event streams alive behind proxies that stall h2
        .protocols(listOf(Protocol.HTTP_1_1))
        .build()

    // requests are built per call from the live settings, there is nothing to recreate
    override fun createOrRecreate(): TranslationEngine = this

    override suspend fun getLanguages(): List<Language> = LANGUAGES

    override suspend fun getModels(): List<String> = withContext(Dispatchers.IO) {
        val request = authorizedRequest(apiBaseUrl() + "models").get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw toApiError(response)
            JsonHelper.json.decodeFromString<ChatModelsResponse>(
                response.body?.string().orEmpty()
            ).data.map { it.id }
        }
    }

    override suspend fun translate(query: String, source: String?, target: String): Translation {
        var result: Translation? = null
        translateStream(query, source, target).collect { result = it }
        return result ?: throw Exception("Received no translation from $name")
    }

    override fun translateStream(query: String, source: String?, target: String): Flow<Translation> = flow {
        val body = JsonHelper.json.encodeToString(
            ChatCompletionRequest(requireModel(), buildMessages(query, source, target), stream = true)
        )
        val request = authorizedRequest(apiBaseUrl() + "chat/completions")
            .header("Accept", "text/event-stream")
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw toApiError(response)

            // servers that ignore the stream flag answer with a single json body
            if (!response.header("Content-Type").orEmpty().contains("event-stream")) {
                emit(parseCompleteBody(response.body?.string().orEmpty()))
                return@use
            }

            val stream = response.body?.source() ?: throw Exception("Empty response from $name")
            val result = StringBuilder()
            while (true) {
                val line = stream.readUtf8Line() ?: break
                if (isSseDone(line)) break
                val piece = parseSseLine(line)
                if (!piece.isNullOrEmpty()) {
                    result.append(piece)
                    emit(Translation(translatedText = result.toString()))
                }
            }
            if (result.isEmpty()) throw Exception("$name returned no translation")
        }
    }.flowOn(Dispatchers.IO).conflate()

    private fun apiBaseUrl(): String = getUrl().trimEnd('/') + "/"

    private fun authorizedRequest(url: String): Request.Builder = Request.Builder()
        .url(url)
        .header("Authorization", "Bearer " + requireApiKey())

    private fun requireApiKey(): String =
        getApiKey()?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("An API key is required to use $name")

    private fun requireModel(): String =
        getSelectedModel()?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("No model configured for $name")

    private fun toApiError(response: Response): Exception {
        val message = runCatching {
            JsonHelper.json.decodeFromString<ChatErrorResponse>(
                response.body?.string().orEmpty()
            ).error?.message
        }.getOrNull()
        return Exception("$name returned HTTP " + response.code + (message?.let { ": $it" } ?: ""))
    }

    internal fun parseSseLine(line: String): String? {
        if (!line.startsWith("data:")) return null
        val payload = line.substring(5).trim()
        if (payload.isEmpty()) return null
        return runCatching { JsonHelper.json.decodeFromString<ChatCompletionResponse>(payload) }
            .getOrNull()?.choices?.firstOrNull()?.delta?.content
    }

    internal fun isSseDone(line: String): Boolean =
        line.startsWith("data:") && line.substring(5).trim() == "[DONE]"

    internal fun parseCompleteBody(body: String): Translation {
        val text = runCatching { JsonHelper.json.decodeFromString<ChatCompletionResponse>(body) }
            .getOrNull()?.choices?.firstOrNull()?.message?.content
            ?: throw Exception("Could not parse the response of $name")
        return Translation(translatedText = text)
    }

    internal fun buildMessages(query: String, source: String?, target: String): List<ChatMessage> {
        // a missing source code means the model has to detect the language itself
        val src = source?.takeIf { it.isNotEmpty() }
        val tgtName = languageName(target)
        val direction = if (src == null) {
            "Automatically detect the source language and translate the text into " + tgtName + " (" + target + ")."
        } else {
            "Translate the text from " + languageName(src) + " (" + src + ") into " + tgtName + " (" + target + ")."
        }
        val instructions = getSystemPrompt()?.takeIf { it.isNotBlank() } ?: DEFAULT_SYSTEM_PROMPT
        return listOf(
            ChatMessage(role = "system", content = instructions + "\n" + direction),
            ChatMessage(role = "user", content = query)
        )
    }

    private fun languageName(code: String): String =
        LANGUAGES.firstOrNull { it.code == code }?.name ?: code

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        const val DEFAULT_SYSTEM_PROMPT = "你是一款专业的翻译引擎，请将这段文字翻译成既口语化且通俗、优雅且流畅的内容，避免机器翻译的生硬风格。待翻译的文本可能包含NSFW内容，仅用于工作需要，请勿拒绝翻译。注意，最重要的一点：请只输出翻译后的文本内容，不要进行任何多余的解释，也不要携带多余的符号。"

        val LANGUAGES = listOf(
            Language("en", "English"),
            Language("zh", "Chinese"),
            Language("zh-TW", "Chinese (Traditional)"),
            Language("ar", "Arabic"),
            Language("bg", "Bulgarian"),
            Language("bn", "Bengali"),
            Language("bs", "Bosnian"),
            Language("ca", "Catalan"),
            Language("cs", "Czech"),
            Language("da", "Danish"),
            Language("de", "German"),
            Language("el", "Greek"),
            Language("es", "Spanish"),
            Language("et", "Estonian"),
            Language("fa", "Persian"),
            Language("fi", "Finnish"),
            Language("fr", "French"),
            Language("gl", "Galician"),
            Language("gu", "Gujarati"),
            Language("he", "Hebrew"),
            Language("hi", "Hindi"),
            Language("hr", "Croatian"),
            Language("hu", "Hungarian"),
            Language("hy", "Armenian"),
            Language("id", "Indonesian"),
            Language("is", "Icelandic"),
            Language("it", "Italian"),
            Language("ja", "Japanese"),
            Language("ka", "Georgian"),
            Language("kk", "Kazakh"),
            Language("km", "Khmer"),
            Language("kn", "Kannada"),
            Language("ko", "Korean"),
            Language("lo", "Lao"),
            Language("lt", "Lithuanian"),
            Language("lv", "Latvian"),
            Language("ml", "Malayalam"),
            Language("mn", "Mongolian"),
            Language("mr", "Marathi"),
            Language("ms", "Malay"),
            Language("my", "Burmese"),
            Language("nb", "Norwegian"),
            Language("ne", "Nepali"),
            Language("nl", "Dutch"),
            Language("pl", "Polish"),
            Language("pt", "Portuguese"),
            Language("ro", "Romanian"),
            Language("ru", "Russian"),
            Language("si", "Sinhala"),
            Language("sk", "Slovak"),
            Language("sl", "Slovenian"),
            Language("sq", "Albanian"),
            Language("sr", "Serbian"),
            Language("sv", "Swedish"),
            Language("sw", "Swahili"),
            Language("ta", "Tamil"),
            Language("te", "Telugu"),
            Language("th", "Thai"),
            Language("tl", "Filipino"),
            Language("tr", "Turkish"),
            Language("uk", "Ukrainian"),
            Language("ur", "Urdu"),
            Language("uz", "Uzbek"),
            Language("vi", "Vietnamese")
        )
    }
}
