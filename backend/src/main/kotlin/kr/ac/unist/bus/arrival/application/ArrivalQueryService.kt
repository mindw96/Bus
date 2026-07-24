package kr.ac.unist.bus.arrival.application

import kr.ac.unist.bus.arrival.domain.ArrivalBatch
import kr.ac.unist.bus.arrival.domain.ArrivalQueryResult
import kr.ac.unist.bus.arrival.domain.Freshness
import kr.ac.unist.bus.collector.ArrivalProvider
import kr.ac.unist.bus.collector.ArrivalProviderUnavailableException
import kr.ac.unist.bus.config.ArrivalProperties
import kr.ac.unist.bus.stop.SupportedStop
import kr.ac.unist.bus.stop.UnistStopCatalog
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ConcurrentHashMap

@Service
class ArrivalQueryService(
    private val stopCatalog: UnistStopCatalog,
    private val provider: ArrivalProvider,
    private val cache: ArrivalSnapshotCache,
    private val properties: ArrivalProperties,
    private val clock: Clock,
) {
    private val inFlightRefreshes = ConcurrentHashMap<String, CompletableFuture<ArrivalBatch>>()

    fun query(stopId: String): ArrivalQueryResult {
        val stop = stopCatalog.require(stopId)
        val now = clock.instant()
        cache.get(stopId)
            ?.takeIf { isFresh(it, now) }
            ?.let { return freshResult(it, now) }

        return try {
            val batch = refresh(stop)
            freshResult(batch, clock.instant())
        } catch (_: ArrivalProviderUnavailableException) {
            val fallbackTime = clock.instant()
            val cached = cache.get(stopId)
            if (
                cached != null &&
                fallbackTime <= cached.observedAt.plus(properties.maxStaleAge)
            ) {
                ArrivalQueryResult(
                    serverTime = fallbackTime,
                    freshness = Freshness.STALE,
                    batch = cached,
                )
            } else {
                throw ArrivalUnavailableException(stopId)
            }
        }
    }

    private fun refresh(stop: SupportedStop): ArrivalBatch {
        val ownedRefresh = CompletableFuture<ArrivalBatch>()
        val existingRefresh = inFlightRefreshes.putIfAbsent(stop.id, ownedRefresh)
        if (existingRefresh != null) {
            return await(existingRefresh)
        }

        try {
            val refreshTime = clock.instant()
            val batch = cache.get(stop.id)
                ?.takeIf { isFresh(it, refreshTime) }
                ?: provider.fetch(stop, refreshTime).also(cache::put)
            ownedRefresh.complete(batch)
            return batch
        } catch (failure: Throwable) {
            ownedRefresh.completeExceptionally(failure)
            throw failure
        } finally {
            inFlightRefreshes.remove(stop.id, ownedRefresh)
        }
    }

    private fun await(refresh: CompletableFuture<ArrivalBatch>): ArrivalBatch =
        try {
            refresh.join()
        } catch (failure: CompletionException) {
            val cause = failure.cause
            if (cause is RuntimeException) {
                throw cause
            }
            throw failure
        }

    private fun isFresh(batch: ArrivalBatch, now: Instant): Boolean {
        val cacheExpiresAt = minOf(
            batch.observedAt.plus(properties.cacheTtl),
            batch.staleAt,
        )
        return now.isBefore(cacheExpiresAt)
    }

    private fun freshResult(
        batch: ArrivalBatch,
        serverTime: Instant,
    ) = ArrivalQueryResult(
        serverTime = serverTime,
        freshness = Freshness.FRESH,
        batch = batch,
    )
}

class ArrivalUnavailableException(
    val stopId: String,
) : RuntimeException("사용 가능한 도착 정보가 없습니다.")
