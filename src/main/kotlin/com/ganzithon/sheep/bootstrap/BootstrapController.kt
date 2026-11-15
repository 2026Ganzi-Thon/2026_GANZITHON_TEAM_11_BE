package com.ganzithon.sheep.bootstrap

import com.ganzithon.sheep.common.*
import com.ganzithon.sheep.security.CurrentUser
import jakarta.validation.Valid
import jakarta.validation.constraints.*
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v1")
class BootstrapController(private val service: BootstrapService) {
    @GetMapping("/bootstrap")
    @NoRequestBody
    fun bootstrap(
        @AuthenticationPrincipal user: CurrentUser
    ): BootstrapResponse = service.bootstrap(user)
}
