import Foundation

enum MainBusStop: String, CaseIterable, Hashable, Identifiable, Sendable {
    case campus = "40234"
    case origin = "40233"
    case terminus = "40235"

    var id: String { rawValue }

    var name: String {
        switch self {
        case .campus:
            "울산과학기술원"
        case .origin:
            "울산과학기술원(기점)"
        case .terminus:
            "울산과학기술원(종점)"
        }
    }

    var summary: StopSummary {
        StopSummary(id: id, name: name)
    }

    var countdownKind: BusCountdownKind {
        self == .origin ? .departure : .arrival
    }
}
