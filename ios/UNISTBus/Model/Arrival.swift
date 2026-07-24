import Foundation

struct StopSummary: Codable, Hashable, Sendable {
    let id: String
    let name: String
}
struct ArrivalResponse: Codable, Hashable, Sendable {
    let stop: StopSummary
    let arrivals: [Arrival]
}

struct Arrival: Codable, Hashable, Identifiable, Sendable {
    var id: String { tripId }

    let tripId: String
    let routeId: String
    let routeName: String
    let destination: String
    let scheduledArrivalAt: Date
    let estimatedArrivalAt: Date
    let observedAt: Date
    let staleAt: Date
    let status: ArrivalStatus

    func updating(
        estimatedArrivalAt: Date? = nil,
        observedAt: Date? = nil,
        staleAt: Date? = nil,
        status: ArrivalStatus? = nil
    ) -> Arrival {
        Arrival(
            tripId: tripId,
            routeId: routeId,
            routeName: routeName,
            destination: destination,
            scheduledArrivalAt: scheduledArrivalAt,
            estimatedArrivalAt: estimatedArrivalAt ?? self.estimatedArrivalAt,
            observedAt: observedAt ?? self.observedAt,
            staleAt: staleAt ?? self.staleAt,
            status: status ?? self.status
        )
    }
}
