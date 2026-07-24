import Foundation
import XCTest
@testable import UNISTBus

final class ArrivalPresentationTests: XCTestCase {
    private let now = Date(timeIntervalSince1970: 1_784_678_400)

    func testCancelledAndArrivedTakePriorityOverStale() {
        XCTAssertEqual(makeArrival(status: .cancelled, staleAt: now).presentation(at: now), .cancelled)
        XCTAssertEqual(makeArrival(status: .arrived, staleAt: now).presentation(at: now), .arrived)
    }

    func testExactlyAtStaleDateIsStale() {
        XCTAssertEqual(makeArrival(staleAt: now).presentation(at: now), .stale)
    }

    func testExactlyAtETAIsArriving() {
        let arrival = makeArrival(
            estimatedArrivalAt: now,
            staleAt: now.addingTimeInterval(60)
        )
        XCTAssertEqual(arrival.presentation(at: now), .arriving)
    }

    func testDelayedArrivalKeepsAbsoluteETA() {
        let eta = now.addingTimeInterval(10 * 60)
        let arrival = makeArrival(
            status: .delayed,
            estimatedArrivalAt: eta,
            staleAt: now.addingTimeInterval(60)
        )
        XCTAssertEqual(arrival.presentation(at: now), .countdown(eta: eta, badge: "지연"))
    }

    private func makeArrival(
        status: ArrivalStatus = .approaching,
        estimatedArrivalAt: Date? = nil,
        staleAt: Date? = nil
    ) -> Arrival {
        Arrival(
            tripId: "trip",
            routeId: "route",
            routeName: "513",
            destination: "덕하 차고지",
            scheduledArrivalAt: now.addingTimeInterval(12 * 60),
            estimatedArrivalAt: estimatedArrivalAt ?? now.addingTimeInterval(10 * 60),
            observedAt: now,
            staleAt: staleAt ?? now.addingTimeInterval(2 * 60),
            status: status
        )
    }
}
