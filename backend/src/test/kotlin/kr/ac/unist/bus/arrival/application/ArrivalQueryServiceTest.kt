package kr.ac.unist.bus.arrival.application

import kr.ac.unist.bus.arrival.domain.Arrival
import kr.ac.unist.bus.arrival.domain.ArrivalBatch
import kr.ac.unist.bus.arrival.domain.ArrivalQueryResult
import kr.ac.unist.bus.arrival.domain.ArrivalStatus
import kr.ac.unist.bus.arrival.domain.Freshness
import kr.ac.unist.bus.collector.ArrivalProvider
import kr.ac.unist.bus.collector.ArrivalProviderUnavailableException
import kr.ac.unist.bus.config.ArrivalProperties
import kr.ac.unist.bus.stop.SupportedStop
import kr.ac.unist.bus.stop.UnistStopCatalog
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ArrivalQueryServiceTest {
    private val now = Instant.parse("2026-07-24T09:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val catalog = UnistStopCatalog()
    private val properties = ArrivalProperties(maxStaleAge = Duration.ofMinutes(10))

    @Test
    fun `provider success is cached as fresh data`() {
        val batch = batch(catalog.require("40234"), now)
        val cache = ArrivalSnapshotCache()
        val service = service(ArrivalProvider { _, _ -> batch }, cache)

        val result = service.query("40234")

        assertEquals(Freshness.FRESH, result.freshness)
        assertEquals(batch, cache.get("40234"))
    }

    @Test
    fun `fresh cache prevents repeated provider calls`() {
        val calls = AtomicInteger()
        val batch = batch(catalog.require("40234"), now)
        val cache = ArrivalSnapshotCache()
        val service = service(
            ArrivalProvider { _, _ ->
                calls.incrementAndGet()
                batch
            },
            cache,
        )

        val first = service.query("40234")
        val second = service.query("40234")

        assertEquals(1, calls.get())
        assertEquals(Freshness.FRESH, first.freshness)
        assertEquals(Freshness.FRESH, second.freshness)
        assertEquals(first.batch, second.batch)
    }

    @Test
    fun `cache refreshes when its ttl has elapsed`() {
        val calls = AtomicInteger()
        val cache = ArrivalSnapshotCache()
        val provider = ArrivalProvider { stop, observedAt ->
            calls.incrementAndGet()
            batch(stop, observedAt)
        }

        service(provider, cache).query("40234")
        service(
            provider,
            cache,
            Clock.offset(clock, Duration.ofSeconds(29)),
        ).query("40234")
        service(
            provider,
            cache,
            Clock.offset(clock, Duration.ofSeconds(30)),
        ).query("40234")

        assertEquals(2, calls.get())
    }

    @Test
    fun `concurrent queries share one provider refresh`() {
        val calls = AtomicInteger()
        val simultaneousProviderCalls = CountDownLatch(2)
        val startQueries = CountDownLatch(1)
        val cache = ArrivalSnapshotCache()
        val service = service(
            ArrivalProvider { stop, observedAt ->
                calls.incrementAndGet()
                simultaneousProviderCalls.countDown()
                simultaneousProviderCalls.await(200, TimeUnit.MILLISECONDS)
                batch(stop, observedAt)
            },
            cache,
        )
        val executor = Executors.newFixedThreadPool(6)

        try {
            val results = List(6) {
                executor.submit<ArrivalQueryResult> {
                    startQueries.await()
                    service.query("40234")
                }
            }
            startQueries.countDown()

            results.forEach { future ->
                assertEquals(Freshness.FRESH, future.get(2, TimeUnit.SECONDS).freshness)
            }
            assertEquals(1, calls.get())
        } finally {
            startQueries.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `concurrent provider failure is shared before stale fallback`() {
        val calls = AtomicInteger()
        val simultaneousProviderCalls = CountDownLatch(2)
        val startQueries = CountDownLatch(1)
        val cached = batch(catalog.require("40234"), now.minusSeconds(3 * 60))
        val cache = ArrivalSnapshotCache().apply { put(cached) }
        val service = service(
            ArrivalProvider { _, _ ->
                calls.incrementAndGet()
                simultaneousProviderCalls.countDown()
                simultaneousProviderCalls.await(200, TimeUnit.MILLISECONDS)
                throw ArrivalProviderUnavailableException()
            },
            cache,
        )
        val executor = Executors.newFixedThreadPool(6)

        try {
            val results = List(6) {
                executor.submit<ArrivalQueryResult> {
                    startQueries.await()
                    service.query("40234")
                }
            }
            startQueries.countDown()

            results.forEach { future ->
                val result = future.get(2, TimeUnit.SECONDS)
                assertEquals(Freshness.STALE, result.freshness)
                assertEquals(cached, result.batch)
            }
            assertEquals(1, calls.get())
        } finally {
            startQueries.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `provider failure falls back to recent cache`() {
        val batch = batch(catalog.require("40234"), now.minusSeconds(3 * 60))
        val cache = ArrivalSnapshotCache().apply { put(batch) }
        val service = service(
            ArrivalProvider { _, _ -> throw ArrivalProviderUnavailableException() },
            cache,
        )

        val result = service.query("40234")

        assertEquals(Freshness.STALE, result.freshness)
        assertEquals(batch, result.batch)
    }

    @Test
    fun `provider failure rejects an expired cache`() {
        val batch = batch(catalog.require("40234"), now.minusSeconds(11 * 60))
        val cache = ArrivalSnapshotCache().apply { put(batch) }
        val service = service(
            ArrivalProvider { _, _ -> throw ArrivalProviderUnavailableException() },
            cache,
        )

        assertFailsWith<ArrivalUnavailableException> {
            service.query("40234")
        }
    }

    private fun service(
        provider: ArrivalProvider,
        cache: ArrivalSnapshotCache,
        serviceClock: Clock = clock,
    ) = ArrivalQueryService(
        stopCatalog = catalog,
        provider = provider,
        cache = cache,
        properties = properties,
        clock = serviceClock,
    )

    private fun batch(stop: SupportedStop, observedAt: Instant): ArrivalBatch =
        ArrivalBatch(
            stop = stop,
            arrivals = listOf(
                Arrival(
                    tripId = "trip-513",
                    routeId = "route-513",
                    routeName = "513",
                    destination = "덕하 차고지",
                    scheduledArrivalAt = now.plusSeconds(8 * 60),
                    estimatedArrivalAt = now.plusSeconds(6 * 60),
                    observedAt = observedAt,
                    staleAt = observedAt.plusSeconds(2 * 60),
                    status = ArrivalStatus.APPROACHING,
                ),
            ),
            observedAt = observedAt,
            staleAt = observedAt.plusSeconds(2 * 60),
        )
}
