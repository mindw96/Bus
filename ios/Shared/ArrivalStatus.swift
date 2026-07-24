import Foundation

enum ArrivalStatus: String, Codable, CaseIterable, Hashable, Sendable {
    case scheduled = "SCHEDULED"
    case approaching = "APPROACHING"
    case arriving = "ARRIVING"
    case arrived = "ARRIVED"
    case delayed = "DELAYED"
    case cancelled = "CANCELLED"
    case unknown = "UNKNOWN"

    var displayName: String {
        switch self {
        case .scheduled:
            "운행 예정"
        case .approaching:
            "접근 중"
        case .arriving:
            "곧 도착"
        case .arrived:
            "도착"
        case .delayed:
            "지연"
        case .cancelled:
            "운행 취소"
        case .unknown:
            "확인 중"
        }
    }
}
