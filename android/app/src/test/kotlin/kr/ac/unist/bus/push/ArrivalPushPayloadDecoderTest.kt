package kr.ac.unist.bus.push

import java.time.Instant
import kr.ac.unist.bus.model.ArrivalStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class ArrivalPushPayloadDecoderTest {
    @Test
    fun decodesDataOnlyArrivalUpdate() {
        val payload = ArrivalPushPayloadDecoder.decode(validUpdate())

        assertNotNull(payload)
        assertEquals(ArrivalPushType.ARRIVAL_UPDATE, payload?.type)
        assertEquals("session-1", payload?.trackingSessionId)
        assertEquals("40233", payload?.item?.stop?.id)
        assertEquals("713", payload?.item?.arrival?.routeName)
        assertEquals(ArrivalStatus.APPROACHING, payload?.item?.arrival?.status)
        assertEquals(
            Instant.parse("2026-07-24T05:30:00Z"),
            payload?.item?.arrival?.estimatedArrivalAt,
        )
    }

    @Test
    fun decodesTrackingEndWithoutArrivalFields() {
        val payload = ArrivalPushPayloadDecoder.decode(
            mapOf(
                "schemaVersion" to "1",
                "type" to "tracking_end",
                "trackingSessionId" to "session-1",
                "observedAt" to "2026-07-24T05:30:00Z",
                "reason" to "ARRIVED",
            ),
        )

        assertEquals(ArrivalPushType.TRACKING_END, payload?.type)
        assertNull(payload?.item)
        assertEquals("ARRIVED", payload?.reason)
    }

    @Test
    fun rejectsUnknownSchemaAndMalformedTimes() {
        assertNull(
            ArrivalPushPayloadDecoder.decode(
                validUpdate() + ("schemaVersion" to "2"),
            ),
        )
        assertNull(
            ArrivalPushPayloadDecoder.decode(
                validUpdate() + ("estimatedArrivalAt" to "not-an-instant"),
            ),
        )
    }

    @Test
    fun rejectsUpdateMissingRequiredDisplayFields() {
        assertNull(
            ArrivalPushPayloadDecoder.decode(
                validUpdate() - "destination",
            ),
        )
    }

    @Test
    fun mapsFutureStatusToUnknownWithoutDroppingUpdate() {
        val payload = ArrivalPushPayloadDecoder.decode(
            validUpdate() + ("status" to "BOARDING_SOON"),
        )

        assertEquals(ArrivalStatus.UNKNOWN, payload?.item?.arrival?.status)
    }

    private fun validUpdate(): Map<String, String> = mapOf(
        "schemaVersion" to "1",
        "type" to "arrival_update",
        "trackingSessionId" to "session-1",
        "tripId" to "trip-713",
        "routeId" to "route-713",
        "routeName" to "713",
        "stopId" to "40233",
        "stopName" to "울산과학기술원(기점)",
        "destination" to "덕하 차고지",
        "estimatedArrivalAt" to "2026-07-24T05:30:00Z",
        "observedAt" to "2026-07-24T05:22:00Z",
        "staleAt" to "2026-07-24T05:24:00Z",
        "status" to "approaching",
    )
}
