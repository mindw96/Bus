import Foundation

enum ArrivalPresentation: Equatable, Sendable {
    case countdown(eta: Date, badge: String?)
    case arriving
    case arrived
    case cancelled
    case stale
    case unknown

    var accessibilityText: String {
        switch self {
        case .countdown(let eta, let badge):
            [badge, eta.formatted(.relative(presentation: .numeric))]
                .compactMap { $0 }
                .joined(separator: ", ")
        case .arriving:
            "곧 도착"
        case .arrived:
            "도착"
        case .cancelled:
            "운행 취소"
        case .stale:
            "데이터 지연"
        case .unknown:
            "도착 정보 확인 중"
        }
    }
}
extension Arrival {
    func presentation(at now: Date) -> ArrivalPresentation {
        switch status {
        case .cancelled:
            return .cancelled
        case .arrived:
            return .arrived
        default:
            break
        }

        if now >= staleAt {
            return .stale
        }

        if status == .arriving || estimatedArrivalAt <= now {
            return .arriving
        }

        if status == .unknown {
            return .unknown
        }

        return .countdown(
            eta: estimatedArrivalAt,
            badge: status == .delayed ? status.displayName : nil
        )
    }
}
