package com.yangsenunbam

import com.yangsenunbam.common.*
import com.yangsenunbam.night.WorrySafetyGateway
import com.yangsenunbam.session.*
import com.yangsenunbam.user.CrossDomainAccountDeletionPort
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext

@SpringBootTest(properties = ["app.safety.mode=http", "app.safety.endpoint=", "app.safety.service-token="])
class Be1MissingIntegrationTest {
    @Autowired lateinit var context: ApplicationContext
    @Autowired lateinit var deletion: CrossDomainAccountDeletionPort
    @Autowired lateinit var safety: WorrySafetyGateway

    @Test fun `missing integrations fail closed`() {
        val operations: List<() -> Unit> = listOf(
            { deletion.deleteOwnedData("fixture") },
            { safety.check("fixture") },
        )
        operations.forEach { assertEquals(503, assertThrows(ApiException::class.java, it).code.status) }
    }

    @Test fun `H2 does not register default failing session ports`() {
        listOf(FeedPreparationPort::class.java, ClosingCardPort::class.java,
            ClosingCardVisibilityPort::class.java, MorningCandidatePort::class.java).forEach {
            assertTrue(context.getBeansOfType(it).isEmpty())
        }
    }
}
