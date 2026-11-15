package com.ganzithon.sheep

import com.ganzithon.sheep.auth.*
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import tools.jackson.databind.PropertyNamingStrategies
import tools.jackson.databind.json.JsonMapper

class ControllerStatusTest {
    private val controller = AuthController(object : AuthService() {
        override fun register(request: RegisterRequest, idempotencyKey: String) =
            AuthResponse("access", "refresh", expiresIn = 900)

        override fun requestPasswordReset(request: PasswordResetRequest) = PasswordResetAccepted(true)

        override fun confirmPasswordReset(request: PasswordResetConfirmRequest) = Unit
    })
    private val mapper = JsonMapper.builder().findAndAddModules().propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).build()
    private val mvc = MockMvcBuilders.standaloneSetup(controller)
        .setMessageConverters(JacksonJsonHttpMessageConverter(mapper)).build()

    @Test
    fun `registration returns 201 when its service is implemented`() {
        mvc.perform(post("/api/v1/auth/register").header("Idempotency-Key", "key")
            .contentType(MediaType.APPLICATION_JSON).content("""{"email":"user@example.com","password":"12345678"}"""))
            .andExpect(status().isCreated)
    }

    @Test
    fun `reset acceptance returns 202`() {
        mvc.perform(post("/api/v1/auth/password-reset/request").contentType(MediaType.APPLICATION_JSON)
            .content("""{"email":"user@example.com"}"""))
            .andExpect(status().isAccepted).andExpect(jsonPath("$.accepted").value(true))
    }

    @Test
    fun `reset confirmation returns an empty 204`() {
        mvc.perform(post("/api/v1/auth/password-reset/confirm").contentType(MediaType.APPLICATION_JSON)
            .content("""{"token":"reset","new_password":"12345678"}"""))
            .andExpect(status().isNoContent).andExpect(content().string(""))
    }
}
