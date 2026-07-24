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
