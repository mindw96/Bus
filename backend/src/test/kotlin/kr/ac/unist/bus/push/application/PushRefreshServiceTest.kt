package kr.ac.unist.bus.push.application

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import kr.ac.unist.bus.arrival.application.ArrivalQueryService
import kr.ac.unist.bus.arrival.application.ArrivalSnapshotCache
import kr.ac.unist.bus.arrival.domain.Arrival
import kr.ac.unist.bus.arrival.domain.ArrivalBatch
import kr.ac.unist.bus.arrival.domain.ArrivalStatus
import kr.ac.unist.bus.arrival.domain.Freshness
import kr.ac.unist.bus.collector.ArrivalProvider
import kr.ac.unist.bus.config.ArrivalProperties
import kr.ac.unist.bus.config.PushProperties
import kr.ac.unist.bus.push.domain.PushMessage
import kr.ac.unist.bus.push.domain.PushMessageType
import kr.ac.unist.bus.push.domain.TrackingEndReason
import kr.ac.unist.bus.push.port.PushGateway
import kr.ac.unist.bus.push.port.PushGatewayRegistry
import kr.ac.unist.bus.stop.SupportedStop
import kr.ac.unist.bus.stop.UnistStopCatalog
import kr.ac.unist.bus.tracking.domain.PushEnvironment
import kr.ac.unist.bus.tracking.domain.TrackingPlatform
import kr.ac.unist.bus.tracking.domain.TrackingState
import kr.ac.unist.bus.tracking.domain.TrackingSubscription
import kr.ac.unist.bus.tracking.repository.InMemoryTrackingSubscriptionRepository
import kr.ac.unist.bus.tracking.security.AesGcmPushTargetCipher
import kr.ac.unist.bus.tracking.security.PushTargetCipher
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PushRefreshServiceTest {
    private val now = Instant.parse("2026-07-24T09:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val catalog = UnistStopCatalog()
    private val pushProperties = PushProperties(
        etaChangeThreshold = Duration.ofSeconds(60),
        matchWindow = Duration.ofMinutes(3),
        iosHeartbeatInterval = Duration.ofSeconds(60),
        deliveryClaimLease = Duration.ofSeconds(30),
    )
    private val cipher: PushTargetCipher = AesGcmPushTargetCipher(ByteArray(32) { 7 })

    @Test
    fun `stable iOS ETA sends once at heartbeat boundary and releases its claim`() {
        val eta = now.plusSeconds(10 * 60)
        val repository = InMemoryTrackingSubscriptionRepository()
        val gateway = RecordingGateway()
        val original = subscription(
            eta = eta,
            lastDeliveredAt = now.minusSeconds(60),
        )
        repository.mutate(original.trackingSessionId) { original }
        val service = service(
            repository = repository,
            gateway = gateway,
            provider = provider(
                arrival(
                    tripId = original.tripId,
                    routeId = original.routeId,
                    eta = eta,
                ),
            ),
        )

        val first = service.refresh()
        val second = service.refresh()

        assertEquals(PushRefreshResult(inspected = 1, updated = 1, ended = 0, failed = 0), first)
        assertEquals(PushRefreshResult(inspected = 1, updated = 0, ended = 0, failed = 0), second)
        val delivery = gateway.deliveries().single()
        assertEquals("apns-live-activity-token", delivery.pushTarget)
        assertEquals(PushMessageType.ARRIVAL_UPDATE, delivery.message.type)
        assertNull(delivery.message.reason)

        val stored = repository.findById(original.trackingSessionId)!!
        assertEquals(now, stored.lastDeliveredAt)
        assertEquals(eta, stored.lastDeliveredEta)
        assertNull(stored.deliveryClaimId)
        assertNull(stored.deliveryClaimUntil)
        assertEquals(TrackingState.ACTIVE, stored.state)
    }

    @Test
    fun `missing trip after ETA ends tracking instead of attaching the next same-route bus`() {
        val originalEta = now.minusSeconds(1)
        val repository = InMemoryTrackingSubscriptionRepository()
        val gateway = RecordingGateway()
        val original = subscription(eta = originalEta)
        repository.mutate(original.trackingSessionId) { original }
        val nextBus = arrival(
            tripId = "trip-713-next",
            routeId = original.routeId,
            eta = now.plusSeconds(2 * 60),
        )
        val service = service(
            repository = repository,
            gateway = gateway,
            provider = provider(nextBus),
        )

        val first = service.refresh()
        val second = service.refresh()

        assertEquals(PushRefreshResult(inspected = 1, updated = 0, ended = 1, failed = 0), first)
        assertEquals(PushRefreshResult(inspected = 0, updated = 0, ended = 0, failed = 0), second)
        val delivery = gateway.deliveries().single()
        assertEquals(PushMessageType.TRACKING_END, delivery.message.type)
        assertEquals(TrackingEndReason.ARRIVED, delivery.message.reason)
        assertEquals(original.tripId, delivery.message.arrival.tripId)
        assertEquals(ArrivalStatus.ARRIVED, delivery.message.arrival.status)

        val stored = repository.findById(original.trackingSessionId)!!
        assertEquals(TrackingState.ENDED, stored.state)
        assertEquals(original.tripId, stored.lastMatchedArrival?.tripId)
        assertNull(stored.deliveryClaimId)
        assertNull(stored.deliveryClaimUntil)
    }

    @Test
    fun `concurrent workers claim one subscription and send only once`() {
        val eta = now.plusSeconds(10 * 60)
        val repository = InMemoryTrackingSubscriptionRepository()
        val gateway = RecordingGateway()
        val original = subscription(
            eta = eta,
            lastDeliveredAt = now.minusSeconds(60),
        )
        repository.mutate(original.trackingSessionId) { original }
        val providerBarrier = CyclicBarrier(2)
        val services = List(2) {
            service(
                repository = repository,
                gateway = gateway,
                provider = ArrivalProvider { stop, observedAt ->
                    providerBarrier.await(2, TimeUnit.SECONDS)
                    batch(
                        stop = stop,
                        observedAt = observedAt,
                        arrivals = listOf(
                            arrival(
                                tripId = original.tripId,
                                routeId = original.routeId,
                                eta = eta,
                                observedAt = observedAt,
                            ),
                        ),
                    )
                },
            )
        }
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)

        try {
            val results = services.map { worker ->
                executor.submit<PushRefreshResult> {
                    start.await()
                    worker.refresh()
                }
            }
            start.countDown()
            val completed = results.map { it.get(3, TimeUnit.SECONDS) }

            assertEquals(1, completed.sumOf(PushRefreshResult::updated))
            assertEquals(0, completed.sumOf(PushRefreshResult::failed))
            assertEquals(1, gateway.deliveries().size)
            val stored = repository.findById(original.trackingSessionId)!!
            assertNull(stored.deliveryClaimId)
            assertNull(stored.deliveryClaimUntil)
        } finally {
            start.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `failed delivery is counted and logged without push credentials`() {
        val eta = now.plusSeconds(10 * 60)
        val repository = InMemoryTrackingSubscriptionRepository()
        val original = subscription(
            eta = eta,
            lastDeliveredAt = now.minusSeconds(60),
        )
        repository.mutate(original.trackingSessionId) { original }
        val service = service(
            repository = repository,
            gateway = FailingGateway(),
            provider = provider(
                arrival(
                    tripId = original.tripId,
                    routeId = original.routeId,
                    eta = eta,
                ),
            ),
        )
        val logger = LoggerFactory.getLogger(PushRefreshService::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().also {
            it.start()
            logger.addAppender(it)
        }

        val result = try {
            service.refresh()
        } finally {
            logger.detachAppender(appender)
            appender.stop()
        }

        assertEquals(
            PushRefreshResult(inspected = 1, updated = 0, ended = 0, failed = 1),
            result,
        )
        val logs = appender.list.joinToString("\n", transform = ILoggingEvent::getFormattedMessage)
        assertTrue(logs.contains("event=push_delivery_failed"))
        assertTrue(logs.contains("trackingSessionId=${original.trackingSessionId}"))
        assertTrue(logs.contains("platform=IOS_LIVE_ACTIVITY"))
        assertTrue(logs.contains("failureType=IllegalStateException"))
        assertTrue(logs.contains("event=push_refresh_completed"))
        assertTrue(logs.contains("failed=1"))
        assertFalse(logs.contains("apns-live-activity-token"))
        assertFalse(logs.contains(original.installationIdentifier))

        val stored = repository.findById(original.trackingSessionId)!!
        assertNull(stored.deliveryClaimId)
        assertNull(stored.deliveryClaimUntil)
        assertEquals(TrackingState.ACTIVE, stored.state)
    }

    private fun service(
        repository: InMemoryTrackingSubscriptionRepository,
        gateway: PushGateway,
        provider: ArrivalProvider,
    ): PushRefreshService {
        val queryService = ArrivalQueryService(
            stopCatalog = catalog,
            provider = provider,
            cache = ArrivalSnapshotCache(),
            properties = ArrivalProperties(),
            clock = clock,
        )
        return PushRefreshService(
            repository = repository,
            queryService = queryService,
            matcher = TrackedArrivalMatcher(pushProperties),
            decisionPolicy = PushDecisionPolicy(pushProperties),
            gateways = PushGatewayRegistry(listOf(gateway)),
            cipher = cipher,
            stopCatalog = catalog,
            properties = pushProperties,
            clock = clock,
        )
    }

    private fun subscription(
        eta: Instant,
        lastDeliveredAt: Instant? = now.minusSeconds(30),
    ) = TrackingSubscription(
        trackingSessionId = UUID.fromString("00000000-0000-0000-0000-000000000101"),
        installationIdentifier = "hmac-sha256:example",
        platform = TrackingPlatform.IOS_LIVE_ACTIVITY,
        pushTarget = cipher.encrypt("apns-live-activity-token"),
        pushEnvironment = PushEnvironment.DEVELOPMENT,
        tripId = "trip-713-original",
        routeId = "route-713",
        routeName = "713",
        stopId = "40233",
        initialEstimatedArrivalAt = eta,
        expiresAt = now.plusSeconds(2 * 60 * 60),
        state = TrackingState.ACTIVE,
        createdAt = now.minusSeconds(5 * 60),
        updatedAt = now.minusSeconds(30),
        lastMatchedArrival = null,
        lastDeliveredEta = eta,
        lastDeliveredStatus = ArrivalStatus.APPROACHING,
        lastDeliveredFreshness = Freshness.FRESH,
        lastDeliveredAt = lastDeliveredAt,
    )

    private fun provider(vararg arrivals: Arrival): ArrivalProvider =
        ArrivalProvider { stop, observedAt ->
            batch(
                stop = stop,
                observedAt = observedAt,
                arrivals = arrivals.map {
                    it.copy(
                        observedAt = observedAt,
                        staleAt = observedAt.plusSeconds(2 * 60),
                    )
                },
            )
        }

    private fun batch(
        stop: SupportedStop,
        observedAt: Instant,
        arrivals: List<Arrival>,
    ) = ArrivalBatch(
        stop = stop,
        arrivals = arrivals,
        observedAt = observedAt,
        staleAt = observedAt.plusSeconds(2 * 60),
    )

    private fun arrival(
        tripId: String,
        routeId: String,
        eta: Instant,
        observedAt: Instant = now,
    ) = Arrival(
        tripId = tripId,
        routeId = routeId,
        routeName = routeId.removePrefix("route-"),
        destination = "덕하 차고지",
        scheduledArrivalAt = eta,
        estimatedArrivalAt = eta,
        observedAt = observedAt,
        staleAt = observedAt.plusSeconds(2 * 60),
        status = ArrivalStatus.APPROACHING,
    )

    private data class Delivery(
        val pushTarget: String,
        val environment: PushEnvironment,
        val message: PushMessage,
    )

    private class RecordingGateway : PushGateway {
        override val platform = TrackingPlatform.IOS_LIVE_ACTIVITY
        private val sent = Collections.synchronizedList(mutableListOf<Delivery>())

        override fun send(
            pushTarget: String,
            environment: PushEnvironment,
            message: PushMessage,
        ) {
            sent += Delivery(pushTarget, environment, message)
        }

        fun deliveries(): List<Delivery> = synchronized(sent) {
            sent.toList()
        }
    }

    private class FailingGateway : PushGateway {
        override val platform = TrackingPlatform.IOS_LIVE_ACTIVITY

        override fun send(
            pushTarget: String,
            environment: PushEnvironment,
            message: PushMessage,
        ) {
            throw IllegalStateException("delivery failed for $pushTarget")
        }
    }
}
