package net.youapps.translation_engines.ai

import kotlinx.coroutines.runBlocking
import net.youapps.translation_engines.EngineSettingsProvider
import net.youapps.translation_engines.TranslationEngine
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class ChatCompletionsEngineTest {
    private class TestSettings(
        private val url: String,
        private val key: String? = "sk-test",
        private val model: String? = "test-model",
        private val prompt: String? = null
    ) : EngineSettingsProvider {
        override fun getApiUrl(engine: TranslationEngine) = url
        override fun getApiKey(engine: TranslationEngine) = key
        override fun getSelectedModel(engine: TranslationEngine) = model
        override fun getSystemPrompt(engine: TranslationEngine) = prompt
    }

    private lateinit var server: MockWebServer
    private lateinit var engine: ChatCompletionsEngine

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        engine = ChatCompletionsEngine(TestSettings(server.url("/v1/").toString()))
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun sseChunksEmitGrowingTranslations() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    "data: {\"choices\":[{\"delta\":{\"role\":\"assistant\",\"content\":\"你好\"}}]}\n\n" +
                        "data: {\"choices\":[{\"delta\":{\"content\":\"，世界\"}}]}\n\n" +
                        "data: [DONE]\n\n"
                )
        )

        val results = mutableListOf<String>()
        engine.translateStream("hello world", "en", "zh").collect { results.add(it.translatedText) }
        assertEquals(listOf("你好", "你好，世界"), results)
    }

    @Test
    fun plainJsonResponseIsSupported() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"Bonjour\"}}]}")
        )

        assertEquals("Bonjour", engine.translate("Hello", "en", "fr").translatedText)
    }

    @Test
    fun apiErrorMessageIsExposed() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(429)
                .setBody("{\"error\":{\"message\":\"Insufficient balance\"}}")
        )

        val message = runCatching { engine.translate("Hello", "en", "fr") }.exceptionOrNull()?.message
        assertEquals(true, message?.contains("429"))
        assertEquals(true, message?.contains("Insufficient balance"))
    }

    @Test
    fun languageDirectionAndCustomPrompt() {
        val auto = engine.buildMessages("Hello", "", "zh")[0].content!!
        assertEquals(true, auto.startsWith(ChatCompletionsEngine.DEFAULT_SYSTEM_PROMPT))
        assertEquals(true, auto.contains("Automatically detect the source language"))
        assertEquals(true, auto.contains("into Chinese (zh)"))

        val custom = ChatCompletionsEngine(
            TestSettings("https://example.com/v1/", prompt = "Custom prompt")
        ).buildMessages("Hello", "en", "zh")[0].content!!
        assertEquals(true, custom.startsWith("Custom prompt"))
        assertEquals(true, custom.contains("from English (en)"))

        assertEquals("Hello", engine.buildMessages("Hello", "en", "zh")[1].content)
    }
}
