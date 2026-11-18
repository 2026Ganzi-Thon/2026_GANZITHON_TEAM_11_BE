package com.yangsenunbam

import com.yangsenunbam.auth.*
import org.junit.jupiter.api.Test
import org.mockito.Answers
import org.mockito.Mockito.mock
import org.mockito.stubbing.Answer
import org.springframework.http.MediaType
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import tools.jackson.databind.PropertyNamingStrategies
import tools.jackson.databind.json.JsonMapper

/** Only verifies controller HTTP status/shape; no database or real AuthService logic. */
class ControllerStatusTest {
    // A default answer avoids passing Mockito's null matcher placeholders to
    // Kotlin non-null parameters during stubbing.
    private val service = mock(AuthService::class.java, Answer<Any?> { invocation ->
        when (invocation.method.name) {
            "register" -> AuthResponse("access", "refresh", expiresIn = 900)
            "requestPasswordReset" -> PasswordResetAccepted(true)
            "confirmPasswordReset" -> null // Kotlin Unit method compiles to void.
            else -> Answers.RETURNS_DEFAULTS.answer(invocation)
        }
    })
    private val mapper = JsonMapper.builder().findAndAddModules()
        .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).build()
    private val mvc = MockMvcBuilders.standaloneSetup(AuthController(service))
        .setMessageConverters(JacksonJsonHttpMessageConverter(mapper)).build()

    @Test
    fun `registration returns 201`() {
        mvc.perform(post("/api/v1/auth/register").header("Idempotency-Key", "key")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"email":"user@example.com","password":"12345678"}"""))
            .andExpect(status().isCreated)
    }

    @Test
    fun `password reset acceptance returns 202 when its service is implemented`() {
        mvc.perform(post("/api/v1/auth/password-reset/request").contentType(MediaType.APPLICATION_JSON)
            .content("""{"email":"user@example.com"}"""))
            .andExpect(status().isAccepted).andExpect(jsonPath("$.accepted").value(true))
    }

    @Test
    fun `password reset confirmation returns an empty 204 when its service is implemented`() {
        mvc.perform(post("/api/v1/auth/password-reset/confirm").contentType(MediaType.APPLICATION_JSON)
            .content("""{"token":"reset","new_password":"12345678"}"""))
            .andExpect(status().isNoContent).andExpect(content().string(""))
    }
}
