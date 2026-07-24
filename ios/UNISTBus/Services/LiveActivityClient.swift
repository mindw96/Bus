import ActivityKit
import Foundation

struct LiveActivityClient: Sendable {
    let isEnabled: @Sendable () -> Bool
    let activeTripID: @Sendable () async -> String?
    let start: @Sendable (_ item: StopArrival) async throws -> Void
    let update: @Sendable (_ item: StopArrival) async -> Void
    let end: @Sendable (_ item: StopArrival?, _ immediately: Bool) async -> Void

    static let live = LiveActivityClient(
        isEnabled: {
            ActivityAuthorizationInfo().areActivitiesEnabled
        },
        activeTripID: {
            Activity<BusArrivalActivityAttributes>.activities.first?.attributes.tripID
        },
        start: { item in
            guard ActivityAuthorizationInfo().areActivitiesEnabled else {
                throw LiveActivityError.notAuthorized
            }

            for existing in Activity<BusArrivalActivityAttributes>.activities {
                await existing.end(nil, dismissalPolicy: .immediate)
            }

            _ = try Activity<BusArrivalActivityAttributes>.request(
                attributes: BusArrivalActivityAttributes(item: item),
                content: ActivityContent(
                    state: BusArrivalActivityAttributes.ContentState(arrival: item.arrival),
                    staleDate: item.arrival.staleAt
                ),
                pushType: nil
            )
        },
        update: { item in
            guard let activity = Activity<BusArrivalActivityAttributes>.activities.first(where: {
                $0.attributes.tripID == item.arrival.tripId
            }) else {
                return
            }

            await activity.update(
                ActivityContent(
                    state: BusArrivalActivityAttributes.ContentState(arrival: item.arrival),
                    staleDate: item.arrival.staleAt
                )
            )
        },
        end: { item, immediately in
            let activities = Activity<BusArrivalActivityAttributes>.activities.filter { activity in
                item == nil || activity.attributes.tripID == item?.arrival.tripId
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
            }
        }
    )

    static let disabled = LiveActivityClient(
        isEnabled: { false },
        activeTripID: { nil },
        start: { _ in throw LiveActivityError.notAuthorized },
        update: { _ in },
        end: { _, _ in }
    )
}

enum LiveActivityError: LocalizedError, Sendable {
    case notAuthorized

    var errorDescription: String? {
        "설정에서 Live Activity 사용을 허용해 주세요."
    }
}

private extension BusArrivalActivityAttributes {
    init(item: StopArrival) {
        self.init(
            tripID: item.arrival.tripId,
            routeName: item.arrival.routeName,
            destination: item.arrival.destination,
            stopName: item.stop.name,
            countdownKind: MainBusStop(rawValue: item.stop.id)?.countdownKind ?? .arrival
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
