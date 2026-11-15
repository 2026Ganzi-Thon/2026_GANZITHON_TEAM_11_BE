package com.ganzithon.sheep

import com.ganzithon.sheep.security.CurrentUser
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping

@SpringBootTest
@AutoConfigureMockMvc
class ApiSkeletonTest {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var mappings: RequestMappingHandlerMapping

    @Test
    fun `all 47 specification endpoints are registered without extra product routes`() {
        val actual = mappings.handlerMethods.keys.flatMap { mapping ->
            mapping.patternValues.filter { it.startsWith("/api/v1") }.flatMap { path ->
                mapping.methodsCondition.methods.map { "${it.name} $path" }
            }
        }
        assertThat(actual).containsExactlyInAnyOrderElementsOf(requests().map { "${it.method} /api/v1${it.path}" })
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("requests")
    fun `valid requests reach an explicit unimplemented service`(case: ApiCase) {
        val path = case.path.replace(Regex("\\{[^}]+}"), "id")
        val request = request(HttpMethod.valueOf(case.method), "/api/v1$path").with(auth(admin = true))
        if (case.idempotent) request.header("Idempotency-Key", "request-1")
        if (case.body != null) request.contentType(MediaType.APPLICATION_JSON).content(case.body)
        if (case.path == "/music/search") request.param("q", "song")
        mvc.perform(request).andExpect(status().isNotImplemented)
            .andExpect(jsonPath("$.error.code").value("NOT_IMPLEMENTED"))
            .andExpect(jsonPath("$.error.fields").isMap)
            .andExpect(jsonPath("$.error.request_id").isString)
    }

    @Test
    fun `public endpoints do not require a login and protected endpoints reject unverified tokens`() {
        mvc.perform(get("/api/v1/legal-documents")).andExpect(status().isNotImplemented)
        mvc.perform(post("/api/v1/auth/login").header("Idempotency-Key", "login")
            .contentType(MediaType.APPLICATION_JSON).content("""{"email":"user@example.com","password":"password"}"""))
            .andExpect(status().isNotImplemented)
        mvc.perform(get("/api/v1/me")).andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"))
        mvc.perform(get("/api/v1/me").header("Authorization", "Bearer unverified"))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `admin and onboarding permissions are separate`() {
        mvc.perform(get("/api/v1/admin/cards").with(auth())).andExpect(status().isForbidden)
            .andExpect(jsonPath("$.error.code").value("ADMIN_REQUIRED"))
        mvc.perform(get("/api/v1/me").with(auth(complete = false))).andExpect(status().isForbidden)
            .andExpect(jsonPath("$.error.code").value("ONBOARDING_REQUIRED"))
        mvc.perform(get("/api/v1/bootstrap").with(auth(complete = false))).andExpect(status().isNotImplemented)
        mvc.perform(delete("/api/v1/me").with(auth(complete = false))).andExpect(status().isNotImplemented)
    }

    @Test
    fun `idempotency keys are required for all specified operations`() {
        for (case in requests().filter { it.idempotent }) {
            val path = case.path.replace(Regex("\\{[^}]+}"), "id")
            val req = request(HttpMethod.valueOf(case.method), "/api/v1$path").with(auth())
            case.body?.let { req.contentType(MediaType.APPLICATION_JSON).content(it) }
            mvc.perform(req).andExpect(status().isUnprocessableContent)
            mvc.perform(req.header("Idempotency-Key", " ")).andExpect(status().isUnprocessableContent)
        }
    }

    @Test
    fun `bodyless requests reject JSON payloads`() {
        for (case in requests().filter { it.body == null }) {
            val path = case.path.replace(Regex("\\{[^}]+}"), "id")
            val req = request(HttpMethod.valueOf(case.method), "/api/v1$path").with(auth(admin = true))
                .contentType(MediaType.APPLICATION_JSON).content("{}")
            if (case.idempotent) req.header("Idempotency-Key", "key")
            if (case.path == "/music/search") req.param("q", "song")
            mvc.perform(req).andExpect(status().isUnprocessableContent)
        }
    }

    @Test
    fun `my cards status and pagination queries follow the contract`() {
        for (status in listOf("pending", "approved", "rejected")) {
            mvc.perform(get("/api/v1/me/cards").param("status", status).with(auth())).andExpect(status().isNotImplemented)
        }
        mvc.perform(get("/api/v1/me/cards").param("status", "all").with(auth())).andExpect(status().isUnprocessableContent)
        mvc.perform(get("/api/v1/admin/cards").param("status", "approved").with(auth(admin = true)))
            .andExpect(status().isUnprocessableContent)
        for (limit in listOf("0", "101", "text")) {
            mvc.perform(get("/api/v1/sheep").param("limit", limit).with(auth())).andExpect(status().isUnprocessableContent)
        }
        mvc.perform(get("/api/v1/music/search").param("q", "song").param("limit", "51").with(auth()))
            .andExpect(status().isUnprocessableContent)
    }

    @Test
    fun `reminders use fifteen minute intervals and consistent enabled state`() {
        for (time in listOf("00:00", "09:15", "22:30", "23:45")) {
            putJson("/me/reminder", """{"enabled":true,"local_time":"$time"}""").andExpect(status().isNotImplemented)
        }
        for (body in listOf("""{"enabled":true,"local_time":"22:10"}""", """{"enabled":true,"local_time":"24:00"}""",
            """{"enabled":true,"local_time":null}""", """{"enabled":false,"local_time":"22:00"}""")) {
            putJson("/me/reminder", body).andExpect(status().isUnprocessableContent)
        }
    }

    @Test
    fun `null adoption is allowed but missing card id is rejected`() {
        putJson("/nights/id/adoption", """{"card_id":null}""").andExpect(status().isNotImplemented)
        for (body in listOf("{}", """{"card_id":" "}""", """{"card_id":42}""")) {
            putJson("/nights/id/adoption", body).andExpect(status().isUnprocessableContent)
        }
    }

    @Test
    fun `malformed JSON unknown fields missing primitives and wrong scalar types are rejected`() {
        putJson("/nights/id/fluff/card", "{").andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error.code").value("MALFORMED_JSON"))
        for (body in listOf("{}", """{"saved":null}""", """{"saved":"true"}""", """{"saved":true,"extra":1}""")) {
            putJson("/nights/id/fluff/card", body).andExpect(status().isUnprocessableContent)
        }
        putJson("/sessions/id/progress", """{"position":1.5,"furthest_position":2,"sequence":1,"writer_epoch":1}""")
            .andExpect(status().isUnprocessableContent)
        putJson("/sessions/id/progress", """{"position":4,"furthest_position":2,"sequence":1,"writer_epoch":1}""")
            .andExpect(status().isUnprocessableContent)
    }

    @Test
    fun `card input distinguishes text and songs without accepting extra content fields`() {
        val valid = listOf(
            """{"kind":"song","category_id":"career","content":{"track_id":"track","recommendation":"추천 문장"}}""",
            """{"kind":"song","category_id":"career","content":{"manual_track":{"title":"곡","artist":"가수"},"recommendation":"추천 문장"},"source_night_id":"n1"}""",
        )
        for (body in valid) postCard(body).andExpect(status().isNotImplemented)
        val invalid = listOf(
            """{"kind":"song","category_id":"career","content":{"recommendation":"추천 문장"}}""",
            """{"kind":"song","category_id":"career","content":{"track_id":"track","manual_track":{"title":"곡","artist":"가수"},"recommendation":"추천 문장"}}""",
            """{"kind":"text","category_id":"none","content":{"text":"글"}}""",
            """{"kind":"text","category_id":"career","content":{"track_id":"track"}}""",
            """{"kind":"text","category_id":"career","content":{"text":"글","lyrics":"가사"}}""",
        )
        for (body in invalid) postCard(body).andExpect(status().isUnprocessableContent)
    }

    @Test
    fun `text limits count Unicode code points and password minimum is eight`() {
        postCard("""{"kind":"text","category_id":"career","content":{"text":"${"🐑".repeat(300)}"}}""")
            .andExpect(status().isNotImplemented)
        postCard("""{"kind":"text","category_id":"career","content":{"text":"${"🐑".repeat(301)}"}}""")
            .andExpect(status().isUnprocessableContent)
        mvc.perform(post("/api/v1/auth/register").header("Idempotency-Key", "key").contentType(MediaType.APPLICATION_JSON)
            .content("""{"email":"user@example.com","password":"12345678"}""")).andExpect(status().isNotImplemented)
        mvc.perform(post("/api/v1/auth/register").header("Idempotency-Key", "key").contentType(MediaType.APPLICATION_JSON)
            .content("""{"email":"user@example.com","password":"1234567"}""")).andExpect(status().isUnprocessableContent)
    }

    @Test
    fun `moderation requires revision and a reason only when rejected`() {
        putJson("/admin/cards/id/moderation", """{"decision":"approved","reason":null,"revision":1}""", admin = true)
            .andExpect(status().isNotImplemented)
        for (body in listOf("""{"decision":"rejected","reason":null,"revision":1}""",
            """{"decision":"approved","reason":"반려","revision":1}""", """{"decision":"approved","reason":null}""")) {
            putJson("/admin/cards/id/moderation", body, admin = true).andExpect(status().isUnprocessableContent)
        }
    }

    private fun postCard(body: String) = mvc.perform(post("/api/v1/cards").with(auth())
        .header("Idempotency-Key", "key").contentType(MediaType.APPLICATION_JSON).content(body))
    private fun putJson(path: String, body: String, admin: Boolean = false) = mvc.perform(put("/api/v1$path")
        .with(auth(admin = admin)).contentType(MediaType.APPLICATION_JSON).content(body))

    companion object {
        @JvmStatic
        fun requests(): List<ApiCase> = listOf(
            ApiCase("POST", "/auth/register", """{"email":"user@example.com","password":"12345678"}""", true),
            ApiCase("POST", "/auth/login", """{"email":"user@example.com","password":"12345678"}""", true),
            ApiCase("POST", "/auth/refresh", """{"refresh_token":"refresh"}""", true),
            ApiCase("GET", "/me"),
            ApiCase("POST", "/auth/logout", """{"refresh_token":"refresh"}"""),
            ApiCase("POST", "/auth/password-reset/request", """{"email":"user@example.com"}"""),
            ApiCase("POST", "/auth/password-reset/confirm", """{"token":"reset","new_password":"12345678"}"""),
            ApiCase("GET", "/legal-documents"),
            ApiCase("GET", "/bootstrap"),
            ApiCase("PUT", "/me/consents", """{"terms_version":"1","privacy_version":"1","terms_accepted":true,"privacy_accepted":true}"""),
            ApiCase("PUT", "/me/day-intro", """{"acknowledged":true}"""),
            ApiCase("PUT", "/me/birth-date", """{"birth_date":"2002-06-15"}"""),
            ApiCase("GET", "/categories"),
            ApiCase("PUT", "/nights/current/worry", """{"category_id":"career","worry_text":"내일 면접"}"""),
            ApiCase("POST", "/nights/{night_id}/support/continue"),
            ApiCase("POST", "/nights/{night_id}/sessions", """{"sheep_count":16}""", true),
            ApiCase("GET", "/sessions/{session_id}/feed"),
            ApiCase("POST", "/sessions/{session_id}/resume", idempotent = true),
            ApiCase("PUT", "/sessions/{session_id}/progress", """{"position":4,"furthest_position":7,"sequence":12,"writer_epoch":1}"""),
            ApiCase("PUT", "/sessions/{session_id}/pauses/{item_id}", """{"action":"entrusted"}"""),
            ApiCase("POST", "/sessions/{session_id}/finish", """{"reason":"leave","position":4,"furthest_position":7,"sequence":13,"writer_epoch":1}""", true),
            ApiCase("PUT", "/sessions/{session_id}/closing-view", """{"viewed":true}"""),
            ApiCase("PUT", "/nights/{night_id}/writing-offer", """{"choice":"morning"}"""),
            ApiCase("PUT", "/cards/{card_id}/yarn", """{"sent":true}"""),
            ApiCase("PUT", "/nights/{night_id}/fluff/{card_id}", """{"saved":true}"""),
            ApiCase("PUT", "/nights/{night_id}/songs/{card_id}", """{"saved":true}"""),
            ApiCase("GET", "/morning"),
            ApiCase("PUT", "/nights/{night_id}/adoption", """{"card_id":null}"""),
            ApiCase("PUT", "/nights/{night_id}/reflection", """{"answer":"smaller"}"""),
            ApiCase("GET", "/fortune/today"),
            ApiCase("GET", "/sheep"),
            ApiCase("GET", "/sheep/{sheep_id}"),
            ApiCase("GET", "/stickers"),
            ApiCase("PUT", "/sheep/{sheep_id}/stickers", """{"if_version":1,"placements":[{"instance_id":"p1","sticker_id":"st1","x":0.4,"y":0.6,"rotation":0,"scale":1}]}"""),
            ApiCase("GET", "/me/yarn-summary"),
            ApiCase("PUT", "/me/yarn-summary/{summary_id}/seen"),
            ApiCase("POST", "/cards", """{"kind":"text","category_id":"career","content":{"text":"오늘도 괜찮아"}}""", true),
            ApiCase("GET", "/music/search"),
            ApiCase("GET", "/me/cards"),
            ApiCase("DELETE", "/me/cards/{card_id}"),
            ApiCase("POST", "/me/cards/{card_id}/resubmit", """{"kind":"text","category_id":"career","content":{"text":"수정한 글"}}""", true),
            ApiCase("GET", "/admin/cards"),
            ApiCase("PUT", "/admin/cards/{card_id}/moderation", """{"decision":"rejected","reason":"추천 글 수정","revision":1}"""),
            ApiCase("PUT", "/me/reminder", """{"enabled":true,"local_time":"22:15"}"""),
            ApiCase("GET", "/me/worries"),
            ApiCase("DELETE", "/nights/{night_id}/worry"),
            ApiCase("DELETE", "/me"),
        )
    }
}

data class ApiCase(val method: String, val path: String, val body: String? = null, val idempotent: Boolean = false) {
    override fun toString(): String = "$method $path"
}

fun auth(admin: Boolean = false, complete: Boolean = true) = authentication(UsernamePasswordAuthenticationToken(
    CurrentUser("user-1", complete), null, listOf(SimpleGrantedAuthority(if (admin) "ROLE_ADMIN" else "ROLE_USER")),
))
