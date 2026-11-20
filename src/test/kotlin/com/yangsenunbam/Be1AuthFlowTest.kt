package com.yangsenunbam

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

@SpringBootTest
@AutoConfigureMockMvc
class Be1AuthFlowTest {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var mapper: JsonMapper

    private fun authPost(path: String, key: String, json: String) = mvc.perform(
        post("/api/v1/auth/$path")
            .header("Idempotency-Key", key)
            .contentType(MediaType.APPLICATION_JSON).content(json),
    )

    @Test
    fun `register login refresh and logout persist and enforce token rotation`() {
        val email = "u-${UUID.randomUUID()}@example.com"
        val body = """{"email":"$email","password":"Passw0rd!123"}"""
        val key = UUID.randomUUID().toString()
        val first = authPost("register", key, body).andExpect(status().isCreated).andReturn().response.contentAsString
        val firstJson = mapper.readTree(first)
        val firstAccess = firstJson.path("access_token").asString()
        val firstRefresh = firstJson.path("refresh_token").asString()
        assertEquals(900L, firstJson.path("expires_in").asLong())
        assertTrue(firstAccess.split('.').size == 3)
        assertEquals(first, authPost("register", key, body).andExpect(status().isCreated).andReturn().response.contentAsString)
        authPost("register", key, """{"email":"$email","password":"OtherPass!456"}""")
            .andExpect(status().isConflict).andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_CONFLICT"))
        authPost("register", UUID.randomUUID().toString(), body)
            .andExpect(status().isConflict).andExpect(jsonPath("$.error.code").value("EMAIL_ALREADY_USED"))
        authPost("login", UUID.randomUUID().toString(), """{"email":"$email","password":"wrongpass"}""")
            .andExpect(status().isUnauthorized).andExpect(jsonPath("$.error.code").value("INVALID_CREDENTIALS"))

        // Authentication succeeds before onboarding; other product endpoints remain protected.
        mvc.perform(get("/api/v1/bootstrap").header("Authorization", "Bearer $firstAccess"))
            .andExpect(status().isOk).andExpect(jsonPath("$.next_screen").value("ONBOARDING_CONSENT"))
        mvc.perform(get("/api/v1/me").header("Authorization", "Bearer $firstAccess"))
            .andExpect(status().isForbidden).andExpect(jsonPath("$.error.code").value("ONBOARDING_REQUIRED"))

        val refreshBody = """{"refresh_token":"$firstRefresh"}"""
        val refreshKey = UUID.randomUUID().toString()
        val refreshResponse = authPost("refresh", refreshKey, refreshBody).andReturn().response
        if (refreshResponse.status != 200) {
            // Never print JWTs or refresh tokens when diagnosing test failures.
            val errorCode = runCatching {
                mapper.readTree(refreshResponse.contentAsString).path("error").path("code").asString()
            }.getOrDefault("unknown")
            throw AssertionError("Refresh returned HTTP ${refreshResponse.status}, error_code=$errorCode")
        }
        val rotated = refreshResponse.contentAsString
        val rotatedJson = mapper.readTree(rotated)
        val nextAccess = rotatedJson.path("access_token").asString()
        val nextRefresh = rotatedJson.path("refresh_token").asString()
        assertNotEquals(firstRefresh, nextRefresh)
        assertEquals(rotated, authPost("refresh", refreshKey, refreshBody)
            .andExpect(status().isOk).andReturn().response.contentAsString)
        mvc.perform(get("/api/v1/bootstrap").header("Authorization", "Bearer $firstAccess"))
            .andExpect(status().isUnauthorized)
        authPost("refresh", UUID.randomUUID().toString(), refreshBody)
            .andExpect(status().isUnauthorized).andExpect(jsonPath("$.error.code").value("INVALID_REFRESH_TOKEN"))

        mvc.perform(post("/api/v1/auth/logout").header("Authorization", "Bearer $nextAccess")
            .contentType(MediaType.APPLICATION_JSON).content("""{"refresh_token":"$nextRefresh"}"""))
            .andExpect(status().isNoContent)
        mvc.perform(post("/api/v1/auth/logout").header("Authorization", "Bearer $nextAccess")
            .contentType(MediaType.APPLICATION_JSON).content("""{"refresh_token":"$nextRefresh"}"""))
            .andExpect(status().isNoContent)
        mvc.perform(get("/api/v1/bootstrap").header("Authorization", "Bearer $nextAccess"))
            .andExpect(status().isUnauthorized)
    }
}
