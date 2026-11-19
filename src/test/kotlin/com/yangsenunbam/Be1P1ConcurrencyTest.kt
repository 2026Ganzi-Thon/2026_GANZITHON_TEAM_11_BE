package com.yangsenunbam

import com.yangsenunbam.auth.*
import com.yangsenunbam.bootstrap.JdbcNightSnapshotProvider
import com.yangsenunbam.card.*
import com.yangsenunbam.common.*
import com.yangsenunbam.night.*
import com.yangsenunbam.security.*
import com.yangsenunbam.session.*
import com.yangsenunbam.user.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.Mockito.*
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.springframework.test.context.bean.override.convention.TestBean
import org.springframework.test.util.AopTestUtils
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.json.JsonMapper
import java.time.*
import java.util.UUID
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicReference

/** Real transactions/connections/row locks; mocks only external domain boundaries.
 * Latches place the contender at the lock while the leader is still uncommitted.
 */
@SpringBootTest
open class Be1P1ConcurrencyTest {
    @Autowired lateinit var auth: AuthService
    @MockitoSpyBean lateinit var store: AuthStore
    @Autowired lateinit var resets: PasswordResetService
    @Autowired lateinit var delivery: PasswordResetOutboxDelivery
    @Autowired lateinit var tokens: TokenService
    @Autowired lateinit var users: UserService
    @Autowired lateinit var nights: NightService
    @Autowired lateinit var sessions: SessionService
    @Autowired lateinit var snapshots: JdbcNightSnapshotProvider
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var manager: PlatformTransactionManager
    @Autowired lateinit var mapper: JsonMapper
    @TestBean(methodName = "fixedClock") lateinit var clock: Clock
    @MockitoBean lateinit var feed: FeedPreparationPort
    @MockitoBean lateinit var closing: ClosingCardPort
    @MockitoBean lateinit var visibility: ClosingCardVisibilityPort
    @MockitoBean lateinit var morning: MorningCandidatePort
    @MockitoBean lateinit var deletion: CrossDomainAccountDeletionPort
    @MockitoBean lateinit var mail: ResetMailDelivery
    private val lockEntered = AtomicReference<CountDownLatch?>()
    private val cardEntered = AtomicReference<CountDownLatch?>()
    private val oldPassword = "OldFixture123!"
    private val newPassword = "NewFixture123!"

    @BeforeEach fun setup() {
        val targetStore = AopTestUtils.getUltimateTargetObject<AuthStore>(store)
        doAnswer {
            if (Thread.currentThread().name == "p1-contender") lockEntered.get()?.countDown()
            it.callRealMethod()
        }.`when`(targetStore).lockUser(anyString())
        jdbc.execute("CREATE TABLE IF NOT EXISTS p1_test_cards(id VARCHAR(36) PRIMARY KEY, available BOOLEAN NOT NULL)")
        doAnswer {
            if (Thread.currentThread().name == "p1-contender") cardEntered.get()?.countDown()
            jdbc.query("SELECT available FROM p1_test_cards WHERE id=? FOR UPDATE",
                { rs, _ -> rs.getBoolean(1) }, it.getArgument<String>(0)).firstOrNull() ?: false
        }.`when`(visibility).lockAndCheckAvailable(anyString())
        doReturn(1).`when`(feed).prepare(anyString(), anyString(), anyString(), anyInt())
    }

    companion object {
        private val now = Instant.parse("2026-11-16T13:00:00Z")

        // Scheduled workers share this clock with the test threads.
        @JvmStatic fun fixedClock(): Clock = Clock.fixed(now, ZoneOffset.UTC)
    }

    private data class Account(val user: CurrentUser, val email: String, val auth: AuthResponse)
    private fun account(): Account {
        val email = "p1-${UUID.randomUUID()}@example.com"
        val response = auth.register(RegisterRequest(email, oldPassword), UUID.randomUUID().toString())
        return Account(CurrentUser(store.findUserByEmail(email)!!.id, true), email, response)
    }
    private fun resetToken(a: Account): String {
        resets.request(PasswordResetRequest(a.email))
        return tokens.unseal(jdbc.queryForObject("SELECT o.encrypted_token FROM auth_reset_outbox o " +
            "JOIN auth_password_resets r ON r.id=o.reset_id WHERE r.user_id=? ORDER BY r.created_at_ms DESC LIMIT 1",
            String::class.java, a.user.id)!!)
    }
    private fun result(action: () -> Any?): Any? = try { action() } catch (ex: ApiException) { ex.code }
    private fun activeCount(a: Account) = jdbc.queryForObject("SELECT COUNT(*) FROM auth_refresh_sessions " +
        "WHERE user_id=? AND revoked_at_ms IS NULL", Int::class.java, a.user.id)

    /** The leader is a real service operation in an outer transaction, held until the contender
     * reaches its production lock. No test acquires the account lock on behalf of a service.
     */
    private fun race(leader: () -> Unit, contender: () -> Any?): Any? {
        val pool = Executors.newSingleThreadExecutor { r -> Thread(r, "p1-contender") }
        val entered = CountDownLatch(1)
        lockEntered.set(entered)
        var pending: Future<Any?>? = null
        try {
            TransactionTemplate(manager).executeWithoutResult {
                leader()
                pending = pool.submit(Callable { result(contender) })
                assertTrue(entered.await(5, TimeUnit.SECONDS), "contender must reach the account lock")
                assertThrows(TimeoutException::class.java) { pending!!.get(150, TimeUnit.MILLISECONDS) }
            }
            return pending!!.get(10, TimeUnit.SECONDS)
        } finally {
            lockEntered.set(null)
            pool.shutdownNow()
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    @ParameterizedTest @ValueSource(strings = ["login", "refresh"])
    fun `reset commits first and rejects waiting old credentials`(operation: String) {
        val a = account()
        val token = resetToken(a)
        val outcome = race(
            { resets.confirm(PasswordResetConfirmRequest(token, newPassword)) },
            { if (operation == "login") auth.login(LoginRequest(a.email, oldPassword), "waiting-login")
              else auth.refresh(RefreshTokenRequest(a.auth.refreshToken), "waiting-refresh") },
        )
        assertEquals(if (operation == "login") ErrorCode.INVALID_CREDENTIALS else ErrorCode.INVALID_REFRESH_TOKEN, outcome)
        assertEquals(0, activeCount(a))
        auth.login(LoginRequest(a.email, newPassword), "new-login")
        assertEquals(1, activeCount(a))
    }

    @ParameterizedTest @ValueSource(strings = ["login", "refresh"])
    fun `issuance commits first and waiting reset revokes issued tokens and cached replay`(operation: String) {
        val a = account()
        val token = resetToken(a)
        var issued: AuthResponse? = null
        val outcome = race(
            { issued = if (operation == "login") auth.login(LoginRequest(a.email, oldPassword), "issue")
                else auth.refresh(RefreshTokenRequest(a.auth.refreshToken), "issue") },
            { resets.confirm(PasswordResetConfirmRequest(token, newPassword)) },
        )
        assertEquals(Unit, outcome)
        assertEquals(0, activeCount(a))
        val claims = tokens.verifyAccess(issued!!.accessToken, now.epochSecond)!!
        assertFalse(store.activeSession(claims.sessionId, a.user.id, now.toEpochMilli()))
        val replay = result { if (operation == "login") auth.login(LoginRequest(a.email, oldPassword), "issue")
            else auth.refresh(RefreshTokenRequest(a.auth.refreshToken), "issue") }
        assertEquals(if (operation == "login") ErrorCode.INVALID_CREDENTIALS else ErrorCode.INVALID_REFRESH_TOKEN, replay)
    }

    @Test fun `two reset confirmations consume token exactly once`() {
        val a = account()
        val token = resetToken(a)
        assertEquals(ErrorCode.INVALID_RESET_TOKEN, race(
            { resets.confirm(PasswordResetConfirmRequest(token, newPassword)) },
            { resets.confirm(PasswordResetConfirmRequest(token, "AnotherFixture123!")) },
        ))
        auth.login(LoginRequest(a.email, newPassword), "new-password")
    }

    @Test fun `concurrent reset requests enforce throttle under account lock`() {
        val a = account()
        assertTrue(race({ resets.request(PasswordResetRequest(a.email)) },
            { resets.request(PasswordResetRequest(a.email)) }) is PasswordResetAccepted)
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM auth_password_resets WHERE user_id=?", Int::class.java, a.user.id))
    }

    @ParameterizedTest @ValueSource(strings = ["consents", "birth", "reset", "request", "login", "refresh"])
    fun `deletion commits first and waiting writes do not resurrect account`(operation: String) {
        val a = account()
        val token = resetToken(a)
        // Existing onboarding row makes the pre-fix child-first ordering observable.
        users.saveBirthDate(a.user, BirthDateRequest(LocalDate.of(2000, 1, 1)))
        val outcome = race({ users.deleteAccount(a.user) }, {
            when (operation) {
                "consents" -> users.saveConsents(a.user, ConsentsRequest("test-1", "test-1", true, true))
                "birth" -> users.saveBirthDate(a.user, BirthDateRequest(LocalDate.of(2001, 1, 1)))
                "reset" -> resets.confirm(PasswordResetConfirmRequest(token, newPassword))
                "request" -> resets.request(PasswordResetRequest(a.email))
                "login" -> auth.login(LoginRequest(a.email, oldPassword), "deleted")
                else -> auth.refresh(RefreshTokenRequest(a.auth.refreshToken), "deleted")
            }
        })
        when (operation) {
            "request" -> assertTrue(outcome is PasswordResetAccepted && outcome.accepted)
            "login" -> assertEquals(ErrorCode.INVALID_CREDENTIALS, outcome)
            "refresh" -> assertEquals(ErrorCode.INVALID_REFRESH_TOKEN, outcome)
            "reset" -> assertEquals(ErrorCode.INVALID_RESET_TOKEN, outcome)
            else -> assertEquals(ErrorCode.RESOURCE_NOT_FOUND, outcome)
        }
        assertNull(store.findUserById(a.user.id))
        for (table in listOf("user_onboarding", "auth_password_resets", "auth_refresh_sessions")) {
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM $table WHERE user_id=?", Int::class.java, a.user.id))
        }
    }

    @ParameterizedTest @ValueSource(strings = ["consents", "birth", "reset", "request"])
    fun `child writes commit first and waiting deletion completes without deadlock`(operation: String) {
        val a = account()
        val token = resetToken(a)
        val outcome = race({
            when (operation) {
                "consents" -> users.saveConsents(a.user, ConsentsRequest("test-1", "test-1", true, true))
                "birth" -> users.saveBirthDate(a.user, BirthDateRequest(LocalDate.of(2000, 1, 1)))
                "reset" -> resets.confirm(PasswordResetConfirmRequest(token, newPassword))
                else -> resets.request(PasswordResetRequest(a.email))
            }
        }, { users.deleteAccount(a.user) })
        assertEquals(Unit, outcome)
        assertNull(store.findUserById(a.user.id))
    }

    @ParameterizedTest @ValueSource(strings = ["reset", "delete"])
    fun `stale outbox discovery cannot send after reset or deletion commits`(operation: String) {
        val a = account()
        val token = resetToken(a)
        val candidate = store.pendingResetMail(now.toEpochMilli(), 100).first { it.userId == a.user.id }
        val outcome = race({
            if (operation == "reset") resets.confirm(PasswordResetConfirmRequest(token, newPassword))
            else users.deleteAccount(a.user)
        }, { delivery.deliver(candidate) })
        assertEquals(Unit, outcome)
        verify(mail, never()).deliver(eq(a.email) ?: a.email, anyString())
    }

    @ParameterizedTest @ValueSource(strings = ["reset", "delete", "second_worker"])
    fun `inflight outbox delivery serializes with reset deletion and another worker`(operation: String) {
        val a = account()
        val token = resetToken(a)
        val candidate = store.pendingResetMail(now.toEpochMilli(), 100).first { it.userId == a.user.id }
        val sending = CountDownLatch(1)
        val releaseMail = CountDownLatch(1)
        doAnswer {
            sending.countDown()
            check(releaseMail.await(10, TimeUnit.SECONDS))
            null
        }.`when`(mail).deliver(eq(a.email) ?: a.email, anyString())
        val sender = Executors.newSingleThreadExecutor()
        val contender = Executors.newSingleThreadExecutor { r -> Thread(r, "p1-contender") }
        val entered = CountDownLatch(1)
        lockEntered.set(entered)
        try {
            val first = sender.submit(Callable { delivery.deliver(candidate) })
            assertTrue(sending.await(5, TimeUnit.SECONDS))
            val second = contender.submit(Callable {
                when (operation) {
                    "reset" -> resets.confirm(PasswordResetConfirmRequest(token, newPassword))
                    "delete" -> users.deleteAccount(a.user)
                    else -> delivery.deliver(candidate)
                }
            })
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertThrows(TimeoutException::class.java) { second.get(150, TimeUnit.MILLISECONDS) }
            releaseMail.countDown()
            first.get(10, TimeUnit.SECONDS)
            second.get(10, TimeUnit.SECONDS)
            verify(mail, times(1)).deliver(eq(a.email) ?: a.email, anyString())
            if (operation == "delete") assertNull(store.findUserById(a.user.id))
            if (operation == "reset") assertEquals(0, activeCount(a))
        } finally {
            releaseMail.countDown()
            lockEntered.set(null)
            sender.shutdownNow()
            contender.shutdownNow()
            assertTrue(sender.awaitTermination(10, TimeUnit.SECONDS))
            assertTrue(contender.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    @ParameterizedTest @ValueSource(strings = ["initial_finish", "finish_replay", "bootstrap"])
    fun `card deletion commits before waiting response and saved body is redacted`(path: String) {
        val a = account()
        val n = nights.recordWorry(a.user, WorryRequest(CategoryId.NONE, null))
        val s = sessions.create(a.user, n.id, CreateSessionRequest(3), "create")
        val id = UUID.randomUUID().toString()
        jdbc.update("INSERT INTO p1_test_cards(id,available) VALUES(?,TRUE)", id)
        val body = "private-test-body-$id"
        doReturn(CardResponse(id, true, CardKind.TEXT, CardTone.WARM, CategoryId.OTHER,
            AuthorType.EDITORIAL, null, TextCardContent(body), CardViewer(false, false, false)))
            .`when`(closing).choose(anyString(), anyString(), anyString(), any<FinishReason>() ?: FinishReason.LEAVE)
        val request = FinishRequest(FinishReason.LEAVE, 1, 1, 1, 1)
        if (path != "initial_finish") sessions.finish(a.user, s.id, request, "finish")
        val pool = Executors.newSingleThreadExecutor { r -> Thread(r, "p1-contender") }
        val entered = CountDownLatch(1)
        cardEntered.set(entered)
        var future: Future<CardResponse?>? = null
        try {
            TransactionTemplate(manager).executeWithoutResult {
                jdbc.update("UPDATE p1_test_cards SET available=FALSE WHERE id=?", id)
                future = pool.submit(Callable {
                    if (path == "bootstrap") snapshots.forUser(a.user.id, n.nightDate).lastSession!!.closingCard
                    else sessions.finish(a.user, s.id, request, "finish").closingCard
                })
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                assertThrows(TimeoutException::class.java) { future!!.get(150, TimeUnit.MILLISECONDS) }
            }
            val card = future!!.get(10, TimeUnit.SECONDS)!!
            assertFalse(card.available)
            assertNull(card.content)
            assertEquals(CardViewer(false, false, false), card.viewer)
            assertFalse(mapper.writeValueAsString(card).contains(body))
        } finally {
            cardEntered.set(null)
            pool.shutdownNow()
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS))
        }
    }
}
