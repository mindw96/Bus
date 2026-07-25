import ActivityKit
import Foundation

enum BusCountdownKind: String, Codable, Hashable, Sendable {
    case arrival
    case departure

    var eventName: String {
        switch self {
        case .arrival:
            "도착"
        case .departure:
            "출발"
        }
    }

    var countdownTitle: String {
        "\(eventName)까지"
    }

    var imminentTitle: String {
        "곧 \(eventName)"
    }

    var completedTitle: String {
        switch self {
        case .arrival:
            "도착"
        case .departure:
            "출발 완료"
        }
    }

    var scheduledTitle: String {
        "\(eventName) 예정"
    }
}

enum BusLiveActivityPhase: Equatable, Sendable {
    case countdown(isStale: Bool)
    case imminent
    case completed
    case cancelled
    case refreshing
    case unknown
}

struct BusArrivalActivityAttributes: ActivityAttributes, Sendable {
    struct ContentState: Codable, Hashable, Sendable {
        let estimatedArrivalEpochSeconds: Int64
        let observedAtEpochSeconds: Int64
        let staleAtEpochSeconds: Int64
        let status: ArrivalStatus

        init(
            estimatedArrivalAt: Date,
            observedAt: Date,
            staleAt: Date,
            status: ArrivalStatus
        ) {
            estimatedArrivalEpochSeconds = Int64(estimatedArrivalAt.timeIntervalSince1970)
            observedAtEpochSeconds = Int64(observedAt.timeIntervalSince1970)
            staleAtEpochSeconds = Int64(staleAt.timeIntervalSince1970)
            self.status = status
        }

        var estimatedArrivalAt: Date {
            Date(timeIntervalSince1970: TimeInterval(estimatedArrivalEpochSeconds))
        }

        var observedAt: Date {
            Date(timeIntervalSince1970: TimeInterval(observedAtEpochSeconds))
        }

        var staleAt: Date {
            Date(timeIntervalSince1970: TimeInterval(staleAtEpochSeconds))
        }
    }

    let tripID: String
    let routeID: String
    let routeName: String
    let destination: String
    let stopID: String
    let stopName: String
    let countdownKind: BusCountdownKind
}

extension BusArrivalActivityAttributes.ContentState {
    func liveActivityPhase(isStale: Bool, now: Date) -> BusLiveActivityPhase {
        switch status {
        case .arrived:
            return .completed
        case .cancelled:
            return .cancelled
        default:
            break
        }

        if isStale {
            return estimatedArrivalAt <= now
                ? .refreshing
                : .countdown(isStale: true)
        }
        if status == .arriving || estimatedArrivalAt <= now {
            return .imminent
        }
        if status == .unknown {
            return .unknown
        }
        return .countdown(isStale: false)
    }
}
