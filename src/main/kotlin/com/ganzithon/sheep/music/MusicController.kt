package com.ganzithon.sheep.music

import com.ganzithon.sheep.common.*
import com.ganzithon.sheep.security.CurrentUser
import jakarta.validation.Valid
import jakarta.validation.constraints.*
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v1")
class MusicController(private val service: MusicService) {
    @GetMapping("/music/search")
    @NoRequestBody
    fun search(
        @AuthenticationPrincipal user: CurrentUser,
        @RequestParam @NotBlank @TextLength(min = 1, max = 100) q: String,
        @RequestParam(defaultValue = "20") @Min(1) @Max(50) limit: Int
    ): ItemsResponse<TrackResponse> = service.search(user, q, limit)
}
