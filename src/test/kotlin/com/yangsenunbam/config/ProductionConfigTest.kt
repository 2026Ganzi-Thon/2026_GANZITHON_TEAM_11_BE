package com.yangsenunbam.config

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ProductionConfigTest {
    private fun settings() = mutableMapOf(
        "DB_URL" to "jdbc:mysql://db.example.invalid:3306/yangsebam",
        "DB_USERNAME" to "fixture", "DB_PASSWORD" to "fixture-password",
        "DB_TRUSTSTORE_URL" to "file:/tmp/fixture.p12", "DB_TRUSTSTORE_PASSWORD" to "fixture-store",
        "AUTH_HMAC_SECRET" to "fixture-secret-with-at-least-32-bytes",
    )
    @Test fun `production accepts explicit mysql configuration`() {
        ProductionConfig.validate(settings()::get)
    }
    @Test fun `missing configuration rejects startup without exposing values`() {
        for (name in settings().keys) {
            val values = settings().also { it.remove(name) }
            val error = assertThrows(IllegalArgumentException::class.java) { ProductionConfig.validate(values::get) }
            assertTrue(error.message!!.contains(name))
            assertFalse(error.message!!.contains("fixture-password"))
        }
    }
    @Test fun `production rejects H2 fallback and URL TLS overrides`() {
        for (url in listOf("jdbc:h2:mem:test", "jdbc:mysql://db/test?sslMode=DISABLED", "jdbc:mysql://user:password@db/test")) {
            val values = settings().also { it["DB_URL"] = url }
            assertThrows(IllegalArgumentException::class.java) { ProductionConfig.validate(values::get) }
        }
    }
}
