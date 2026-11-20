package com.yangsenunbam

import com.yangsenunbam.auth.*
import com.yangsenunbam.bootstrap.JdbcNightSnapshotProvider
import com.yangsenunbam.card.*
import com.yangsenunbam.common.*
import com.yangsenunbam.night.*
import com.yangsenunbam.security.CurrentUser
import com.yangsenunbam.security.TokenService
import com.yangsenunbam.session.*
import com.yangsenunbam.user.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.mockito.Mockito.*
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.bean.override.mockito.MockitoBean
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@SpringBootTest
@ActiveProfiles("session-test")
class Be1RemainingFlowTest {
    @Autowired lateinit var auth: AuthService
    @Autowired lateinit var authStore: AuthStore
    @Autowired lateinit var resetService: PasswordResetService
    @Autowired lateinit var outbox: PasswordResetOutboxWorker
    @Autowired lateinit var tokens: TokenService
    @Autowired lateinit var nights: NightService
    @Autowired lateinit var nightStore: NightStore
    @Autowired lateinit var sessions: SessionService
    @Autowired lateinit var sessionStore: SessionStore
    @Autowired lateinit var users: UserService
    @Autowired lateinit var snapshots: JdbcNightSnapshotProvider
    @Autowired lateinit var jdbc: JdbcTemplate
    @MockitoBean lateinit var clock: Clock
    @MockitoBean lateinit var safety: WorrySafetyGateway
    @MockitoBean lateinit var feed: FeedPreparationPort
    @MockitoBean lateinit var visibility: ClosingCardVisibilityPort
    @MockitoBean lateinit var closing: ClosingCardPort
    @MockitoBean lateinit var morning: MorningCandidatePort
    @MockitoBean lateinit var deletion: CrossDomainAccountDeletionPort
    @MockitoBean lateinit var mail: ResetMailDelivery

    private fun at(value: String) {
        val instant = Instant.parse(value)
        `when`(clock.instant()).thenReturn(instant)
        `when`(clock.millis()).thenReturn(instant.toEpochMilli())
        `when`(clock.zone).thenReturn(ZoneOffset.UTC)
    }

    @BeforeEach fun setup() {
        at("2026-11-16T13:00:00Z")
        `when`(visibility.lockAndCheckAvailable(anyString())).thenReturn(true)
        `when`(safety.check(anyString())).thenReturn(WorryVerdict(false))
        doReturn(1).`when`(feed).prepare(anyString(), anyString(), anyString(), anyInt())
        `when`(closing.choose(anyString(), anyString(), anyString(), any<FinishReason>() ?: FinishReason.LEAVE)).thenReturn(
            CardResponse("fixture-card", true, CardKind.TEXT, CardTone.WARM, CategoryId.OTHER,
                AuthorType.EDITORIAL, null, TextCardContent("test fixture"), CardViewer(false, false, false)))
    }

    private fun account(): Pair<CurrentUser, AuthResponse> {
        val email = "remaining-${UUID.randomUUID()}@example.com"
        val response = auth.register(RegisterRequest(email, "FixturePass123!"), UUID.randomUUID().toString())
        return CurrentUser(authStore.findUserByEmail(email)!!.id, true) to response
    }
    private fun night(user: CurrentUser) = nights.recordWorry(user, WorryRequest(CategoryId.NONE, null))
    private fun count(table: String, userId: String) = jdbc.queryForObject(
        "SELECT COUNT(*) FROM $table WHERE user_id=?", Long::class.java, userId)!!
    private fun error(code: ErrorCode, operation: () -> Unit) {
        assertEquals(code, assertThrows(ApiException::class.java, operation).code)
    }

    @ParameterizedTest
    @CsvSource("2026-11-16T12:00:00Z,true", "2026-11-16T18:59:00Z,true",
        "2026-11-16T19:00:00Z,false", "2026-11-16T11:59:00Z,false")
    fun `night boundaries gate writes`(instant: String, allowed: Boolean) {
        val user = account().first
        at(instant)
        if (allowed) assertEquals(NightStatus.READY, night(user).status)
        else error(ErrorCode.NIGHT_CLOSED) { night(user) }
    }

    @Test fun `worry replay survives safety failure and deletion prevents reentry`() {
        val user = account().first
        val request = WorryRequest(CategoryId.OTHER, "test worry")
        val first = nights.recordWorry(user, request)
        `when`(safety.check(anyString())).thenThrow(ApiException(ErrorCode.SAFETY_CHECK_UNAVAILABLE, "fixture"))
        assertEquals(first, nights.recordWorry(user, request))
        verify(safety, times(1)).check("test worry")
        error(ErrorCode.WORRY_ALREADY_RECORDED) { night(user) }
        nights.deleteWorry(user, first.id)
        assertTrue(users.worries(user, null, 20).items.isEmpty())
        error(ErrorCode.WORRY_ALREADY_RECORDED) { nights.recordWorry(user, request) }
    }

    @Test fun `concurrent identical worries share one night after safety check`() {
        val user = account().first
        val checked = CountDownLatch(2)
        doAnswer {
            checked.countDown()
            check(checked.await(5, TimeUnit.SECONDS))
            WorryVerdict(false)
        }.`when`(safety).check(anyString())
        val pool = Executors.newFixedThreadPool(2)
        try {
            val results = (1..2).map { pool.submit(Callable {
                nights.recordWorry(user, WorryRequest(CategoryId.OTHER, "concurrent fixture"))
            }) }.map { it.get(10, TimeUnit.SECONDS) }
            assertEquals(results[0], results[1])
            assertEquals(1L, count("be1_nights", user.id))
        } finally { pool.shutdownNow() }
    }

    @Test fun `safety failure rolls back and none requires null`() {
        val user = account().first
        error(ErrorCode.VALIDATION_ERROR) { nights.recordWorry(user, WorryRequest(CategoryId.NONE, "text")) }
        `when`(safety.check(anyString())).thenThrow(ApiException(ErrorCode.SAFETY_CHECK_UNAVAILABLE, "fixture"))
        error(ErrorCode.SAFETY_CHECK_UNAVAILABLE) { nights.recordWorry(user, WorryRequest(CategoryId.OTHER, "text")) }
        assertEquals(0L, count("be1_nights", user.id))
        assertEquals(NightStatus.READY, night(user).status)
    }

    @Test fun `support must be acknowledged before creation`() {
        val user = account().first
        `when`(safety.check(anyString())).thenReturn(WorryVerdict(true, SupportResponse("fixture only", listOf(SupportResource("fixture", SupportAction.OPEN_CONTACTS, null)))))
        val n = nights.recordWorry(user, WorryRequest(CategoryId.OTHER, "fixture"))
        assertEquals(NightStatus.NEEDS_SUPPORT, n.status)
        error(ErrorCode.SUPPORT_REQUIRED) { sessions.create(user, n.id, CreateSessionRequest(3), "before") }
        nights.continueAfterSupport(user, n.id)
        assertEquals(SessionStatus.ACTIVE, sessions.create(user, n.id, CreateSessionRequest(3), "after").status)
    }

    @Test fun `feed failure rolls back session and idempotency then retry succeeds`() {
        val user = account().first
        val n = night(user)
        `when`(feed.prepare(anyString(), anyString(), anyString(), anyInt()))
            .thenThrow(ApiException(ErrorCode.FEED_BUILD_UNAVAILABLE, "fixture"))
        error(ErrorCode.FEED_BUILD_UNAVAILABLE) { sessions.create(user, n.id, CreateSessionRequest(3), "create") }
        assertEquals(0L, count("be1_sessions", user.id))
        assertEquals(0L, count("be1_session_replays", user.id))
        doReturn(1).`when`(feed).prepare(anyString(), anyString(), anyString(), anyInt())
        val session = sessions.create(user, n.id, CreateSessionRequest(3), "create")
        assertEquals(session, sessions.create(user, n.id, CreateSessionRequest(3), "create"))
        error(ErrorCode.IDEMPOTENCY_CONFLICT) { sessions.create(user, n.id, CreateSessionRequest(4), "create") }
        assertEquals(session.id, snapshots.forUser(user.id, n.nightDate).activeSessionId)
    }

    @Test fun `concurrent creates serialize to one active session`() {
        val user = account().first
        val n = night(user)
        val pool = Executors.newFixedThreadPool(2)
        val start = CountDownLatch(1)
        try {
            val results = (1..2).map { i -> pool.submit(Callable {
                start.await(5, TimeUnit.SECONDS)
                try { sessions.create(user, n.id, CreateSessionRequest(3), "parallel-$i"); "created" }
                catch (ex: ApiException) { ex.code.name }
            }) }
            start.countDown()
            assertEquals(listOf("ACTIVE_SESSION_EXISTS", "created"), results.map { it.get(10, TimeUnit.SECONDS) }.sorted())
            assertEquals(1L, count("be1_sessions", user.id))
        } finally { pool.shutdownNow() }
    }

    @Test fun `same key concurrent create and finish have one effect`() {
        val user = account().first
        val n = night(user)
        val pool = Executors.newFixedThreadPool(2)
        fun <T> parallel(action: () -> T): List<T> {
            val start = CountDownLatch(1)
            val tasks = (1..2).map { pool.submit(Callable { start.await(5, TimeUnit.SECONDS); action() }) }
            start.countDown()
            return tasks.map { it.get(10, TimeUnit.SECONDS) }
        }
        try {
            val created = parallel { sessions.create(user, n.id, CreateSessionRequest(3), "same-create") }
            assertEquals(created[0], created[1])
            val finished = parallel {
                sessions.finish(user, created[0].id, FinishRequest(FinishReason.COMPLETED, 3, 3, 1, 1), "same-finish")
            }
            assertEquals(finished[0], finished[1])
            verify(feed, times(1)).prepare(user.id, n.id, created[0].id, 3)
            verify(closing, times(1)).choose(user.id, n.id, created[0].id, FinishReason.COMPLETED)
        } finally { pool.shutdownNow() }
    }

    @Test fun `finish validates ownership epoch sequence and persists replay`() {
        val user = account().first
        val n = night(user)
        val s = sessions.create(user, n.id, CreateSessionRequest(3), "create")
        val request = FinishRequest(FinishReason.COMPLETED, 3, 3, 1, 1)
        error(ErrorCode.RESOURCE_NOT_FOUND) { sessions.finish(account().first, s.id, request, "foreign") }
        error(ErrorCode.WRITER_REPLACED) { sessions.finish(user, s.id, request.copy(writerEpoch = 2), "epoch") }
        error(ErrorCode.SEQUENCE_CONFLICT) { sessions.finish(user, s.id, request.copy(sequence = 0), "sequence") }
        val first = sessions.finish(user, s.id, request, "finish")
        assertEquals(first, sessions.finish(user, s.id, request, "finish"))
        assertEquals(first, sessions.finish(user, s.id, request, "another-key"))
        error(ErrorCode.FINISH_CONFLICT) { sessions.finish(user, s.id, request.copy(reason = FinishReason.LEAVE), "conflict") }
        verify(closing, times(1)).choose(user.id, n.id, s.id, FinishReason.COMPLETED)
        at("2026-11-16T19:00:00Z")
        nights.saveWritingOffer(user, n.id, WritingOfferRequest(WritingChoice.MORNING))
        assertEquals(WritingState.DEFERRED, nightStore.owned(user.id, n.id).writingState)
    }

    @Test fun `closing provider failure rolls back progress and finish`() {
        val user = account().first
        val n = night(user)
        val s = sessions.create(user, n.id, CreateSessionRequest(3), "create")
        `when`(closing.choose(anyString(), anyString(), anyString(), any<FinishReason>() ?: FinishReason.LEAVE))
            .thenThrow(ApiException(ErrorCode.FEED_BUILD_UNAVAILABLE, "fixture"))
        error(ErrorCode.FEED_BUILD_UNAVAILABLE) {
            sessions.finish(user, s.id, FinishRequest(FinishReason.LEAVE, 1, 1, 1, 1), "finish")
        }
        assertEquals(SessionStatus.ACTIVE, sessionStore.owned(user.id, s.id).status)
        assertEquals(0, sessionStore.owned(user.id, s.id).furthestPosition)
        assertNull(nightStore.owned(user.id, n.id).writingState)
    }

    @Test fun `expired session rejects finish and disappears from active snapshot`() {
        val user = account().first
        val n = night(user)
        val s = sessions.create(user, n.id, CreateSessionRequest(3), "create")
        at("2026-11-16T19:00:00Z")
        error(ErrorCode.SESSION_CLOSED) { sessions.finish(user, s.id, FinishRequest(FinishReason.LEAVE, 1, 1, 1, 1), "late") }
        assertNull(snapshots.forUser(user.id, n.nightDate).activeSessionId)
        assertEquals(SessionStatus.EXPIRED, sessionStore.owned(user.id, s.id).status)
    }

    @Test fun `worry cursor is stable and scoped to owner`() {
        val user = account().first
        nights.recordWorry(user, WorryRequest(CategoryId.OTHER, "first fixture"))
        at("2026-11-17T13:00:00Z")
        nights.recordWorry(user, WorryRequest(CategoryId.OTHER, "second fixture"))
        val first = users.worries(user, null, 1)
        assertEquals(1, first.items.size)
        assertNotNull(first.nextCursor)
        val second = users.worries(user, first.nextCursor, 1)
        assertEquals(1, second.items.size)
        assertNotEquals(first.items, second.items)
        assertNull(second.nextCursor)
        assertTrue(users.worries(account().first, first.nextCursor, 1).items.isEmpty())
    }

    @Test fun `reset is encrypted one use revokes sessions and hides unknown accounts`() {
        val (user, initial) = account()
        val email = authStore.findUserById(user.id)!!.email
        assertTrue(resetService.request(PasswordResetRequest(email)).accepted)
        assertTrue(resetService.request(PasswordResetRequest("missing-${UUID.randomUUID()}@example.com")).accepted)
        resetService.request(PasswordResetRequest(email))
        assertEquals(1L, count("auth_password_resets", user.id))
        val encrypted = jdbc.queryForObject("SELECT encrypted_token FROM auth_reset_outbox WHERE reset_id IN " +
            "(SELECT id FROM auth_password_resets WHERE user_id=?)", String::class.java, user.id)!!
        val token = tokens.unseal(encrypted)
        assertTrue(encrypted != token)
        resetService.confirm(PasswordResetConfirmRequest(token, "ChangedPass123!"))
        error(ErrorCode.INVALID_RESET_TOKEN) { resetService.confirm(PasswordResetConfirmRequest(token, "ChangedPass123!")) }
        error(ErrorCode.INVALID_REFRESH_TOKEN) { auth.refresh(RefreshTokenRequest(initial.refreshToken), "after-reset") }
        error(ErrorCode.INVALID_CREDENTIALS) { auth.login(LoginRequest(email, "FixturePass123!"), "old-password") }
        auth.login(LoginRequest(email, "ChangedPass123!"), "new-password")
    }

    @Test fun `outbox retries failures and skips expired resets`() {
        val user = account().first
        val email = authStore.findUserById(user.id)!!.email
        resetService.request(PasswordResetRequest(email))
        doThrow(IllegalStateException("fixture delivery failure")).`when`(mail).deliver(anyString(), anyString())
        outbox.dispatch()
        val attempts = jdbc.queryForObject("SELECT attempt_count FROM auth_reset_outbox WHERE reset_id IN " +
            "(SELECT id FROM auth_password_resets WHERE user_id=?)", Int::class.java, user.id)
        assertEquals(1, attempts)
        at("2026-11-16T13:01:00Z")
        doNothing().`when`(mail).deliver(anyString(), anyString())
        outbox.dispatch()
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM auth_reset_outbox WHERE sent_at_ms IS NOT NULL AND reset_id IN " +
            "(SELECT id FROM auth_password_resets WHERE user_id=?)", Int::class.java, user.id))
        resetService.request(PasswordResetRequest(email))
        val encrypted = jdbc.queryForObject("SELECT o.encrypted_token FROM auth_reset_outbox o JOIN auth_password_resets r " +
            "ON r.id=o.reset_id WHERE r.user_id=? ORDER BY r.created_at_ms DESC LIMIT 1", String::class.java, user.id)!!
        at("2026-11-16T13:31:00Z")
        clearInvocations(mail)
        outbox.dispatch()
        verifyNoInteractions(mail)
        error(ErrorCode.INVALID_RESET_TOKEN) { resetService.confirm(PasswordResetConfirmRequest(tokens.unseal(encrypted), "ChangedPass123!")) }
    }

    @Test fun `cross domain deletion failure rolls back all participant writes`() {
        val user = account().first
        val n = night(user)
        doAnswer {
            jdbc.update("DELETE FROM be1_nights WHERE user_id=?", user.id)
            throw ApiException(ErrorCode.DATA_DELETION_UNAVAILABLE, "fixture")
        }.`when`(deletion).deleteOwnedData(user.id)
        error(ErrorCode.DATA_DELETION_UNAVAILABLE) { users.deleteAccount(user) }
        assertNotNull(authStore.findUserById(user.id))
        assertEquals(n.id, nightStore.owned(user.id, n.id).id)
        doNothing().`when`(deletion).deleteOwnedData(user.id)
        users.deleteAccount(user)
        assertNull(authStore.findUserById(user.id))
        assertEquals(0L, count("be1_nights", user.id))
    }
}
