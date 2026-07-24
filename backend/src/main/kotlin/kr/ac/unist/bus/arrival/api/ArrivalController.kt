package kr.ac.unist.bus.arrival.api

import kr.ac.unist.bus.arrival.application.ArrivalQueryService
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/stops")
class ArrivalController(
    private val queryService: ArrivalQueryService,
) {
    @GetMapping("/{stopId}/arrivals")
    fun arrivals(
        @PathVariable stopId: String,
    ): ResponseEntity<ArrivalResponse> {
        val result = queryService.query(stopId)
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .header("X-Data-Freshness", result.freshness.name)
            .body(ArrivalResponse.from(result))
    }
}
