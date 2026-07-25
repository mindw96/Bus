@preconcurrency import ActivityKit
import Foundation

@MainActor
final class LiveActivityPushCoordinator {
    typealias BusActivity = Activity<BusArrivalActivityAttributes>
    typealias BusActivityContent = ActivityContent<BusArrivalActivityAttributes.ContentState>

    private struct Observation {
        let tokenTask: Task<Void, Never>
        let stateTask: Task<Void, Never>
        let contentTask: Task<Void, Never>

        func cancel() {
            tokenTask.cancel()
            stateTask.cancel()
            contentTask.cancel()
        }
    }

    private let isEnabled: Bool
    private let pushEnvironment: TrackingPushEnvironment
    private let client: TrackingSubscriptionClient
    private let persistence: TrackingSubscriptionPersistence

    private var observations: [String: Observation] = [:]
    private var terminalActivityIds: Set<String> = []
    private var isRetrying = false
    private var shouldRetryAgain = false

    init(
        isEnabled: Bool,
        pushEnvironment: TrackingPushEnvironment,
        client: TrackingSubscriptionClient,
        persistence: TrackingSubscriptionPersistence
    ) {
        self.isEnabled = isEnabled
        self.pushEnvironment = pushEnvironment
        self.client = client
        self.persistence = persistence
    }

    func resume() async {
        guard isEnabled else { return }

        for activity in BusActivity.activities {
            await observe(activity)
        }
        // Observing first stages the current ActivityKit token before flushing
        // anything persisted by an older app process.
        await retryPending()
    }

    func observe(_ activity: BusActivity) async {
        guard isEnabled, !terminalActivityIds.contains(activity.id) else {
            return
        }

        _ = await persistence.trackingSessionId(for: activity.id)

        if observations[activity.id] == nil {
            let tokenTask = Task { @MainActor [weak self] in
                for await token in activity.pushTokenUpdates {
                    guard !Task.isCancelled else { return }
                    await self?.received(token: token, for: activity)
                }
            }
            let stateTask = Task { @MainActor [weak self] in
                for await state in activity.activityStateUpdates {
                    guard !Task.isCancelled else { return }
                    if state == .ended || state == .dismissed {
                        await self?.activityDidEnd(activityId: activity.id)
                        return
                    }
                    await self?.retryPending()
                }
            }
            let contentTask = Task { @MainActor [weak self] in
                for await content in activity.contentUpdates {
                    guard !Task.isCancelled else { return }
                    await self?.synchronize(activity, content: content)
                }
            }
            observations[activity.id] = Observation(
                tokenTask: tokenTask,
                stateTask: stateTask,
                contentTask: contentTask
            )
        }

        if let token = activity.pushToken {
            await received(token: token, for: activity)
        } else {
            await retryPending()
        }
    }

    func synchronize(
        _ activity: BusActivity,
        content: BusActivityContent
    ) async {
        guard
            isEnabled,
            !terminalActivityIds.contains(activity.id),
            let token = activity.pushToken
        else {
            await retryPending()
            return
        }

        await stageUpsert(
            activity: activity,
            content: content,
            pushTarget: Self.hexString(token)
        )
    }

    func activityDidEnd(activityId: String) async {
        guard isEnabled else { return }

        terminalActivityIds.insert(activityId)
        let observation = observations.removeValue(forKey: activityId)
        _ = await persistence.stageDelete(activityId: activityId)
        await retryPending()
        observation?.cancel()
    }

    func retryPending() async {
        guard isEnabled else { return }
        guard !isRetrying else {
            shouldRetryAgain = true
            return
        }

        isRetrying = true
        repeat {
            shouldRetryAgain = false
            await performPendingPass()
        } while shouldRetryAgain
        isRetrying = false
    }

    private func received(
        token: Data,
        for activity: BusActivity
    ) async {
        guard !terminalActivityIds.contains(activity.id) else {
            return
        }

        await stageUpsert(
            activity: activity,
            content: activity.content,
            pushTarget: Self.hexString(token)
        )
    }

    private func stageUpsert(
        activity: BusActivity,
        content: BusActivityContent,
        pushTarget: String
    ) async {
        let installationId = await persistence.installationId()
        let state = content.state
        let expiresAt = max(
            state.estimatedArrivalAt,
            state.staleAt
        ).addingTimeInterval(30 * 60)
        let request = TrackingSubscriptionRequest(
            installationId: installationId,
            pushTarget: pushTarget,
            pushEnvironment: pushEnvironment,
            tripId: activity.attributes.tripID,
            routeId: activity.attributes.routeID,
            routeName: activity.attributes.routeName,
            stopId: activity.attributes.stopID,
            estimatedArrivalAt: state.estimatedArrivalAt,
            expiresAt: expiresAt
        )

        await persistence.stageUpsert(
            activityId: activity.id,
            request: request
        )
        await retryPending()
    }

    private func performPendingPass() async {
        let operations = await persistence.pendingOperations()

        for operation in operations {
            do {
                switch operation {
                case .upsert(
                    let activityId,
                    let trackingSessionId,
                    let request
                ):
                    _ = try await client.upsert(trackingSessionId, request)
                    await persistence.completeUpsert(
                        activityId: activityId,
                        request: request
                    )

                case .delete(let activityId, let trackingSessionId):
                    try await client.delete(trackingSessionId)
                    await persistence.completeDelete(
                        activityId: activityId,
                        trackingSessionId: trackingSessionId
                    )
                }
            } catch {
                // Registration is best-effort. The pending operation remains in
                // UserDefaults and is retried on launch, content updates, state
                // changes, or the next ActivityKit push-token event.
            }
        }
    }

    private static func hexString(_ data: Data) -> String {
        data.map { String(format: "%02x", $0) }.joined()
    }
}
