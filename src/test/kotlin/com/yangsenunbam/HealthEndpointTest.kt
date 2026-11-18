package com.yangsenunbam

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*

@SpringBootTest(properties = ["management.endpoint.health.probes.enabled=true",
    "management.endpoint.health.group.readiness.include=readinessState,db"])
@AutoConfigureMockMvc
class HealthEndpointTest {
    @Autowired lateinit var mvc: MockMvc
    @Test fun `load balancer can read health without credentials or details`() {
        for (path in listOf("/actuator/health", "/actuator/health/readiness", "/actuator/health/liveness")) {
            mvc.perform(get(path)).andExpect(status().isOk)
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components").doesNotExist())
        }
        mvc.perform(get("/api/v1/me")).andExpect(status().isUnauthorized)
    }
}
