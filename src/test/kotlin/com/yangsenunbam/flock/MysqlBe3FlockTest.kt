package com.yangsenunbam.flock

import com.jayway.jsonpath.JsonPath
import com.yangsenunbam.security.CurrentUser
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.json.JsonCompareMode
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import org.testcontainers.mysql.MySQLContainer
import java.sql.Date
import java.time.Instant
import java.time.LocalDate

@Tag("mysql")
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("mysql")
class MysqlBe3FlockTest {
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var mvc: MockMvc

    private val me = "be3-user-1"
    private val other = "be3-user-2"
    private val t0 = Instant.parse("2026-11-10T22:00:00Z").toEpochMilli()
    private val d9 = LocalDate.parse("2026-11-09")
    private val d10 = LocalDate.parse("2026-11-10")
    private val d11 = LocalDate.parse("2026-11-11")

    @BeforeEach
    fun clean() {
        listOf("feed_resume_receipts", "adopted_sheep", "night_reactions", "feed_card_views", "feed_items",
            "feed_session_progress", "night_feed_state", "feed_card_yarn", "feed_card_history", "feed_card_catalog",
            "stickers").forEach { jdbc.update("DELETE FROM $it") }
        for (table in listOf("be1_session_replays", "be1_sessions", "be1_nights", "user_onboarding")) {
            jdbc.update("DELETE FROM $table WHERE user_id IN (?, ?)", me, other)
        }
        jdbc.update("DELETE FROM auth_users WHERE id IN (?, ?)", me, other)
        for (id in listOf(me, other)) {
            jdbc.update("INSERT INTO auth_users(id, email, password_hash, onboarding_complete, created_at_ms) VALUES (?, ?, ?, TRUE, ?)",
                id, "$id@example.test", "test-fixture-not-a-login-hash", 0)
        }
        textCard("c_1", "seed", "먼저 지나간 양", "empathy", "career", "나도 면접 전날에 잠이 안 왔어.")
    }

    @Test
    fun `GET stickers는 정렬 순서대로 전체를 돌려준다`() {
        sticker("st_3", "별", 3)
        sticker("st_1", "달", 1)
        sticker("st_2", "구름", 2)

        mvc.perform(asUser(me, get("/api/v1/stickers")))
            .andExpect(status().isOk)
            .andExpect(content().json(
                """
                {"items":[
                  {"id":"st_1","label":"달","asset_url":"https://cdn.example.com/st_1.png"},
                  {"id":"st_2","label":"구름","asset_url":"https://cdn.example.com/st_2.png"},
                  {"id":"st_3","label":"별","asset_url":"https://cdn.example.com/st_3.png"}
                ]}
                """, JsonCompareMode.STRICT))
    }

    @Test
    fun `GET sheep는 Sheep 전체 필드를 돌려준다`() {
        night("n_1", me, d10, "career", "걱정")
        sheep("sh_1", me, "n_1", d10, "c_1", null, 1, 16, 3,
            """[{"instance_id":"pl_1","sticker_id":"st_1","x":0.4,"y":0.6,"rotation":0,"scale":1}]""")

        mvc.perform(asUser(me, get("/api/v1/sheep")))
            .andExpect(status().isOk)
            .andExpect(content().json(
                """
                {"items":[{
                  "id":"sh_1","night_id":"n_1","night_date":"2026-11-10","card_id":"c_1",
                  "color_key":null,"created_at":"2026-11-10T22:00:00Z","version":3,
                  "placements":[{"instance_id":"pl_1","sticker_id":"st_1","x":0.4,"y":0.6,"rotation":0,"scale":1}]}],
                 "next_cursor":null}
                """, JsonCompareMode.STRICT))
    }

    @Test
    fun `GET sheep는 cursor로 끝까지 넘겨도 빠짐없이 중복없이 본인 양만 나온다`() {
        val offsets = mapOf("sh_1" to 2L, "sh_2" to 4L, "sh_3" to 0L, "sh_4" to 3L, "sh_5" to 1L)
        for ((id, offset) in offsets) {
            val date = d9.plusDays(offset)
            night("n_$id", me, date, "career", null)
            sheep(id, me, "n_$id", date, "c_1", "career", null, 7, 1)
        }
        night("n_x", other, d11.plusDays(1), "career", null)
        sheep("sh_x", other, "n_x", d11.plusDays(1), "c_1", "career", null, 7, 1)

        val seen = mutableListOf<String>()
        var cursor: String? = null
        do {
            val request = get("/api/v1/sheep").param("limit", "2")
            cursor?.let { request.param("cursor", it) }
            val body = mvc.perform(asUser(me, request)).andExpect(status().isOk).andReturn().response.contentAsString
            seen += JsonPath.read<List<String>>(body, "$.items[*].id")
            cursor = JsonPath.read<String?>(body, "$.next_cursor")
        } while (cursor != null)

        assertThat(seen).containsExactly("sh_2", "sh_4", "sh_1", "sh_5", "sh_3")
    }

    @Test
    fun `GET sheep 상세는 카드·반응·고민·회고를 합쳐 돌려준다`() {
        night("n_1", me, d10, "career", "내일 면접이 걱정돼요.")
        sheep("sh_1", me, "n_1", d10, "c_1", "career", 4, 15, 2)
        jdbc.update("INSERT INTO feed_card_yarn(user_id, card_id) VALUES (?, ?)", me, "c_1")
        jdbc.update("INSERT INTO night_reactions(night_id, card_id, fluff_saved, song_saved_at_ms) VALUES (?, ?, TRUE, NULL)", "n_1", "c_1")
        jdbc.update("INSERT INTO night_feed_state(night_id, reflection_answer, reflection_message) VALUES (?, ?, ?)",
            "n_1", "smaller", "봐, 밤엔 커 보였던 거야.")

        mvc.perform(asUser(me, get("/api/v1/sheep/sh_1")))
            .andExpect(status().isOk)
            .andExpect(content().json(
                """
                {
                  "sheep":{"id":"sh_1","night_id":"n_1","night_date":"2026-11-10","card_id":"c_1",
                           "color_key":"career","created_at":"2026-11-10T22:00:00Z","version":2,"placements":[]},
                  "card":{"id":"c_1","available":true,"kind":"text","tone":"empathy","category_id":"career",
                          "author_type":"seed","author_label":"먼저 지나간 양",
                          "content":{"text":"나도 면접 전날에 잠이 안 왔어."},
                          "viewer":{"yarn_sent":true,"fluff_saved":true,"song_saved":false}},
                  "position":4,
                  "sheep_count":15,
                  "night":{"night_date":"2026-11-10","worry_text":"내일 면접이 걱정돼요.","category_id":"career",
                           "reflection":{"answer":"smaller","message":"봐, 밤엔 커 보였던 거야."}}
                }
                """, JsonCompareMode.STRICT))
    }

    @Test
    fun `노래 카드는 track과 recommendation을 담고 song_saved를 반영한다`() {
        jdbc.update("""INSERT INTO feed_card_catalog
            (id, kind, tone, category_id, author_type, author_label, author_user_id, content_json, available, published_at_ms)
            VALUES (?, 'song', NULL, 'study', 'user', '놀러온 양', ?, ?, TRUE, ?)""", "c_s", other,
            """{"track":{"id":"track_1","title":"곡 제목","artist":"가수","cover_url":null,"spotify_search_url":"https://open.spotify.com/search/x"},"recommendation":"들어 봐요."}""",
            t0)
        night("n_1", me, d10, "study", "시험")
        sheep("sh_1", me, "n_1", d10, "c_s", "study", 8, 16, 1)
        jdbc.update("INSERT INTO night_reactions(night_id, card_id, fluff_saved, song_saved_at_ms) VALUES (?, ?, FALSE, ?)", "n_1", "c_s", t0)

        mvc.perform(asUser(me, get("/api/v1/sheep/sh_1")))
            .andExpect(status().isOk)
            .andExpect(content().json(
                """
                {"card":{"id":"c_s","available":true,"kind":"song","tone":null,"category_id":"study",
                         "content":{"track":{"id":"track_1","title":"곡 제목","artist":"가수","cover_url":null,
                                             "spotify_search_url":"https://open.spotify.com/search/x"},
                                    "recommendation":"들어 봐요."},
                         "viewer":{"yarn_sent":false,"fluff_saved":false,"song_saved":true}}}
                """, JsonCompareMode.LENIENT))
    }

    @Test
    fun `삭제된 카드와 삭제된 고민도 200, content·worry_text null, viewer 모두 false`() {
        night("n_1", me, d10, "career", "지운 고민", deleted = true)
        sheep("sh_1", me, "n_1", d10, "c_1", "career", 3, 10, 1)
        jdbc.update("INSERT INTO night_reactions(night_id, card_id, fluff_saved, song_saved_at_ms) VALUES (?, ?, TRUE, NULL)", "n_1", "c_1")
        jdbc.update("UPDATE feed_card_catalog SET available = FALSE, content_json = NULL WHERE id = ?", "c_1")

        mvc.perform(asUser(me, get("/api/v1/sheep/sh_1")))
            .andExpect(status().isOk)
            .andExpect(content().json(
                """
                {"card":{"id":"c_1","available":false,"content":null,
                         "viewer":{"yarn_sent":false,"fluff_saved":false,"song_saved":false}},
                 "night":{"worry_text":null,"reflection":null}}
                """, JsonCompareMode.LENIENT))
    }

    @Test
    fun `다른 사용자의 양은 404이고 내용을 노출하지 않는다`() {
        night("n_1", other, d10, "career", "남의 고민")
        sheep("sh_1", other, "n_1", d10, "c_1", "career", 1, 3, 1)

        mvc.perform(asUser(me, get("/api/v1/sheep/sh_1")))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"))
            .andExpect(content().string(not(containsString("남의 고민"))))
    }

    private fun asUser(userId: String, request: MockHttpServletRequestBuilder) = request.with(authentication(
        UsernamePasswordAuthenticationToken(CurrentUser(userId, true), null, listOf(SimpleGrantedAuthority("ROLE_USER")))))

    private fun textCard(id: String, authorType: String, label: String?, tone: String?, category: String?, text: String) {
        jdbc.update("""INSERT INTO feed_card_catalog
            (id, kind, tone, category_id, author_type, author_label, author_user_id, content_json, available, published_at_ms)
            VALUES (?, 'text', ?, ?, ?, ?, NULL, ?, TRUE, ?)""",
            id, tone, category, authorType, label, """{"text":"$text"}""", t0)
    }

    private fun night(id: String, userId: String, date: LocalDate, category: String, worry: String?, deleted: Boolean = false) {
        jdbc.update("""INSERT INTO be1_nights(id, user_id, night_date, category_id, worry_text, worry_saved_at_ms, worry_deleted)
            VALUES (?, ?, ?, ?, ?, ?, ?)""", id, userId, Date.valueOf(date), category, worry, t0, deleted)
    }

    private fun sheep(id: String, userId: String, nightId: String, date: LocalDate, cardId: String, colorKey: String?,
                      position: Int?, sheepCount: Int, version: Int, placements: String = "[]") {
        jdbc.update("""INSERT INTO adopted_sheep
            (id, night_id, user_id, night_date, card_id, color_key, created_at_ms, position, sheep_count, placements_json, version)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            id, nightId, userId, Date.valueOf(date), cardId, colorKey, t0, position, sheepCount, placements, version)
    }

    private fun sticker(id: String, label: String, order: Int) {
        jdbc.update("INSERT INTO stickers(id, label, asset_url, sort_order) VALUES (?, ?, ?, ?)",
            id, label, "https://cdn.example.com/$id.png", order)
    }

    companion object {
        private val externalUrl = System.getenv("MYSQL_TEST_URL")?.also {
            require(Regex("jdbc:mysql://(?:localhost|127\\.0\\.0\\.1):[0-9]+/[A-Za-z0-9_]+_test(?:\\?.*)?").matches(it)) {
                "MYSQL_TEST_URL must point to a disposable MySQL database named *_test"
            }
        }
        private val mysql by lazy {
            MySQLContainer("mysql:8.4").withDatabaseName("be3_test").withUsername("be3_test").withPassword("be3-test-only").also { it.start() }
        }

        @JvmStatic
        @DynamicPropertySource
        fun database(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { externalUrl ?: mysql.jdbcUrl }
            registry.add("spring.datasource.username") { if (externalUrl != null) System.getenv("MYSQL_TEST_USERNAME") ?: "be3_test" else mysql.username }
            registry.add("spring.datasource.password") { if (externalUrl != null) System.getenv("MYSQL_TEST_PASSWORD") ?: error("MYSQL_TEST_PASSWORD is required") else mysql.password }
            registry.add("spring.flyway.enabled") { true }
        }
    }
}
