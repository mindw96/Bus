@preconcurrency import ActivityKit
import Foundation

struct LiveActivityTrackingDescriptor: Equatable, Hashable, Sendable {
    let tripID: String
    let routeID: String
    let stopID: String

    init(
        tripID: String,
        routeID: String,
        stopID: String
    ) {
        self.tripID = tripID
        self.routeID = routeID
        self.stopID = stopID
    }

    init(item: StopArrival) {
        self.init(
            tripID: item.arrival.tripId,
            routeID: item.arrival.routeId,
            stopID: item.stop.id
        )
    }

    func matches(_ item: StopArrival) -> Bool {
        routeID == item.arrival.routeId && stopID == item.stop.id
    }

    func updatingTripID(_ tripID: String) -> LiveActivityTrackingDescriptor {
        LiveActivityTrackingDescriptor(
            tripID: tripID,
            routeID: routeID,
            stopID: stopID
        )
    }
}

struct LiveActivityClient: Sendable {
    let isEnabled: @MainActor @Sendable () -> Bool
    let activeTracking: @MainActor @Sendable () async -> LiveActivityTrackingDescriptor?
    let start: @MainActor @Sendable (_ item: StopArrival) async throws -> Void
    let update: @MainActor @Sendable (_ item: StopArrival) async -> Void
    let end: @MainActor @Sendable (_ item: StopArrival?, _ immediately: Bool) async -> Void
    let resume: @MainActor @Sendable () async -> Void

    @MainActor
    static func live(
        remotePushEnabled: Bool = false,
        pushEnvironment: TrackingPushEnvironment = .development,
        subscriptionClient: TrackingSubscriptionClient = .disabled,
        persistence: TrackingSubscriptionPersistence = .live
    ) -> LiveActivityClient {
        let pushCoordinator = LiveActivityPushCoordinator(
            isEnabled: remotePushEnabled,
            pushEnvironment: pushEnvironment,
            client: subscriptionClient,
            persistence: persistence
        )

        return LiveActivityClient(
            isEnabled: {
                ActivityAuthorizationInfo().areActivitiesEnabled
            },
            activeTracking: {
                Activity<BusArrivalActivityAttributes>.activities.first.map {
                    LiveActivityTrackingDescriptor(attributes: $0.attributes)
                }
            },
            start: { item in
                guard ActivityAuthorizationInfo().areActivitiesEnabled else {
                    throw LiveActivityError.notAuthorized
                }

                for existing in Activity<BusArrivalActivityAttributes>.activities {
                    await existing.end(nil, dismissalPolicy: .immediate)
                    await pushCoordinator.activityDidEnd(activityId: existing.id)
                }

                let activity = try Activity<BusArrivalActivityAttributes>.request(
                    attributes: BusArrivalActivityAttributes(item: item),
                    content: ActivityContent(
                        state: BusArrivalActivityAttributes.ContentState(arrival: item.arrival),
                        staleDate: item.arrival.staleAt
                    ),
                    pushType: remotePushEnabled ? .token : nil
                )
                await pushCoordinator.observe(activity)
            },
            update: { item in
                guard let activity = Activity<BusArrivalActivityAttributes>.activities.first(where: {
                    $0.attributes.trackingDescriptor.matches(item)
                }) else {
                    await pushCoordinator.retryPending()
                    return
                }

                let content = ActivityContent(
                    state: BusArrivalActivityAttributes.ContentState(arrival: item.arrival),
                    staleDate: item.arrival.staleAt
                )
                await activity.update(content)
                await pushCoordinator.synchronize(activity, content: content)
            },
            end: { item, immediately in
                let activities = Activity<BusArrivalActivityAttributes>.activities.filter { activity in
                    guard let item else { return true }
                    return activity.attributes.trackingDescriptor.matches(item)
                }

                for activity in activities {
                    let finalContent = item.map {
                        ActivityContent(
                            state: BusArrivalActivityAttributes.ContentState(arrival: $0.arrival),
                            staleDate: $0.arrival.status.isTerminal ? nil : $0.arrival.staleAt
                        )
                    }
                    let policy: ActivityUIDismissalPolicy = immediately
                        ? .immediate
                        : .after(Date().addingTimeInterval(15 * 60))
                    await activity.end(finalContent, dismissalPolicy: policy)
                    await pushCoordinator.activityDidEnd(activityId: activity.id)
                }
            },
            resume: {
                await pushCoordinator.resume()
            }
        )
    }

    static let disabled = LiveActivityClient(
        isEnabled: { false },
        activeTracking: { nil },
        start: { _ in throw LiveActivityError.notAuthorized },
        update: { _ in },
        end: { _, _ in },
        resume: {}
    )
}

enum LiveActivityError: LocalizedError, Sendable {
    case notAuthorized

    var errorDescription: String? {
        "설정에서 Live Activity 사용을 허용해 주세요."
    }
}

private extension BusArrivalActivityAttributes {
    var trackingDescriptor: LiveActivityTrackingDescriptor {
        LiveActivityTrackingDescriptor(attributes: self)
    }

    init(item: StopArrival) {
        self.init(
            tripID: item.arrival.tripId,
            routeID: item.arrival.routeId,
            routeName: item.arrival.routeName,
            destination: item.arrival.destination,
            stopID: item.stop.id,
            stopName: item.stop.name,
            countdownKind: MainBusStop(rawValue: item.stop.id)?.countdownKind ?? .arrival
        )
    }
}

private extension LiveActivityTrackingDescriptor {
    init(attributes: BusArrivalActivityAttributes) {
        self.init(
            tripID: attributes.tripID,
            routeID: attributes.routeID,
            stopID: attributes.stopID
        )
    }
}

private extension ArrivalStatus {
    var isTerminal: Bool {
        self == .arrived || self == .cancelled
    }
}

private extension BusArrivalActivityAttributes.ContentState {
    init(arrival: Arrival) {
        self.init(
            estimatedArrivalAt: arrival.estimatedArrivalAt,
            observedAt: arrival.observedAt,
            staleAt: arrival.staleAt,
            status: arrival.status
        )
    }
}
