package io.github.droidkaigi.feeder.data

import io.github.droidkaigi.feeder.data.response.InstantSerializer
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngineConfig
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.features.defaultRequest
import io.ktor.client.features.json.JsonFeature
import io.ktor.client.features.json.serializer.KotlinxSerializer
import io.ktor.client.features.logging.LogLevel
import io.ktor.client.features.logging.Logger
import io.ktor.client.features.logging.Logging
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.put
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual

class NetworkService private constructor(val httpClient: HttpClient) {

    suspend inline fun <reified T : Any> get(url: String): T {
        return httpClient.get(url)
    }

    suspend inline fun <reified T> post(
        urlString: String,
        block: HttpRequestBuilder.() -> Unit = {}
    ): T = httpClient.post<T>(urlString, block)

    suspend inline fun <reified T> put(
        urlString: String,
        block: HttpRequestBuilder.() -> Unit = {}
    ): T = httpClient.put<T>(urlString, block)

    companion object {
        fun <T> create(
            engineFactory: HttpClientEngineFactory<T>,
            userDataStore: UserDataStore,
            block: T.() -> Unit = {},
        ): NetworkService where T : HttpClientEngineConfig {
            val httpClient = HttpClient(engineFactory) {
                engine(block)
                install(JsonFeature) {
                    serializer = KotlinxSerializer(
                        Json {
                            serializersModule = SerializersModule {
                                contextual(InstantSerializer)
                            }
                            ignoreUnknownKeys = true
                        }
                    )
                }
                install(Logging) {
                    logger = object : Logger {
                        override fun log(message: String) {
                            io.github.droidkaigi.feeder.Logger.d(message)
                        }
                    }
                    level = LogLevel.ALL
                }
                defaultRequest {
                    headers {
                        userDataStore.idToken.value?.let {
                            set("Authorization", "Bearer $it")
                        }
                    }
                }
            }
            return NetworkService(httpClient)
        }
    }
}
