package com.yangsenunbam.night

import com.yangsenunbam.common.*
import org.springframework.beans.factory.annotation.Value
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.http.HttpClient
import java.time.Duration

/**
 * Internal-only safety service. NEVER log worry text or send it to an unapproved third party.
 * Missing service, invalid response and transport errors all fail closed with HTTP 503.
 * The team must approve the remote contract, service, and support resources before release.
 */
interface WorrySafetyGateway { fun check(worry: String): WorryVerdict }

data class WorryVerdict(val needsSupport: Boolean, val support: SupportResponse? = null)

@Component
class HttpWorrySafetyGateway internal constructor(
    private val endpoint: String,
    private val serviceToken: String,
    private val client: RestClient,
) : WorrySafetyGateway {
    @Autowired
    constructor(
        @Value("\${app.safety.endpoint:}") endpoint: String,
        @Value("\${app.safety.service-token:}") serviceToken: String,
    ) : this(endpoint, serviceToken, createClient())

    private val mapper = JsonMapper.builder().build()

    companion object {
        private fun createClient(): RestClient {
            val http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build()
            val factory = JdkClientHttpRequestFactory(http).apply {
                setReadTimeout(Duration.ofSeconds(5))
            }
            return RestClient.builder().requestFactory(factory).build()
        }
    }

    override fun check(worry: String): WorryVerdict {
        val uri = runCatching { URI(endpoint) }.getOrNull()
        if (uri?.scheme != "https" || uri.host.isNullOrBlank() || uri.userInfo != null ||
            uri.fragment != null || serviceToken.isBlank()) {
            throw ApiException(ErrorCode.SAFETY_CHECK_UNAVAILABLE, "안전 검사가 준비되지 않았어요.")
        }
        val result = try {
            val request = client.post().uri(endpoint).contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer $serviceToken")
                .body(mapOf("text" to worry, "purpose" to "night_worry"))
            val response = request.retrieve().toEntity(String::class.java)
            if (!response.statusCode.is2xxSuccessful) return unavailable()
            val tree = mapper.readTree(response.body ?: return unavailable())
            // Keep the existing external camelCase contract. Missing/null/coerced verdicts
            // must never be interpreted as an explicit safe decision.
            val decision = tree.path("needsSupport")
            if (!decision.isBoolean) return unavailable()
            val needsSupport = decision.asBoolean()
            if (!needsSupport) WorryVerdict(false)
            else {
                val support = tree.path("support")
                val message = support.path("message")
                val resources = support.path("resources")
                if (!message.isString || message.asString().isBlank() || !resources.isArray || resources.isEmpty) {
                    return unavailable()
                }
                val parsed = (0 until resources.size()).map { index ->
                    val resource = resources.path(index)
                    val label = resource.path("label")
                    val action = SupportAction.entries.firstOrNull { it.value == resource.path("action").asString() }
                        ?: return unavailable()
                    val targetNode = resource.path("target")
                    if (!label.isString || label.asString().isBlank() || (!targetNode.isNull && !targetNode.isString)) {
                        return unavailable()
                    }
                    val target = if (targetNode.isNull) null else targetNode.asString()
                    if (action != SupportAction.OPEN_CONTACTS && target.isNullOrBlank()) return unavailable()
                    SupportResource(label.asString(), action, target)
                }
                WorryVerdict(true, SupportResponse(message.asString(), parsed))
            }
        } catch (_: Exception) { null }
        if (result == null) {
            throw ApiException(ErrorCode.SAFETY_CHECK_UNAVAILABLE, "안전 검사를 완료하지 못했어요.")
        }
        return result
    }

    private fun unavailable(): Nothing = throw ApiException(ErrorCode.SAFETY_CHECK_UNAVAILABLE, "안전 검사를 완료하지 못했어요.")
}
