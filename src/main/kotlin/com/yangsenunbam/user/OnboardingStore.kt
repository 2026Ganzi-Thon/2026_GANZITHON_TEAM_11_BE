package com.yangsenunbam.user

import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import java.time.LocalDate

data class OnboardingProfile(
    val userId: String,
    val termsVersion: String? = null,
    val privacyVersion: String? = null,
    val birthDate: LocalDate? = null,
    val birthDateEditsUsed: Int = 0,
    val dayIntroAcknowledged: Boolean = false,
    val reminderEnabled: Boolean = false,
    val reminderLocalTime: String? = null,
) {
    fun complete(policy: LegalPolicy) = policy.hasVersions() &&
        termsVersion == policy.termsVersion && privacyVersion == policy.privacyVersion && birthDate != null
    fun editsRemaining() = if (birthDateEditsUsed >= 1) 0 else 1
    fun reminder() = ReminderResponse(reminderEnabled, reminderLocalTime)
}

/** Transactions serialize consent and birth-date changes on a per-user locked row. */
@Repository
class OnboardingStore(private val jdbc: JdbcTemplate) {
    private val mapper = RowMapper<OnboardingProfile> { rs, _ -> OnboardingProfile(
        userId = rs.getString("user_id"),
        termsVersion = rs.getString("terms_version"),
        privacyVersion = rs.getString("privacy_version"),
        birthDate = rs.getDate("birth_date")?.toLocalDate(),
        birthDateEditsUsed = rs.getInt("birth_date_edits_used"),
        dayIntroAcknowledged = rs.getBoolean("day_intro_acknowledged"),
        reminderEnabled = rs.getBoolean("reminder_enabled"),
        reminderLocalTime = rs.getString("reminder_local_time"),
    ) }
    private val columns = "user_id, terms_version, privacy_version, birth_date, birth_date_edits_used, " +
        "day_intro_acknowledged, reminder_enabled, reminder_local_time"

    fun find(userId: String): OnboardingProfile = jdbc.query(
        "SELECT $columns FROM user_onboarding WHERE user_id = ?", mapper, userId,
    ).firstOrNull() ?: OnboardingProfile(userId)

    fun completed(userId: String, policy: LegalPolicy) = find(userId).complete(policy)

    /** Lazily populate accounts registered before BE1 phase 2; must run in a transaction. */
    fun lock(userId: String): OnboardingProfile {
        try {
            jdbc.update(
                "INSERT INTO user_onboarding(user_id) SELECT ? WHERE NOT EXISTS " +
                    "(SELECT 1 FROM user_onboarding WHERE user_id = ?)", userId, userId,
            )
        } catch (_: DuplicateKeyException) {
            // A concurrent first write created it. Read it with a row lock below.
        }
        return jdbc.query("SELECT $columns FROM user_onboarding WHERE user_id = ? FOR UPDATE", mapper, userId)
            .firstOrNull() ?: error("User profile row could not be loaded")
    }

    fun saveConsents(userId: String, terms: String, privacy: String, atMillis: Long) {
        jdbc.update(
            "UPDATE user_onboarding SET terms_version = ?, privacy_version = ?, " +
                "terms_accepted_at_ms = ?, privacy_accepted_at_ms = ? WHERE user_id = ?",
            terms, privacy, atMillis, atMillis, userId,
        )
    }
    fun saveBirthDate(userId: String, date: LocalDate, edits: Int) {
        jdbc.update("UPDATE user_onboarding SET birth_date = ?, birth_date_edits_used = ? WHERE user_id = ?",
            java.sql.Date.valueOf(date), edits, userId)
    }
    fun acknowledgeDayIntro(userId: String) {
        jdbc.update("UPDATE user_onboarding SET day_intro_acknowledged = TRUE WHERE user_id = ?", userId)
    }
    fun saveReminder(userId: String, enabled: Boolean, localTime: String?) {
        jdbc.update("UPDATE user_onboarding SET reminder_enabled = ?, reminder_local_time = ? WHERE user_id = ?",
            enabled, localTime, userId)
    }
    fun synchronizeAuthGate(userId: String, policy: LegalPolicy) {
        // Compatibility with BE1 auth schema. Filter also re-checks current consent versions.
        jdbc.update("UPDATE auth_users SET onboarding_complete = ? WHERE id = ?", completed(userId, policy), userId)
    }
}
