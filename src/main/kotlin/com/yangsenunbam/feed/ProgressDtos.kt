package com.yangsenunbam.feed

import com.fasterxml.jackson.annotation.JsonIgnore
import jakarta.validation.constraints.AssertTrue
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min

data class ProgressRequest(
    @field:Min(1) @field:Max(30) val position: Int,
    @field:Min(1) @field:Max(30) val furthestPosition: Int,
    @field:Min(1) val sequence: Long,
    @field:Min(1) val writerEpoch: Long,
) {
    @AssertTrue @JsonIgnore
    fun isPositionOrdered(): Boolean = position <= furthestPosition
}

data class ProgressResponse(val position: Int, val furthestPosition: Int, val sequence: Long, val writerEpoch: Long)
