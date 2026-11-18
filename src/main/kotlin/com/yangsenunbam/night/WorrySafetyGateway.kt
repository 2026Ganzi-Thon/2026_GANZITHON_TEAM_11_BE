package com.yangsenunbam.night

import com.yangsenunbam.common.*
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient

/**
 * Internal-only safety service. NEVER log worry text or send it to an unapproved third party.
 * Missing service, invalid response and transport errors all fail closed with HTTP 503.
 * The team must approve the remote contract, service, and support resources before release.
 */
interface WorrySafetyGateway { fun check(worry: String): WorryVerdict }

data class WorryVerdict(val needsSupport: Boolean, val support: SupportResponse? = null)

@Component
class HttpWorrySafetyGateway(
    @Value("\${app.safety.endpoint:}") private val endpoint: String,
    @Value("\${app.safety.service-token:}") private val serviceToken: String,
) : WorrySafetyGateway {
    private val client = RestClient.create()
    override fun check(worry: String): WorryVerdict {
        if (endpoint.isBlank() || !endpoint.startsWith("https://") || serviceToken.isBlank()) {
            throw ApiException(ErrorCode.SAFETY_CHECK_UNAVAILABLE, "안전 검사가 준비되지 않았어요.")
        }
        val result = try {
            val request = client.post().uri(endpoint).contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer $serviceToken")
                .body(mapOf("text" to worry, "purpose" to "night_worry"))
            request.retrieve().body(WorryVerdict::class.java)
        } catch (_: Exception) { null }
        if (result == null || (result.needsSupport &&
                (result.support?.message.isNullOrBlank() || result.support?.resources.isNullOrEmpty()))) {
            throw ApiException(ErrorCode.SAFETY_CHECK_UNAVAILABLE, "안전 검사를 완료하지 못했어요.")
        }
        return result
    }
}
