package com.yangsenunbam.config

import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.core.env.Environment

/** Reject incomplete production configuration without printing any supplied values. */
@Configuration(proxyBeanMethods = false)
@Profile("prod")
class ProductionConfig(environment: Environment) {
    init {
        validate { environment.getProperty(it) }
    }

    companion object {
        internal fun validate(read: (String) -> String?) {
            for (name in listOf("DB_URL", "DB_USERNAME", "DB_PASSWORD", "DB_TRUSTSTORE_URL", "DB_TRUSTSTORE_PASSWORD")) {
                require(!read(name).isNullOrBlank()) { "Missing production setting: $name" }
            }
            // Driver properties (including TLS) are supplied separately, not overridden in the URL.
            require(Regex("jdbc:mysql://[A-Za-z0-9.-]+(?::[0-9]+)?/[A-Za-z0-9_]+").matches(read("DB_URL")!!)) {
                "DB_URL must be a MySQL host/database URL without credentials or query parameters"
            }
            require(read("DB_TRUSTSTORE_URL")!!.startsWith("file:/")) { "DB_TRUSTSTORE_URL must reference a local truststore" }
            require((read("AUTH_HMAC_SECRET")?.toByteArray(Charsets.UTF_8)?.size ?: 0) >= 32) {
                "AUTH_HMAC_SECRET must contain at least 32 UTF-8 bytes"
            }
        }
    }
}
