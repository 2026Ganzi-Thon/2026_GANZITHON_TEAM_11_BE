package com.yangsenunbam.night

import com.yangsenunbam.common.ApiException
import com.yangsenunbam.common.ErrorCode
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.*
import org.springframework.test.web.client.response.MockRestResponseCreators.*
import org.springframework.web.client.RestClient
import java.net.SocketTimeoutException

class WorrySafetyGatewayTest {
    private val endpoint = "https://safety.example.invalid/check"

    private fun response(body: String): WorryVerdict {
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        server.expect(requestTo(endpoint))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header("Authorization", "Bearer fixture"))
            .andExpect(content().json("""{"text":"fixture worry","purpose":"night_worry"}"""))
            .andRespond(withSuccess(body, MediaType.APPLICATION_JSON))
        try {
            return HttpWorrySafetyGateway(endpoint, "fixture", builder.build()).check("fixture worry")
        } finally {
            server.verify()
        }
    }

    @Test fun `explicit safe decision is accepted`() {
        assertEquals(WorryVerdict(false), response("""{"needsSupport":false}"""))
    }

    @Test fun `support decision preserves complete resources`() {
        val result = response("""{"needsSupport":true,"support":{"message":"fixture guidance","resources":[{"label":"contacts","action":"open_contacts","target":null}]}}""")
        assertTrue(result.needsSupport)
        assertEquals("fixture guidance", result.support!!.message)
        assertEquals(1, result.support.resources.size)
    }

    @Test fun `missing malformed or coerced decisions fail closed`() {
        for (body in listOf("{}", "null", "[]", "invalid", """{"needsSupport":null}""",
            """{"needsSupport":"false"}""", """{"needsSupport":0}""",
            """{"needsSupport":true}""",
            """{"needsSupport":true,"support":{"message":"ok","resources":[]}}""",
            """{"needsSupport":true,"support":{"message":"ok","resources":[{"label":"","action":"open_contacts","target":null}]}}""",
            """{"needsSupport":true,"support":{"message":"ok","resources":[{"label":"link","action":"open_url","target":null}]}}""")) {
            val error = assertThrows(ApiException::class.java, { response(body) }, body)
            assertEquals(ErrorCode.SAFETY_CHECK_UNAVAILABLE, error.code)
        }
    }

    @Test fun `invalid endpoint and missing credential never send a request`() {
        for ((url, token) in listOf("" to "fixture", "http://safety.example.invalid" to "fixture",
            "https://user:password@safety.example.invalid" to "fixture", endpoint to "")) {
            val builder = RestClient.builder()
            val server = MockRestServiceServer.bindTo(builder).build()
            val error = assertThrows(ApiException::class.java) {
                HttpWorrySafetyGateway(url, token, builder.build()).check("private fixture")
            }
            assertEquals(ErrorCode.SAFETY_CHECK_UNAVAILABLE, error.code)
            assertFalse(error.message.orEmpty().contains("private fixture"))
            server.verify()
        }
    }

    @Test fun `upstream failures and timeouts fail closed without leaking response`() {
        for (reply in listOf(withStatus(HttpStatus.INTERNAL_SERVER_ERROR).body("private upstream detail"),
            withStatus(HttpStatus.FOUND).header("Location", "https://other.example.invalid"),
            withException(SocketTimeoutException("private connection detail")))) {
            val builder = RestClient.builder()
            val server = MockRestServiceServer.bindTo(builder).build()
            server.expect(requestTo(endpoint)).andRespond(reply)
            val error = assertThrows(ApiException::class.java) {
                HttpWorrySafetyGateway(endpoint, "fixture", builder.build()).check("private fixture")
            }
            assertEquals(ErrorCode.SAFETY_CHECK_UNAVAILABLE, error.code)
            assertFalse(error.message.orEmpty().contains("private"))
            server.verify()
        }
    }
}
