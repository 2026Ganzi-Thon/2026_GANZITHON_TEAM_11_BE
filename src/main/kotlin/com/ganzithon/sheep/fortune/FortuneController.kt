package com.ganzithon.sheep.fortune

import com.ganzithon.sheep.common.*
import com.ganzithon.sheep.security.CurrentUser
import jakarta.validation.Valid
import jakarta.validation.constraints.*
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v1")
class FortuneController(private val service: FortuneService) {
    @GetMapping("/fortune/today")
    @NoRequestBody
    fun today(
        @AuthenticationPrincipal user: CurrentUser
    ): FortuneResponse = service.today(user)
}
