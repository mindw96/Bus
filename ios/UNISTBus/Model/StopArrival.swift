import Foundation

struct StopArrival: Hashable, Identifiable, Sendable {
    let stop: StopSummary
    let arrival: Arrival

    var id: String {
        "\(stop.id):\(arrival.tripId)"
    }

    var logicalRouteKey: String {
        arrival.routeId
    }

    func updatingArrival(_ arrival: Arrival) -> StopArrival {
        StopArrival(stop: stop, arrival: arrival)
    }
}

enum MainBusRoute: String, CaseIterable, Hashable, Identifiable, Sendable {
    case route513 = "513"
    case route713 = "713"
    case route743 = "743"
    case route753 = "753"
    case route1115 = "1115"

    var id: String {
        rawValue
    }
}

enum MainRouteUnavailableReason: Equatable, Sendable {
    case checking
    case noCurrentArrival

    var title: String {
        switch self {
        case .checking:
            "운행 정보 확인 중"
        case .noCurrentArrival:
            "현재 도착 정보 없음"
        }
    }

    var detail: String {
        switch self {
        case .checking:
            "도착 정보가 확인되면 잠금화면 추적을 사용할 수 있습니다."
        case .noCurrentArrival:
            "운행 시간대가 아니거나 실시간 도착 정보가 아직 없습니다."
        }
    }

    var systemImage: String {
        switch self {
        case .checking:
            "arrow.clockwise"
        case .noCurrentArrival:
            "clock"
        }
    }
}

enum MainRouteArrival: Equatable, Identifiable, Sendable {
    case available(route: MainBusRoute, item: StopArrival)
    case unavailable(route: MainBusRoute, reason: MainRouteUnavailableReason)

    var id: String {
        route.id
    }

    var route: MainBusRoute {
        switch self {
        case .available(let route, _), .unavailable(let route, _):
            route
        }
    }

    var item: StopArrival? {
        guard case .available(_, let item) = self else { return nil }
        return item
    }
}
