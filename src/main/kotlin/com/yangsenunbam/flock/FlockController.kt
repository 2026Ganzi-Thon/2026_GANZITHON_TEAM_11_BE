package com.yangsenunbam.flock

import com.yangsenunbam.common.*
import com.yangsenunbam.security.CurrentUser
import jakarta.validation.Valid
import jakarta.validation.constraints.*
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v1")
class FlockController(private val service: FlockService) {
    @GetMapping("/sheep")
    @NoRequestBody
    fun list(
        @AuthenticationPrincipal user: CurrentUser,
        @RequestParam(required = false) cursor: String?,
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) limit: Int
    ): PageResponse<SheepResponse> = service.list(user, cursor, limit)

    @GetMapping("/sheep/{sheep_id}")
    @NoRequestBody
    fun detail(
        @AuthenticationPrincipal user: CurrentUser,
        @PathVariable("sheep_id") sheepId: String
    ): SheepDetailResponse = service.detail(user, sheepId)

    @GetMapping("/stickers")
    @NoRequestBody
    fun stickers(
        @AuthenticationPrincipal user: CurrentUser
    ): ItemsResponse<StickerResponse> = service.stickers(user)

    @PutMapping("/sheep/{sheep_id}/stickers")
    fun saveStickers(
        @AuthenticationPrincipal user: CurrentUser,
        @PathVariable("sheep_id") sheepId: String,
        @Valid @RequestBody request: SaveStickersRequest
    ): SheepResponse = service.saveStickers(user, sheepId, request)
}
