package com.yangsenunbam

import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

/** Runs the same production-service race tests with real MySQL locks and versioned DDL. */
class Be1MySqlIntegrationTest : Be1P1ConcurrencyTest() {
    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun database(registry: DynamicPropertyRegistry) {
            val url = System.getenv("MYSQL_TEST_URL") ?: error("Set MYSQL_TEST_URL for mysqlTest")
            require(Regex("jdbc:mysql://(?:localhost|127\\.0\\.0\\.1):[0-9]+/[A-Za-z0-9_]+_test(?:\\?.*)?").matches(url)) {
                "mysqlTest requires a local/tunnel URL with a dedicated *_test database"
            }
            registry.add("spring.datasource.url") { url }
            registry.add("spring.datasource.username") { System.getenv("MYSQL_TEST_USERNAME") ?: "root" }
            registry.add("spring.datasource.password") { System.getenv("MYSQL_TEST_PASSWORD") ?: "" }
            registry.add("spring.datasource.driver-class-name") { "com.mysql.cj.jdbc.Driver" }
            registry.add("spring.datasource.hikari.transaction-isolation") { "TRANSACTION_READ_COMMITTED" }
            registry.add("spring.sql.init.mode") { "never" }
            registry.add("spring.flyway.enabled") { "true" }
            registry.add("spring.flyway.locations") { "classpath:db/migration/mysql" }
            registry.add("spring.flyway.baseline-on-migrate") { "false" }
            registry.add("spring.flyway.clean-disabled") { "true" }
        }
    }
}
