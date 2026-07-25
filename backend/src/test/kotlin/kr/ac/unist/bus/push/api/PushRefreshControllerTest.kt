package kr.ac.unist.bus.push.api

import kr.ac.unist.bus.config.PushProperties
import kr.ac.unist.bus.push.application.PushRefreshResult
import kr.ac.unist.bus.push.application.PushRefreshUseCase
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PushRefreshControllerTest {
    @Test
    fun `returns bad gateway when any refresh operation failed`() {
        val result = PushRefreshResult(inspected = 3, updated = 1, ended = 0, failed = 2)
        val controller = controller(result)

        val response = controller.refresh(refreshKey)

        assertEquals(HttpStatus.BAD_GATEWAY, response.statusCode)
        assertEquals(result, response.body)
    }

    @Test
    fun `returns ok when refresh completed without failures`() {
        val result = PushRefreshResult(inspected = 1, updated = 1, ended = 0, failed = 0)
        val controller = controller(result)

        val response = controller.refresh(refreshKey)

        assertEquals(HttpStatus.OK, response.statusCode)
        assertEquals(result, response.body)
    }

    @Test
    fun `rejects a request without the configured worker key`() {
        val failure = assertFailsWith<ResponseStatusException> {
            controller(PushRefreshResult(0, 0, 0, 0)).refresh(null)
        }

        assertEquals(HttpStatus.UNAUTHORIZED, failure.statusCode)
    }

    private fun controller(result: PushRefreshResult) = PushRefreshController(
        refreshUseCase = PushRefreshUseCase { result },
        properties = PushProperties(
            enabled = true,
            refreshKey = refreshKey,
        ),
    )

    private companion object {
        const val refreshKey = "worker-refresh-key"
    }
}
