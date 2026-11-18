package com.yangsenunbam

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import tools.jackson.databind.json.JsonMapper
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

@SpringBootTest
@AutoConfigureMockMvc
class Be1OnboardingFlowTest {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var mapper: JsonMapper

    private fun register(): String {
        val email = "onboarding-${UUID.randomUUID()}@example.com"
        val body = """{"email":"$email","password":"strongPass!123"}"""
        val result = mvc.perform(post("/api/v1/auth/register")
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isCreated).andReturn()
        return mapper.readTree(result.response.contentAsString).path("access_token").asString()
    }

    private fun getAuth(path: String, token: String) = mvc.perform(get("/api/v1$path")
        .header("Authorization", "Bearer $token"))
    private fun putAuth(path: String, token: String, body: String) = mvc.perform(put("/api/v1$path")
        .header("Authorization", "Bearer $token")
        .contentType(MediaType.APPLICATION_JSON).content(body))

    @Test
    fun `registration proceeds through policy consent birth date profile and reminder`() {
        val jwt = register()
        getAuth("/bootstrap", jwt).andExpect(status().isOk)
            .andExpect(jsonPath("$.next_screen").value("ONBOARDING_CONSENT"))
            .andExpect(jsonPath("$.required_consents.terms_version").value("test-1"))
        getAuth("/me", jwt).andExpect(status().isForbidden)
        mvc.perform(get("/api/v1/legal-documents")).andExpect(status().isOk)
            .andExpect(jsonPath("$.items.length()").value(2))
        putAuth("/me/consents", jwt, """{"terms_version":"old","privacy_version":"test-1","terms_accepted":true,"privacy_accepted":true}""")
            .andExpect(status().isConflict).andExpect(jsonPath("$.error.code").value("CONSENT_VERSION_CHANGED"))
        val consent = """{"terms_version":"test-1","privacy_version":"test-1","terms_accepted":true,"privacy_accepted":true}"""
        putAuth("/me/consents", jwt, consent).andExpect(status().isNoContent)
        putAuth("/me/consents", jwt, consent).andExpect(status().isNoContent)
        getAuth("/bootstrap", jwt).andExpect(status().isOk)
            .andExpect(jsonPath("$.next_screen").value("ONBOARDING_BIRTH_DATE"))

        putAuth("/me/birth-date", jwt, """{"birth_date":"2018-02-01"}""")
            .andExpect(status().isUnprocessableContent).andExpect(jsonPath("$.error.code").value("AGE_NOT_ALLOWED"))
        val born = """{"birth_date":"2002-06-15"}"""
        putAuth("/me/birth-date", jwt, born).andExpect(status().isOk)
            .andExpect(jsonPath("$.edits_remaining").value(1))
            .andExpect(jsonPath("$.zodiac").value("gemini"))
        putAuth("/me/birth-date", jwt, born).andExpect(status().isOk)
            .andExpect(jsonPath("$.edits_remaining").value(1))
        putAuth("/me/birth-date", jwt, """{"birth_date":"2002-06-16"}""")
            .andExpect(status().isOk).andExpect(jsonPath("$.edits_remaining").value(0))
        putAuth("/me/birth-date", jwt, """{"birth_date":"2002-06-17"}""")
            .andExpect(status().isConflict).andExpect(jsonPath("$.error.code").value("BIRTH_DATE_EDIT_LIMIT"))

        getAuth("/me", jwt).andExpect(status().isOk)
            .andExpect(jsonPath("$.user.birth_date").value("2002-06-16"))
            .andExpect(jsonPath("$.user.stats.night_count").value(0))
        putAuth("/me/reminder", jwt, """{"enabled":true,"local_time":"21:15"}""")
            .andExpect(status().isOk).andExpect(jsonPath("$.local_time").value("21:15"))
        putAuth("/me/reminder", jwt, """{"enabled":true,"local_time":"21:17"}""")
            .andExpect(status().isUnprocessableContent)
        putAuth("/me/day-intro", jwt, """{"acknowledged":true}""").andExpect(status().isNoContent)
        getAuth("/bootstrap", jwt).andExpect(status().isOk)
            .andExpect(jsonPath("$.reminder.enabled").value(true))
    }

    @Test
    fun `KST day and night boundaries use the 04 to 21 definition`() {
        val clock = com.yangsenunbam.bootstrap.KstNightTime(java.time.Clock.systemUTC())
        val dates = listOf(
            Triple("2026-11-16T11:59:00Z", "day", "2026-11-15"), // 20:59 KST
            Triple("2026-11-16T12:00:00Z", "night", "2026-11-16"), // 21:00 KST
            Triple("2026-11-16T18:59:00Z", "night", "2026-11-16"), // 03:59 KST next day
            Triple("2026-11-16T19:00:00Z", "day", "2026-11-16"), // 04:00 KST next day
        )
        for ((instant, phase, date) in dates) {
            val actual = clock.at(java.time.Instant.parse(instant))
            assertEquals(phase, actual.phase.value)
            assertEquals(LocalDate.parse(date), actual.nightDate)
        }
        assertEquals(ZoneId.of("Asia/Seoul"), com.yangsenunbam.bootstrap.KstNightTime.KST)
    }
}
