package com.yangsenunbam.card

import com.jayway.jsonpath.JsonPath
import com.yangsenunbam.be2.MutableClock
import com.yangsenunbam.common.*
import com.yangsenunbam.music.ItunesClient
import com.yangsenunbam.music.ItunesTrack
import com.yangsenunbam.night.WorrySafetyGateway
import com.yangsenunbam.night.WorryVerdict
import com.yangsenunbam.security.CurrentUser
import com.yangsenunbam.user.CrossDomainAccountDeletionPort
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.*
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import org.testcontainers.mysql.MySQLContainer
import java.sql.Date
import java.time.Instant
import java.time.LocalDate

@Tag("mysql")
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("mysql")
@Import(MysqlBe3CardsTest.TestClock::class)
class MysqlBe3CardsTest {
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var clock: MutableClock
    @Autowired lateinit var deletion: CrossDomainAccountDeletionPort
    @Autowired lateinit var transactions: PlatformTransactionManager
    @MockitoBean lateinit var itunes: ItunesClient
    @MockitoBean lateinit var safety: WorrySafetyGateway

    private val writer = "be3-writer"
    private val reader = "be3-reader"
    private val stranger = "be3-stranger"
    private val users = listOf(writer, reader, stranger)
    private val day = Instant.parse("2026-11-11T03:00:00Z")
    private val night = Instant.parse("2026-11-10T13:00:00Z")
    private var keySeq = 0

    @BeforeEach
    fun clean() {
        clock.current = day
        `when`(safety.check(anyString())).thenReturn(WorryVerdict(false))
        listOf("yarn_summaries", "yarn_events", "card_idempotency", "card_submissions", "music_search_cache", "music_tracks",
            "feed_resume_receipts", "adopted_sheep", "night_reactions", "feed_card_views", "feed_items",
            "feed_session_progress", "night_feed_state", "feed_card_yarn", "feed_card_history", "feed_card_catalog",
            "stickers").forEach { jdbc.update("DELETE FROM $it") }
        val ids = users.joinToString(",") { "'$it'" }
        for (table in listOf("be1_session_replays", "be1_sessions", "be1_nights", "user_onboarding")) {
            jdbc.update("DELETE FROM $table WHERE user_id IN ($ids)")
        }
        jdbc.update("DELETE FROM auth_users WHERE id IN ($ids)")
        users.forEach {
            jdbc.update("INSERT INTO auth_users(id, email, password_hash, onboarding_complete, created_at_ms) VALUES (?, ?, ?, TRUE, 0)",
                it, "$it@example.test", "test-fixture-not-a-login-hash")
        }
    }

    @Test
    fun `곡 검색은 iTunes 결과를 정규화하고 같은 검색어는 캐시에서 돌려준다`() {
        `when`(itunes.search(anyString(), anyInt())).thenReturn(listOf(
            ItunesTrack(11, "밤편지", "아이유", "https://is1.example/100x100bb.jpg"),
            ItunesTrack(12, "Love poem", "아이유", null)))

        mvc.perform(asUser(reader, get("/api/v1/music/search").param("q", "  아이유  ").param("limit", "1")))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.items[0].id").value("itunes_11"))
            .andExpect(jsonPath("$.items[0].cover_url").value("https://is1.example/600x600bb.jpg"))
            .andExpect(jsonPath("$.items[0].spotify_search_url")
                .value("https://open.spotify.com/search/%EB%B0%A4%ED%8E%B8%EC%A7%80%20%EC%95%84%EC%9D%B4%EC%9C%A0"))
        mvc.perform(asUser(reader, get("/api/v1/music/search").param("q", "아이유")))
            .andExpect(status().isOk).andExpect(jsonPath("$.items[1].cover_url").doesNotExist())

        verify(itunes, times(1)).search("아이유", 50)
    }

    @Test
    fun `곡 검색 장애는 캐시가 없으면 503, 결과 없음은 빈 items`() {
        `when`(itunes.search("없는곡", 50)).thenReturn(emptyList())
        `when`(itunes.search("장애", 50))
            .thenThrow(ApiException(ErrorCode.MUSIC_SEARCH_UNAVAILABLE, "down"))

        mvc.perform(asUser(reader, get("/api/v1/music/search").param("q", "없는곡")))
            .andExpect(status().isOk).andExpect(jsonPath("$.items.length()").value(0))
        mvc.perform(asUser(reader, get("/api/v1/music/search").param("q", "장애")))
            .andExpect(status().isServiceUnavailable)
            .andExpect(jsonPath("$.error.code").value("MUSIC_SEARCH_UNAVAILABLE"))
    }

    @Test
    fun `낮에 글 카드를 제출하면 pending으로 접수되고 같은 멱등키는 같은 결과, 다른 본문은 409`() {
        val body = """{"kind":"text","category_id":"career","content":{"text":"  나도 그랬어.  "}}"""
        val first = submit(body, "key-1").andExpect(status().isCreated)
            .andExpect(jsonPath("$.moderation_status").value("pending"))
            .andExpect(jsonPath("$.content.text").value("나도 그랬어."))
            .andExpect(jsonPath("$.rejection_reason").isEmpty)
            .andExpect(jsonPath("$.yarn_count").value(0))
            .andExpect(jsonPath("$.revision").value(1))
            .andReturn().response.contentAsString
        val id = JsonPath.read<String>(first, "$.id")

        submit(body, "key-1").andExpect(status().isCreated).andExpect(jsonPath("$.id").value(id))
        submit("""{"kind":"text","category_id":"career","content":{"text":"다른 글"}}""", "key-1")
            .andExpect(status().isConflict).andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_CONFLICT"))
        assertThat(count("card_submissions")).isEqualTo(1)

        mvc.perform(asUser(writer, get("/api/v1/me/cards").param("status", "pending")))
            .andExpect(status().isOk).andExpect(jsonPath("$.items[0].id").value(id))
        mvc.perform(asUser(writer, get("/api/v1/me/cards").param("status", "approved")))
            .andExpect(status().isOk).andExpect(jsonPath("$.items.length()").value(0))
        mvc.perform(asUser(reader, get("/api/v1/me/cards")))
            .andExpect(status().isOk).andExpect(jsonPath("$.items.length()").value(0))
    }

    @Test
    fun `밤에는 작성 제안 writing 상태에서만 제출되고 제출하면 fulfilled가 된다`() {
        clock.current = night
        nightRecord("n_w", writer, LocalDate.parse("2026-11-10"), "career", "writing")
        val text = """{"kind":"text","category_id":"career","content":{"text":"괜찮아"}"""

        submit("$text}", "k-no-source").andExpect(status().isConflict)
            .andExpect(jsonPath("$.error.code").value("WRITING_ENTRY_REQUIRED"))
        submit("""$text,"source_night_id":"n_w"}""", "k-source").andExpect(status().isCreated)
        assertThat(writingState("n_w")).isEqualTo("fulfilled")
        submit("""$text,"source_night_id":"n_w"}""", "k-again").andExpect(status().isConflict)
            .andExpect(jsonPath("$.error.code").value("WRITING_ENTRY_REQUIRED"))
    }

    @Test
    fun `낮에 아침 배너(deferred)에서 제출하면 그 밤의 작성 제안이 fulfilled가 된다`() {
        nightRecord("n_d", writer, LocalDate.parse("2026-11-10"), "career", "deferred")
        submit("""{"kind":"text","category_id":"career","content":{"text":"괜찮아"},"source_night_id":"n_d"}""", "k")
            .andExpect(status().isCreated)
        assertThat(writingState("n_d")).isEqualTo("fulfilled")
        submit("""{"kind":"text","category_id":"career","content":{"text":"괜찮아"},"source_night_id":"nope"}""", "k2")
            .andExpect(status().isNotFound)
    }

    @Test
    fun `노래 카드는 검색한 곡을 track으로 확장하고 모르는 곡은 422 TRACK_NOT_FOUND`() {
        `when`(itunes.search(anyString(), anyInt())).thenReturn(listOf(ItunesTrack(21, "곡", "가수", null)))
        mvc.perform(asUser(writer, get("/api/v1/music/search").param("q", "곡"))).andExpect(status().isOk)

        submit("""{"kind":"song","category_id":"study","content":{"track_id":"itunes_21","recommendation":"들어 봐요"}}""", "s1")
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.content.track.id").value("itunes_21"))
            .andExpect(jsonPath("$.content.track.title").value("곡"))
            .andExpect(jsonPath("$.content.recommendation").value("들어 봐요"))
        submit("""{"kind":"song","category_id":"study","content":{"track_id":"itunes_99","recommendation":"x"}}""", "s2")
            .andExpect(status().isUnprocessableContent).andExpect(jsonPath("$.error.code").value("TRACK_NOT_FOUND"))
        submit("""{"kind":"song","category_id":"study","content":{"manual_track":{"title":"직접 곡","artist":"직접 가수"},"recommendation":"좋아요"}}""", "s3")
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.content.track.title").value("직접 곡"))
            .andExpect(jsonPath("$.content.track.cover_url").isEmpty)
    }

    @Test
    fun `위기 표현이 감지되면 422 SUPPORT_RECOMMENDED와 support를 돌려주고 검수 대기열에 넣지 않는다`() {
        val support = SupportResponse("도움을 받아 보세요.", listOf(SupportResource("상담", SupportAction.CALL, "109")))
        `when`(safety.check("위험한 글")).thenReturn(WorryVerdict(true, support))

        submit("""{"kind":"text","category_id":"health","content":{"text":"위험한 글"}}""", "k")
            .andExpect(status().isUnprocessableContent)
            .andExpect(jsonPath("$.error.code").value("SUPPORT_RECOMMENDED"))
            .andExpect(jsonPath("$.error.support.resources[0].target").value("109"))
        assertThat(count("card_submissions")).isZero()
    }

    @Test
    fun `반려 후 재제출하면 revision이 오르고, 이전 revision 승인은 409, 최신 승인은 피드 공개 목록에 발행된다`() {
        val id = createdId("""{"kind":"text","category_id":"career","content":{"text":"첫 글"}}""")
        mvc.perform(asAdmin(get("/api/v1/admin/cards"))).andExpect(status().isOk)
            .andExpect(jsonPath("$.items[0].id").value(id))

        moderate(id, """{"decision":"rejected","reason":"본인의 말로 써 주세요.","revision":1}""")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.moderation_status").value("rejected"))
            .andExpect(jsonPath("$.rejection_reason").value("본인의 말로 써 주세요."))
        moderate(id, """{"decision":"rejected","reason":"본인의 말로 써 주세요.","revision":1}""").andExpect(status().isOk)

        mvc.perform(asUser(writer, post("/api/v1/me/cards/$id/resubmit")).header("Idempotency-Key", "r1")
            .contentType(MediaType.APPLICATION_JSON).content("""{"kind":"text","category_id":"family","content":{"text":"고친 글"}}"""))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.moderation_status").value("pending"))
            .andExpect(jsonPath("$.category_id").value("family"))
            .andExpect(jsonPath("$.revision").value(2))
        mvc.perform(asUser(writer, post("/api/v1/me/cards/$id/resubmit")).header("Idempotency-Key", "r2")
            .contentType(MediaType.APPLICATION_JSON).content("""{"kind":"text","category_id":"family","content":{"text":"또"}}"""))
            .andExpect(status().isConflict).andExpect(jsonPath("$.error.code").value("CARD_NOT_REJECTED"))

        moderate(id, """{"decision":"approved","reason":null,"revision":1}""")
            .andExpect(status().isConflict).andExpect(jsonPath("$.error.code").value("MODERATION_CONFLICT"))
        moderate(id, """{"decision":"approved","reason":null,"revision":2}""")
            .andExpect(status().isOk).andExpect(jsonPath("$.moderation_status").value("approved"))

        val published = jdbc.queryForMap("SELECT * FROM feed_card_catalog WHERE id = ?", id)
        assertThat(published["available"]).isEqualTo(true)
        assertThat(published["author_type"]).isEqualTo("user")
        assertThat(published["author_label"]).isEqualTo("먼저 지나간 양")
        assertThat(published["author_user_id"]).isEqualTo(writer)
        assertThat(published["tone"]).isEqualTo("empathy")
        assertThat(published["category_id"]).isEqualTo("family")
        mvc.perform(asAdmin(get("/api/v1/admin/cards"))).andExpect(jsonPath("$.items.length()").value(0))
    }

    @Test
    fun `남의 카드 삭제·재제출은 404, 내 카드 삭제는 반복해도 204이고 목록과 공개 목록에서 사라진다`() {
        val id = approvedCard("지울 글")
        mvc.perform(asUser(stranger, delete("/api/v1/me/cards/$id"))).andExpect(status().isNotFound)

        mvc.perform(asUser(writer, delete("/api/v1/me/cards/$id"))).andExpect(status().isNoContent)
        mvc.perform(asUser(writer, delete("/api/v1/me/cards/$id"))).andExpect(status().isNoContent)
        mvc.perform(asUser(writer, get("/api/v1/me/cards"))).andExpect(jsonPath("$.items.length()").value(0))
        val row = jdbc.queryForMap("SELECT available, content_json FROM feed_card_catalog WHERE id = ?", id)
        assertThat(row["available"]).isEqualTo(false)
        assertThat(row["content_json"]).isNull()
        moderate(id, """{"decision":"approved","reason":null,"revision":1}""")
            .andExpect(jsonPath("$.error.code").value("MODERATION_CONFLICT"))
    }

    @Test
    fun `피드에서 받은 카드에 털실을 보내고 취소하며, 작성자는 다음 04시 이후 요약을 한 번 본다`() {
        val id = approvedCard("털실 받을 글")
        feedAccess(reader, id)

        yarn(stranger, id, true).andExpect(status().isNotFound)
        yarn(reader, id, true).andExpect(status().isOk).andExpect(content().json("""{"card_id":"$id","sent":true}"""))
        yarn(reader, id, true).andExpect(status().isOk)
        assertThat(count("feed_card_yarn")).isEqualTo(1)
        mvc.perform(asUser(writer, get("/api/v1/me/cards"))).andExpect(jsonPath("$.items[0].yarn_count").value(1))

        mvc.perform(asUser(writer, get("/api/v1/me/yarn-summary"))).andExpect(content().json("""{"summary":null}"""))

        clock.current = Instant.parse("2026-11-12T00:00:00Z")
        val body = mvc.perform(asUser(writer, get("/api/v1/me/yarn-summary"))).andExpect(status().isOk)
            .andExpect(jsonPath("$.summary.date").value("2026-11-12"))
            .andExpect(jsonPath("$.summary.yarn_count").value(1))
            .andExpect(jsonPath("$.summary.sender_count").value(1))
            .andReturn().response.contentAsString
        val summaryId = JsonPath.read<String>(body, "$.summary.id")
        mvc.perform(asUser(reader, put("/api/v1/me/yarn-summary/$summaryId/seen"))).andExpect(status().isNotFound)
        mvc.perform(asUser(writer, put("/api/v1/me/yarn-summary/$summaryId/seen"))).andExpect(status().isNoContent)
        mvc.perform(asUser(writer, put("/api/v1/me/yarn-summary/$summaryId/seen"))).andExpect(status().isNoContent)
        mvc.perform(asUser(writer, get("/api/v1/me/yarn-summary"))).andExpect(content().json("""{"summary":null}"""))

        yarn(reader, id, false).andExpect(status().isOk).andExpect(jsonPath("$.sent").value(false))
        assertThat(count("feed_card_yarn")).isZero()
    }

    @Test
    fun `마감 전에 취소한 털실과 삭제된 카드의 털실은 요약에서 빠지고, 삭제 카드에 새로 보내면 409`() {
        val kept = approvedCard("남길 글")
        val removed = approvedCard("지울 글")
        feedAccess(reader, kept, removed)
        yarn(reader, kept, true)
        yarn(reader, kept, false)
        yarn(reader, removed, true)
        mvc.perform(asUser(writer, delete("/api/v1/me/cards/$removed"))).andExpect(status().isNoContent)
        yarn(reader, removed, true).andExpect(status().isConflict).andExpect(jsonPath("$.error.code").value("CARD_UNAVAILABLE"))

        clock.current = Instant.parse("2026-11-12T00:00:00Z")
        mvc.perform(asUser(writer, get("/api/v1/me/yarn-summary"))).andExpect(content().json("""{"summary":null}"""))
    }

    @Test
    fun `스티커 배치는 낮에 전체 교체되고 version이 오르며 이전 version은 409, 밤에는 DAY_ONLY`() {
        val id = approvedCard("양 카드")
        nightRecord("n_s", writer, LocalDate.parse("2026-11-10"), "career", null)
        jdbc.update("""INSERT INTO adopted_sheep(id, night_id, user_id, night_date, card_id, color_key, created_at_ms, position,
            sheep_count, placements_json, version) VALUES ('sh_1', 'n_s', ?, '2026-11-10', ?, 'career', 0, 1, 7, '[]', 1)""", writer, id)
        jdbc.update("INSERT INTO stickers(id, label, asset_url, sort_order) VALUES ('st_1', '달', 'https://cdn.example/st_1.png', 1)")
        val body = """{"if_version":1,"placements":[{"instance_id":"pl_1","sticker_id":"st_1","x":0.4,"y":0.6,"rotation":-15,"scale":1.5}]}"""

        stickers(stranger, body).andExpect(status().isNotFound)
        stickers(writer, body).andExpect(status().isOk)
            .andExpect(jsonPath("$.version").value(2))
            .andExpect(jsonPath("$.placements[0].rotation").value(-15))
        stickers(writer, body).andExpect(status().isConflict).andExpect(jsonPath("$.error.code").value("VERSION_CONFLICT"))
        stickers(writer, """{"if_version":2,"placements":[{"instance_id":"pl_1","sticker_id":"st_x","x":0,"y":0,"rotation":0,"scale":1}]}""")
            .andExpect(status().isUnprocessableContent).andExpect(jsonPath("$.error.fields.placements").exists())
        mvc.perform(asUser(writer, get("/api/v1/sheep/sh_1"))).andExpect(jsonPath("$.sheep.placements[0].scale").value(1.5))

        clock.current = night
        stickers(writer, """{"if_version":2,"placements":[]}""")
            .andExpect(status().isConflict).andExpect(jsonPath("$.error.code").value("DAY_ONLY"))
    }

    @Test
    fun `탈퇴하면 작성 카드·멱등 기록·털실 이력·요약이 지워지고 계정 행을 지울 수 있다`() {
        val id = approvedCard("탈퇴할 글")
        createdId("""{"kind":"text","category_id":"career","content":{"text":"검수 중인 글"}}""")
        feedAccess(reader, id)
        yarn(reader, id, true).andExpect(status().isOk)
        clock.current = Instant.parse("2026-11-12T00:00:00Z")
        mvc.perform(asUser(writer, get("/api/v1/me/yarn-summary"))).andExpect(jsonPath("$.summary.yarn_count").value(1))

        TransactionTemplate(transactions).executeWithoutResult { deletion.deleteOwnedData(writer) }

        for (table in listOf("card_submissions", "card_idempotency", "yarn_summaries")) {
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM $table WHERE user_id = ?", Int::class.java, writer)).isZero()
        }
        assertThat(count("yarn_events")).isZero()
        assertThat(jdbc.queryForObject("SELECT available FROM feed_card_catalog WHERE id = ?", Boolean::class.java, id)).isFalse()
        assertThat(jdbc.update("DELETE FROM auth_users WHERE id = ?", writer)).isEqualTo(1)
    }

    private fun submit(body: String, key: String, user: String = writer): ResultActions =
        mvc.perform(asUser(user, post("/api/v1/cards")).header("Idempotency-Key", key)
            .contentType(MediaType.APPLICATION_JSON).content(body))

    private fun createdId(body: String): String =
        JsonPath.read(submit(body, "auto-${keySeq++}").andExpect(status().isCreated).andReturn().response.contentAsString, "$.id")

    private fun approvedCard(text: String): String {
        val id = createdId("""{"kind":"text","category_id":"career","content":{"text":"$text"}}""")
        moderate(id, """{"decision":"approved","reason":null,"revision":1}""").andExpect(status().isOk)
        return id
    }

    private fun moderate(id: String, body: String): ResultActions = mvc.perform(asAdmin(put("/api/v1/admin/cards/$id/moderation"))
        .contentType(MediaType.APPLICATION_JSON).content(body))

    private fun yarn(user: String, cardId: String, sent: Boolean): ResultActions = mvc.perform(asUser(user, put("/api/v1/cards/$cardId/yarn"))
        .contentType(MediaType.APPLICATION_JSON).content("""{"sent":$sent}"""))

    private fun stickers(user: String, body: String): ResultActions = mvc.perform(asUser(user, put("/api/v1/sheep/sh_1/stickers"))
        .contentType(MediaType.APPLICATION_JSON).content(body))

    private fun feedAccess(user: String, vararg cardIds: String) {
        nightRecord("n_$user", user, LocalDate.parse("2026-11-10"), "career", null)
        jdbc.update("""INSERT INTO be1_sessions(id, night_id, user_id, status, sheep_count, feed_version, started_at_ms, closes_at_ms)
            VALUES (?, ?, ?, 'completed', 7, 1, 0, 1)""", "s_$user", "n_$user", user)
        cardIds.forEachIndexed { index, cardId ->
            jdbc.update("INSERT INTO feed_items(id, session_id, position, card_id) VALUES (?, ?, ?, ?)",
                "i_${user}_$index", "s_$user", index + 1, cardId)
        }
    }

    private fun nightRecord(id: String, userId: String, date: LocalDate, category: String, writingState: String?) {
        jdbc.update("""INSERT INTO be1_nights(id, user_id, night_date, category_id, worry_text, worry_saved_at_ms, worry_deleted, writing_state)
            VALUES (?, ?, ?, ?, NULL, 0, FALSE, ?)""", id, userId, Date.valueOf(date), category, writingState)
    }

    private fun writingState(nightId: String) =
        jdbc.queryForObject("SELECT writing_state FROM be1_nights WHERE id = ?", String::class.java, nightId)

    private fun count(table: String) = jdbc.queryForObject("SELECT COUNT(*) FROM $table", Int::class.java)!!

    private fun asUser(userId: String, request: MockHttpServletRequestBuilder) = request.with(authentication(
        UsernamePasswordAuthenticationToken(CurrentUser(userId, true), null, listOf(SimpleGrantedAuthority("ROLE_USER")))))

    private fun asAdmin(request: MockHttpServletRequestBuilder) = request.with(authentication(
        UsernamePasswordAuthenticationToken(CurrentUser("be3-admin", true), null, listOf(SimpleGrantedAuthority("ROLE_ADMIN")))))

    @TestConfiguration
    class TestClock {
        @Bean @Primary fun be3Clock(): MutableClock = MutableClock()
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
