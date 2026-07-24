import Foundation
import XCTest
@testable import UNISTBus

@MainActor
final class ArrivalCacheTests: XCTestCase {
    func testFileCacheRoundTrip() async throws {
        let fileURL = FileManager.default.temporaryDirectory
            .appending(path: "unist-bus-cache-\(UUID().uuidString).json")
        defer { try? FileManager.default.removeItem(at: fileURL) }

        let response = makeResponse(observedAt: Date(timeIntervalSince1970: 1_784_678_400))
        await ArrivalCacheClient.file(fileURL).save(response)

        let reloaded = await ArrivalCacheClient.file(fileURL).load()

        XCTAssertEqual(reloaded, [response])
    }

    func testExpiredCacheIsExcluded() {
        let now = Date(timeIntervalSince1970: 1_784_678_400)
        let fresh = makeResponse(observedAt: now.addingTimeInterval(-29 * 60))
        let expired = makeResponse(
            stop: .origin,
            observedAt: now.addingTimeInterval(-31 * 60)
        )

        let usable = ArrivalsStore.usableCachedResponses([fresh, expired], at: now)

        XCTAssertEqual(usable, [fresh])
    }

    private func makeResponse(
        stop: MainBusStop = .campus,
        observedAt: Date
    ) -> ArrivalResponse {
        ArrivalResponse(
            stop: stop.summary,
            arrivals: [
                Arrival(
                    tripId: "cache-\(stop.id)",
                    routeId: "route-\(stop.id)",
                    routeName: stop == .campus ? "513" : "713",
                    destination: "덕하 차고지",
                    scheduledArrivalAt: observedAt.addingTimeInterval(8 * 60),
                    estimatedArrivalAt: observedAt.addingTimeInterval(6 * 60),
                    observedAt: observedAt,
                    staleAt: observedAt.addingTimeInterval(2 * 60),
                    status: .approaching
                )
            ]
        )
    }
}
