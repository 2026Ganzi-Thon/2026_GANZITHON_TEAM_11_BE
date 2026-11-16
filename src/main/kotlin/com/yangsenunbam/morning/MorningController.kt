package com.yangsenunbam.morning

import com.yangsenunbam.common.*
import com.yangsenunbam.security.CurrentUser
import com.yangsenunbam.flock.SheepResponse
import jakarta.validation.Valid
import jakarta.validation.constraints.*
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v1")
class MorningController(private val service: MorningService) {
    @GetMapping("/morning")
    @NoRequestBody
    fun morning(
        @AuthenticationPrincipal user: CurrentUser,
        @RequestParam(name = "night_id", required = false) nightId: String?
    ): MorningResponse = service.morning(user, nightId)

    @PutMapping("/nights/{night_id}/adoption")
    fun adopt(
        @AuthenticationPrincipal user: CurrentUser,
        @PathVariable("night_id") nightId: String,
        @Valid @RequestBody request: AdoptionRequest
    ): SheepResponse = service.adopt(user, nightId, request)

    @PutMapping("/nights/{night_id}/reflection")
    fun reflect(
        @AuthenticationPrincipal user: CurrentUser,
        @PathVariable("night_id") nightId: String,
        @Valid @RequestBody request: ReflectionRequest
    ): ReflectionResponse = service.reflect(user, nightId, request)
}
