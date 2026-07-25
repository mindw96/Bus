package kr.ac.unist.bus.push.api

import kr.ac.unist.bus.config.PushProperties
import kr.ac.unist.bus.push.application.PushRefreshResult
import kr.ac.unist.bus.push.application.PushRefreshUseCase
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

@RestController
@RequestMapping("/internal/v1/push")
class PushRefreshController(
    private val refreshUseCase: PushRefreshUseCase,
    private val properties: PushProperties,
) {
    @PostMapping("/refresh")
    fun refresh(
        @RequestHeader(name = "X-UNIST-Push-Key", required = false)
        suppliedKey: String?,
    ): ResponseEntity<PushRefreshResult> {
        if (!properties.enabled || properties.refreshKey.isBlank()) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND)
        }
        if (!constantTimeEquals(properties.refreshKey, suppliedKey.orEmpty())) {
            throw ResponseStatusException(HttpStatus.UNAUTHORIZED)
        }
        val result = refreshUseCase.refresh()
        val status = if (result.failed > 0) {
            HttpStatus.BAD_GATEWAY
        } else {
            HttpStatus.OK
        }
        return ResponseEntity.status(status).body(result)
    }

    private fun constantTimeEquals(expected: String, actual: String): Boolean =
        MessageDigest.isEqual(
            expected.toByteArray(StandardCharsets.UTF_8),
            actual.toByteArray(StandardCharsets.UTF_8),
        )
}
