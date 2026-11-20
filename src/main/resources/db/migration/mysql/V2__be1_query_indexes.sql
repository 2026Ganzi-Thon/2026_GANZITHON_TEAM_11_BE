CREATE INDEX ix_refresh_user ON auth_refresh_sessions(user_id, revoked_at_ms);
CREATE INDEX ix_reset_user_created ON auth_password_resets(user_id, created_at_ms);
CREATE INDEX ix_outbox_due ON auth_reset_outbox(sent_at_ms, next_attempt_at_ms);
CREATE INDEX ix_sessions_night ON be1_sessions(night_id, status, started_at_ms);
CREATE INDEX ix_sessions_expiry ON be1_sessions(status, closes_at_ms);
CREATE INDEX ix_sessions_user_expiry ON be1_sessions(user_id, status, closes_at_ms);
