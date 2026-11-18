package com.yangsenunbam

import com.yangsenunbam.common.*
import com.yangsenunbam.night.WorrySafetyGateway
import com.yangsenunbam.session.*
import com.yangsenunbam.user.CrossDomainAccountDeletionPort
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest

@SpringBootTest(properties = ["app.safety.endpoint=", "app.safety.service-token="])
class Be1MissingIntegrationTest {
    @Autowired lateinit var feed: FeedPreparationPort
    @Autowired lateinit var visibility: ClosingCardVisibilityPort
    @Autowired lateinit var closing: ClosingCardPort
    @Autowired lateinit var morning: MorningCandidatePort
    @Autowired lateinit var deletion: CrossDomainAccountDeletionPort
    @Autowired lateinit var safety: WorrySafetyGateway

    @Test fun `missing integrations fail closed`() {
        val operations: List<() -> Unit> = listOf(
            { visibility.lockAndCheckAvailable("fixture") },
            { feed.prepare("fixture", "fixture", "fixture", 3) },
            { closing.choose("fixture", "fixture", "fixture", FinishReason.LEAVE) },
            { morning.hasCandidate("fixture", "fixture") },
            { deletion.deleteOwnedData("fixture") },
            { safety.check("fixture") },
        )
        operations.forEach { assertEquals(503, assertThrows(ApiException::class.java, it).code.status) }
    }
}
