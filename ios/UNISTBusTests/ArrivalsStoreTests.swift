import Foundation
import XCTest
@testable import UNISTBus

@MainActor
final class ArrivalsStoreTests: XCTestCase {
    func testMainStopCatalogMatchesUNISTStopIdentifiers() {
        XCTAssertEqual(MainBusStop.allCases.map(\.id), ["40234", "40233", "40235"])
        XCTAssertEqual(MainBusStop.campus.name, "울산과학기술원")
        XCTAssertEqual(MainBusStop.origin.name, "울산과학기술원(기점)")
        XCTAssertEqual(MainBusStop.terminus.name, "울산과학기술원(종점)")
    }

    func testLiveActivityUsesDepartureAtOriginAndArrivalAtOtherStops() {
        XCTAssertEqual(MainBusStop.origin.countdownKind, .departure)
        XCTAssertEqual(MainBusStop.origin.countdownKind.countdownTitle, "출발까지")
        XCTAssertEqual(MainBusStop.origin.countdownKind.imminentTitle, "곧 출발")

        XCTAssertEqual(MainBusStop.campus.countdownKind, .arrival)
        XCTAssertEqual(MainBusStop.terminus.countdownKind, .arrival)
        XCTAssertEqual(MainBusStop.campus.countdownKind.countdownTitle, "도착까지")
        XCTAssertEqual(MainBusStop.terminus.countdownKind.imminentTitle, "곧 도착")
    }

    func testLiveActivityTerminalStateTakesPriorityOverStaleData() {
        let now = Date(timeIntervalSince1970: 1_784_678_400)
        let arrived = makeContentState(status: .arrived, eta: now, now: now)
        let cancelled = makeContentState(status: .cancelled, eta: now, now: now)

        XCTAssertEqual(arrived.liveActivityPhase(isStale: true, now: now), .completed)
        XCTAssertEqual(cancelled.liveActivityPhase(isStale: true, now: now), .cancelled)
    }

    func testLiveActivityMarksStaleExpiredETAAsRefreshing() {
        let now = Date(timeIntervalSince1970: 1_784_678_400)
        let expired = makeContentState(
            status: .approaching,
            eta: now.addingTimeInterval(-1),
            now: now
        )
        let future = makeContentState(
            status: .approaching,
            eta: now.addingTimeInterval(5 * 60),
            now: now
        )
        let unknownButStale = makeContentState(
            status: .unknown,
            eta: now.addingTimeInterval(5 * 60),
            now: now
        )

        XCTAssertEqual(expired.liveActivityPhase(isStale: true, now: now), .refreshing)
        XCTAssertEqual(future.liveActivityPhase(isStale: true, now: now), .countdown(isStale: true))
        XCTAssertEqual(
            unknownButStale.liveActivityPhase(isStale: true, now: now),
            .countdown(isStale: true)
        )
        XCTAssertEqual(future.liveActivityPhase(isStale: false, now: now), .countdown(isStale: false))
    }

    func testFixtureUsesInjectedAbsoluteTime() async throws {
        let now = Date(timeIntervalSince1970: 1_784_678_400)
        let response = try await ArrivalClient.fixture(
            clock: .constant(now),
            delay: nil
        ).fetchArrivals(MainBusStop.campus.id)

        XCTAssertEqual(response.stop, MainBusStop.campus.summary)
        XCTAssertEqual(response.arrivals.map(\.routeName), ["513"])
        XCTAssertEqual(response.arrivals.first?.estimatedArrivalAt, now.addingTimeInterval(6 * 60))
        XCTAssertEqual(response.arrivals.first?.staleAt, now.addingTimeInterval(2 * 60))
    }

    func testLoadQueriesAllMainStopsAndShowsFiveLogicalRoutes() async {
        let now = Date(timeIntervalSince1970: 1_784_678_400)
        let requestedStops = RequestedStops()
        let fixture = ArrivalClient.fixture(clock: .constant(now), delay: nil)
        let client = ArrivalClient { stopID in
            await requestedStops.append(stopID)
            return try await fixture.fetchArrivals(stopID)
        }
        let store = ArrivalsStore(
            client: client,
            liveActivity: .disabled,
            clock: .constant(now)
        )

        await store.load()
        let requestedStopIDs = await requestedStops.values()

        XCTAssertEqual(store.state, .loaded)
        XCTAssertEqual(
            Set(requestedStopIDs),
            Set(MainBusStop.allCases.map(\.id))
        )
        XCTAssertEqual(store.arrivals.map(\.arrival.routeName), ["513", "713", "743", "753", "1115"])
        XCTAssertEqual(store.arrivals.count, 5)
    }

    func testLoadKeepsSuccessfulStopsWhenOneStopFails() async {
        let now = Date(timeIntervalSince1970: 1_784_678_400)
        let fixture = ArrivalClient.fixture(clock: .constant(now), delay: nil)
        let client = ArrivalClient { stopID in
            if stopID == MainBusStop.origin.id {
                throw TestArrivalError.unavailable
            }
            return try await fixture.fetchArrivals(stopID)
        }
        let store = ArrivalsStore(
            client: client,
            liveActivity: .disabled,
            clock: .constant(now)
        )

        await store.load()

        XCTAssertEqual(store.state, .loaded)
        XCTAssertEqual(store.unavailableStopIDs, Set([MainBusStop.origin.id]))
        XCTAssertEqual(store.arrivals.map(\.arrival.routeName), ["513", "713", "743", "753", "1115"])
    }

    func testPartialFailureDoesNotRemoveAnUnreconciledLiveActivity() async {
        let now = Date(timeIntervalSince1970: 1_784_678_400)
        let probe = LiveActivityProbe(activeTripID: "fixture-40233-trip-713")
        let fixture = ArrivalClient.fixture(clock: .constant(now), delay: nil)
        let client = ArrivalClient { stopID in
            if stopID == MainBusStop.origin.id {
                throw TestArrivalError.unavailable
            }
            return try await fixture.fetchArrivals(stopID)
        }
        let store = ArrivalsStore(
            client: client,
            liveActivity: probe.client,
            clock: .constant(now)
        )

        await store.load()
        let endedAllImmediately = await probe.endedAllImmediately()

        XCTAssertEqual(store.trackingTripID, "fixture-40233-trip-713")
        XCTAssertFalse(endedAllImmediately)
    }

    func testLoadRefreshesActiveLiveActivityWhenTripStillExists() async {
        let now = Date(timeIntervalSince1970: 1_784_678_400)
        let probe = LiveActivityProbe(activeTripID: "fixture-40234-trip-513")
        let store = ArrivalsStore(
            client: .fixture(clock: .constant(now), delay: nil),
            liveActivity: probe.client,
            clock: .constant(now)
        )

        await store.load()
        let updatedTripID = await probe.updatedTripID()
        let endedAllImmediately = await probe.endedAllImmediately()

        XCTAssertEqual(store.trackingTripID, "fixture-40234-trip-513")
        XCTAssertEqual(updatedTripID, "fixture-40234-trip-513")
        XCTAssertFalse(endedAllImmediately)
    }

    func testLoadRemovesOrphanedLiveActivity() async {
        let now = Date(timeIntervalSince1970: 1_784_678_400)
        let probe = LiveActivityProbe(activeTripID: "missing-trip")
        let store = ArrivalsStore(
            client: .fixture(clock: .constant(now), delay: nil),
            liveActivity: probe.client,
            clock: .constant(now)
        )

        await store.load()
        let updatedTripID = await probe.updatedTripID()
        let endedAllImmediately = await probe.endedAllImmediately()

        XCTAssertNil(store.trackingTripID)
        XCTAssertNil(updatedTripID)
        XCTAssertTrue(endedAllImmediately)
    }

    func testMergeKeepsEarliestArrivalForDuplicateLogicalRoute() {
        let now = Date(timeIntervalSince1970: 1_784_678_400)
        let responses = [
            makeResponse(
                stop: .origin,
                arrivals: [makeArrival(route: "713", minutes: 12, now: now)]
            ),
            makeResponse(
                stop: .terminus,
                arrivals: [makeArrival(route: "713", minutes: 7, now: now)]
            ),
            makeResponse(
                stop: .campus,
                arrivals: [makeArrival(route: "513", minutes: 5, now: now)]
            )
        ]

        let merged = ArrivalsStore.mergeResponses(responses)

        XCTAssertEqual(merged.map(\.arrival.routeName), ["513", "713"])
        XCTAssertEqual(merged.last?.stop, MainBusStop.terminus.summary)
        XCTAssertEqual(merged.last?.arrival.estimatedArrivalAt, now.addingTimeInterval(7 * 60))
    }

    func testFreshResponseWinsOverEarlierFallbackForTheSameRoute() {
        let now = Date(timeIntervalSince1970: 1_784_678_400)
        let fresh = makeResponse(
            stop: .origin,
            arrivals: [makeArrival(route: "713", minutes: 12, now: now)]
        )
        let fallback = makeResponse(
            stop: .terminus,
            arrivals: [makeArrival(route: "713", minutes: 7, now: now)]
        )

        let merged = ArrivalsStore.mergeFreshResponses(
            [fresh],
            fallbackResponses: [fallback]
        )

        XCTAssertEqual(merged.count, 1)
        XCTAssertEqual(merged.single?.stop, MainBusStop.origin.summary)
        XCTAssertEqual(merged.single?.arrival.estimatedArrivalAt, now.addingTimeInterval(12 * 60))
    }

    private func makeResponse(stop: MainBusStop, arrivals: [Arrival]) -> ArrivalResponse {
        ArrivalResponse(stop: stop.summary, arrivals: arrivals)
    }

    private func makeArrival(route: String, minutes: Int, now: Date) -> Arrival {
        Arrival(
            tripId: "trip-\(route)-\(minutes)",
            routeId: "route-\(route)",
            routeName: route,
            destination: "덕하 차고지",
            scheduledArrivalAt: now.addingTimeInterval(TimeInterval((minutes + 2) * 60)),
            estimatedArrivalAt: now.addingTimeInterval(TimeInterval(minutes * 60)),
            observedAt: now,
            staleAt: now.addingTimeInterval(2 * 60),
            status: .scheduled
        )
    }

    private func makeContentState(
        status: ArrivalStatus,
        eta: Date,
        now: Date
    ) -> BusArrivalActivityAttributes.ContentState {
        BusArrivalActivityAttributes.ContentState(
            estimatedArrivalAt: eta,
            observedAt: now,
            staleAt: now.addingTimeInterval(2 * 60),
            status: status
        )
    }
}

private enum TestArrivalError: LocalizedError {
    case unavailable

    var errorDescription: String? {
        "테스트 정류장 연결 실패"
    }
}

private extension Collection {
    var single: Element? {
        count == 1 ? first : nil
    }
}

private actor RequestedStops {
    private var stopIDs: [String] = []

    func append(_ stopID: String) {
        stopIDs.append(stopID)
    }

    func values() -> [String] {
        stopIDs
    }
}

private actor LiveActivityProbe {
    private let activeID: String?
    private var updatedID: String?
    private var removedOrphan = false

    init(activeTripID: String?) {
        activeID = activeTripID
    }

    nonisolated var client: LiveActivityClient {
        LiveActivityClient(
            isEnabled: { true },
            activeTripID: { await self.activeTripID() },
            start: { _ in },
            update: { item in await self.recordUpdate(item) },
            end: { item, immediately in
                await self.recordEnd(item: item, immediately: immediately)
            }
        )
    }

    func activeTripID() -> String? {
        activeID
    }

    func recordUpdate(_ item: StopArrival) {
        updatedID = item.arrival.tripId
    }

    func recordEnd(item: StopArrival?, immediately: Bool) {
        removedOrphan = item == nil && immediately
    }

    func updatedTripID() -> String? {
        updatedID
    }

    func endedAllImmediately() -> Bool {
        removedOrphan
    }
}
