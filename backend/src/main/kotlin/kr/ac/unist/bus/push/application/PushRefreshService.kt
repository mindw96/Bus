package kr.ac.unist.bus.push.application

import kr.ac.unist.bus.arrival.application.ArrivalQueryService
import kr.ac.unist.bus.arrival.domain.ArrivalStatus
import kr.ac.unist.bus.arrival.domain.Freshness
import kr.ac.unist.bus.config.PushProperties
import kr.ac.unist.bus.push.domain.PushDecision
import kr.ac.unist.bus.push.domain.PushMessage
import kr.ac.unist.bus.push.domain.PushMessageType
import kr.ac.unist.bus.push.domain.TrackingEndReason
import kr.ac.unist.bus.push.port.PushGatewayRegistry
import kr.ac.unist.bus.stop.UnistStopCatalog
import kr.ac.unist.bus.tracking.domain.TrackedArrival
import kr.ac.unist.bus.tracking.domain.TrackingState
import kr.ac.unist.bus.tracking.domain.TrackingSubscription
import kr.ac.unist.bus.tracking.repository.TrackingSubscriptionRepository
import kr.ac.unist.bus.tracking.security.PushTargetCipher
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant
import java.util.UUID

fun interface PushRefreshUseCase {
    fun refresh(): PushRefreshResult
}

data class PushRefreshResult(
    val inspected: Int,
    val updated: Int,
    val ended: Int,
    val failed: Int,
)

@Service
class PushRefreshService(
    private val repository: TrackingSubscriptionRepository,
    private val queryService: ArrivalQueryService,
    private val matcher: TrackedArrivalMatcher,
    private val decisionPolicy: PushDecisionPolicy,
    private val gateways: PushGatewayRegistry,
    private val cipher: PushTargetCipher,
    private val stopCatalog: UnistStopCatalog,
    private val properties: PushProperties,
    private val clock: Clock,
) : PushRefreshUseCase {
    override fun refresh(): PushRefreshResult {
        val now = clock.instant()
        val subscriptions = repository.findAllActive()
        var updated = 0
        var ended = 0
        var failed = 0

        val expired = subscriptions.filter { !now.isBefore(it.expiresAt) }
        expired.forEach { subscription ->
            when (
                deliver(
                    subscription,
                    fallbackArrival(subscription, now),
                    PushDecision.End(TrackingEndReason.EXPIRED),
                    now,
                )
            ) {
                DeliveryResult.SENT -> ended++
                DeliveryResult.FAILED -> failed++
                DeliveryResult.SKIPPED -> Unit
            }
        }

        subscriptions.asSequence()
            .filter { now.isBefore(it.expiresAt) }
            .groupBy(TrackingSubscription::stopId)
            .forEach { (stopId, stopSubscriptions) ->
                val result = try {
                    queryService.query(stopId)
                } catch (failure: RuntimeException) {
                    logger.warn(
                        "event=push_refresh_arrival_query_failed stopId={} " +
                            "subscriptionCount={} failureType={}",
                        stopId,
                        stopSubscriptions.size,
                        failure.javaClass.simpleName,
                    )
                    failed += stopSubscriptions.size
                    return@forEach
                }
                stopSubscriptions.forEach subscriptionLoop@{ subscription ->
                    val evaluationTime = maxOf(now, result.serverTime)
                    val arrival = matcher.match(subscription, result)
                    if (arrival == null) {
                        if (matcher.isMissingAfterEta(subscription, evaluationTime)) {
                            val arrived = fallbackArrival(subscription, evaluationTime).copy(
                                observedAt = evaluationTime,
                                staleAt = evaluationTime,
                                status = ArrivalStatus.ARRIVED,
                                freshness = result.freshness,
                            )
                            when (
                                deliver(
                                    subscription,
                                    arrived,
                                    PushDecision.End(TrackingEndReason.ARRIVED),
                                    evaluationTime,
                                )
                            ) {
                                DeliveryResult.SENT -> ended++
                                DeliveryResult.FAILED -> failed++
                                DeliveryResult.SKIPPED -> Unit
                            }
                        }
                        return@subscriptionLoop
                    }
                    val withLatestMatch = subscription.copy(lastMatchedArrival = arrival)
                    val decision = decisionPolicy.decide(
                        withLatestMatch,
                        arrival,
                        evaluationTime,
                    )
                    when (decision) {
                        PushDecision.None -> {
                            repository.compareAndMutate(
                                subscription.trackingSessionId,
                                subscription.revision,
                            ) { current ->
                                current.copy(
                                    lastMatchedArrival = arrival,
                                    updatedAt = evaluationTime,
                                )
                            }
                        }
                        PushDecision.Update -> {
                            when (deliver(subscription, arrival, decision, evaluationTime)) {
                                DeliveryResult.SENT -> updated++
                                DeliveryResult.FAILED -> failed++
                                DeliveryResult.SKIPPED -> Unit
                            }
                        }
                        is PushDecision.End -> {
                            when (deliver(subscription, arrival, decision, evaluationTime)) {
                                DeliveryResult.SENT -> ended++
                                DeliveryResult.FAILED -> failed++
                                DeliveryResult.SKIPPED -> Unit
                            }
                        }
                    }
                }
            }
        val result = PushRefreshResult(
            inspected = subscriptions.size,
            updated = updated,
            ended = ended,
            failed = failed,
        )
        if (result.failed > 0) {
            logger.warn(
                "event=push_refresh_completed inspected={} updated={} ended={} failed={}",
                result.inspected,
                result.updated,
                result.ended,
                result.failed,
            )
        } else {
            logger.info(
                "event=push_refresh_completed inspected={} updated={} ended={} failed={}",
                result.inspected,
                result.updated,
                result.ended,
                result.failed,
            )
        }
        return result
    }

    private fun deliver(
        subscription: TrackingSubscription,
        arrival: TrackedArrival,
        decision: PushDecision,
        now: Instant,
    ): DeliveryResult {
        val claimId = UUID.randomUUID()
        val claimed = repository.compareAndMutate(
            subscription.trackingSessionId,
            subscription.revision,
        ) { current ->
            val available = current.state == TrackingState.ACTIVE &&
                (
                    current.deliveryClaimUntil == null ||
                        !now.isBefore(current.deliveryClaimUntil)
                    )
            if (!available) {
                null
            } else {
                current.copy(
                    lastMatchedArrival = arrival,
                    deliveryClaimId = claimId,
                    deliveryClaimUntil = now.plus(properties.deliveryClaimLease),
                    updatedAt = now,
                )
            }
        }
            ?: return DeliveryResult.SKIPPED

        val type = if (decision is PushDecision.End) {
            PushMessageType.TRACKING_END
        } else {
            PushMessageType.ARRIVAL_UPDATE
        }
        val reason = (decision as? PushDecision.End)?.reason
        if (!send(claimed, arrival, type, reason)) {
            releaseClaim(claimed, claimId)
            return DeliveryResult.FAILED
        }

        val completed = repository.compareAndMutate(
            claimed.trackingSessionId,
            claimed.revision,
        ) { current ->
            if (current.deliveryClaimId != claimId) {
                null
            } else {
                when (decision) {
                    PushDecision.Update -> current.copy(
                        lastDeliveredEta = arrival.estimatedArrivalAt,
                        lastDeliveredStatus = arrival.status,
                        lastDeliveredFreshness = arrival.freshness,
                        lastDeliveredAt = now,
                        deliveryClaimId = null,
                        deliveryClaimUntil = null,
                        updatedAt = now,
                    )
                    is PushDecision.End -> current.copy(
                        state = if (decision.reason == TrackingEndReason.EXPIRED) {
                            TrackingState.EXPIRED
                        } else {
                            TrackingState.ENDED
                        },
                        lastDeliveredEta = arrival.estimatedArrivalAt,
                        lastDeliveredStatus = arrival.status,
                        lastDeliveredFreshness = arrival.freshness,
                        lastDeliveredAt = now,
                        deliveryClaimId = null,
                        deliveryClaimUntil = null,
                        updatedAt = now,
                    )
                    PushDecision.None -> null
                }
            }
        }
        return if (completed == null) DeliveryResult.SKIPPED else DeliveryResult.SENT
    }

    private fun releaseClaim(
        claimed: TrackingSubscription,
        claimId: UUID,
    ) {
        repository.compareAndMutate(
            claimed.trackingSessionId,
            claimed.revision,
        ) { current ->
            if (current.deliveryClaimId != claimId) {
                null
            } else {
                current.copy(
                    deliveryClaimId = null,
                    deliveryClaimUntil = null,
                    updatedAt = clock.instant(),
                )
            }
        }
    }

    private fun send(
        subscription: TrackingSubscription,
        arrival: TrackedArrival,
        type: PushMessageType,
        reason: TrackingEndReason? = null,
    ): Boolean =
        try {
            gateways.require(subscription.platform).send(
                pushTarget = cipher.decrypt(subscription.pushTarget),
                environment = subscription.pushEnvironment,
                message = PushMessage(
                    type = type,
                    trackingSessionId = subscription.trackingSessionId,
                    arrival = arrival,
                    timestamp = clock.instant(),
                    expiresAt = subscription.expiresAt,
                    reason = reason,
                ),
            )
            true
        } catch (failure: Exception) {
            logger.warn(
                "event=push_delivery_failed trackingSessionId={} platform={} " +
                    "messageType={} stopId={} routeName={} failureType={}",
                subscription.trackingSessionId,
                subscription.platform,
                type,
                subscription.stopId,
                subscription.routeName,
                failure.javaClass.simpleName,
            )
            false
        }

    private fun fallbackArrival(
        subscription: TrackingSubscription,
        now: Instant,
    ): TrackedArrival =
        subscription.lastMatchedArrival ?: TrackedArrival(
            tripId = subscription.tripId,
            routeId = subscription.routeId,
            routeName = subscription.routeName,
            stopId = subscription.stopId,
            stopName = stopCatalog.require(subscription.stopId).name,
            destination = "",
            estimatedArrivalAt = subscription.lastDeliveredEta,
            observedAt = now,
            staleAt = now,
            status = ArrivalStatus.UNKNOWN,
            freshness = Freshness.STALE,
        )

    private enum class DeliveryResult {
        SENT,
        SKIPPED,
        FAILED,
    }

    private companion object {
        val logger = LoggerFactory.getLogger(PushRefreshService::class.java)
    }
}
