package kr.ac.unist.bus.push.apns

import kr.ac.unist.bus.arrival.domain.ArrivalStatus
import kr.ac.unist.bus.arrival.domain.Freshness
import kr.ac.unist.bus.config.PushProperties
import kr.ac.unist.bus.push.domain.PushMessage
import kr.ac.unist.bus.push.domain.PushMessageType
import kr.ac.unist.bus.tracking.domain.PushEnvironment
import kr.ac.unist.bus.tracking.domain.TrackedArrival
import org.junit.jupiter.api.Test
import tools.jackson.databind.ObjectMapper
import java.net.http.HttpRequest
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ApnsPushGatewayTest {
    @Test
    fun `development subscriptions use the APNs sandbox endpoint`() {
        val fixture = gateway(
            allowedEnvironment = PushEnvironment.DEVELOPMENT,
        )

        fixture.gateway.send(
            pushTarget = PUSH_TARGET,
            environment = PushEnvironment.DEVELOPMENT,
            message = message(),
        )

        val request = fixture.transport.singleRequest()
        assertEquals("api.sandbox.push.apple.com", request.uri().host)
        assertEquals("/3/device/$PUSH_TARGET", request.uri().path)
    }

    @Test
    fun `production subscriptions use the APNs production endpoint`() {
        val fixture = gateway(
            allowedEnvironment = PushEnvironment.PRODUCTION,
        )

        fixture.gateway.send(
            pushTarget = PUSH_TARGET,
            environment = PushEnvironment.PRODUCTION,
            message = message(),
        )

        val request = fixture.transport.singleRequest()
        assertEquals("api.push.apple.com", request.uri().host)
        assertEquals("/3/device/$PUSH_TARGET", request.uri().path)
    }

    @Test
    fun `mismatched APNs environment is rejected before authorization or dispatch`() {
        val fixture = gateway(
            allowedEnvironment = PushEnvironment.DEVELOPMENT,
        )

        val failure = assertFailsWith<ApnsEnvironmentMismatchException> {
            fixture.gateway.send(
                pushTarget = PUSH_TARGET,
                environment = PushEnvironment.PRODUCTION,
                message = message(),
            )
        }

        assertEquals(PushEnvironment.DEVELOPMENT, failure.allowedEnvironment)
        assertEquals(PushEnvironment.PRODUCTION, failure.requestedEnvironment)
        assertTrue(fixture.transport.requests.isEmpty())
        assertEquals(0, fixture.tokenProvider.callCount)
    }

    @Test
    fun `APNs rejection exposes only the status and validated reason`() {
        val hiddenResponseField = "response-body-must-not-escape"
        val fixture = gateway(
            allowedEnvironment = PushEnvironment.DEVELOPMENT,
            response = ApnsHttpResponse(
                statusCode = 400,
                body = """
                    {
                      "reason": "BadDeviceToken",
                      "details": "$hiddenResponseField"
                    }
                """.trimIndent(),
            ),
        )

        val failure = assertFailsWith<ApnsPushException> {
            fixture.gateway.send(
                pushTarget = PUSH_TARGET,
                environment = PushEnvironment.DEVELOPMENT,
                message = message(),
            )
        }

        assertEquals(400, failure.statusCode)
        assertEquals("BadDeviceToken", failure.reason)
        assertFalse(failure.message.orEmpty().contains(hiddenResponseField))
        assertFalse(failure.message.orEmpty().contains(PUSH_TARGET))
        assertFalse(failure.message.orEmpty().contains(TEST_AUTHORIZATION_TOKEN))
    }

    private fun gateway(
        allowedEnvironment: PushEnvironment,
        response: ApnsHttpResponse = ApnsHttpResponse(statusCode = 200, body = ""),
    ): GatewayFixture {
        val transport = RecordingTransport(response)
        val tokenProvider = RecordingTokenProvider()
        val properties = PushProperties(
            apns = PushProperties.Apns(
                enabled = true,
                bundleId = "kr.ac.unist.bus",
                allowedEnvironment = allowedEnvironment,
            ),
        )
        return GatewayFixture(
            gateway = ApnsPushGateway(
                properties = properties,
                tokenProvider = tokenProvider,
                payloadFactory = ApnsPayloadFactory(),
                objectMapper = ObjectMapper(),
                transport = transport,
            ),
            transport = transport,
            tokenProvider = tokenProvider,
        )
    }

    private fun message(): PushMessage {
        val now = Instant.parse("2026-07-25T09:00:00Z")
        return PushMessage(
            type = PushMessageType.ARRIVAL_UPDATE,
            trackingSessionId = UUID.fromString("00000000-0000-0000-0000-000000000301"),
            arrival = TrackedArrival(
                tripId = "trip-713",
                routeId = "route-713",
                routeName = "713",
                stopId = "40233",
                stopName = "울산과학기술원(기점)",
                destination = "덕하 차고지",
                estimatedArrivalAt = now.plusSeconds(7 * 60),
                observedAt = now,
                staleAt = now.plusSeconds(2 * 60),
                status = ArrivalStatus.APPROACHING,
                freshness = Freshness.FRESH,
            ),
            timestamp = now,
            expiresAt = now.plusSeconds(60 * 60),
        )
    }

    private data class GatewayFixture(
        val gateway: ApnsPushGateway,
        val transport: RecordingTransport,
        val tokenProvider: RecordingTokenProvider,
    )

    private class RecordingTransport(
        private val response: ApnsHttpResponse,
    ) : ApnsHttpTransport {
        val requests = mutableListOf<HttpRequest>()

        override fun send(request: HttpRequest): ApnsHttpResponse {
            requests += request
            return response
        }

        fun singleRequest(): HttpRequest {
            assertEquals(1, requests.size)
            return requests.single()
        }
    }

    private class RecordingTokenProvider : ApnsAuthorizationTokenProvider {
        var callCount = 0
            private set

        override fun token(): String {
            callCount++
            return TEST_AUTHORIZATION_TOKEN
        }
    }

    private companion object {
        const val PUSH_TARGET =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        const val TEST_AUTHORIZATION_TOKEN = "unit-test-authorization-token"
    }
}
