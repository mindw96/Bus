import Foundation
import Observation

@MainActor
@Observable
final class ArrivalsStore {
    enum LoadState: Equatable {
        case idle
        case loading
        case loaded
        case empty
        case failed(String)
    }

    struct UserNotice: Identifiable, Equatable {
        let id = UUID()
        let title: String
        let message: String
    }

    private let client: ArrivalClient
    private let liveActivity: LiveActivityClient
    private let clock: DateProvider
    private let mainStops: [MainBusStop]
    private let cache: ArrivalCacheClient

    private(set) var state: LoadState
    private(set) var arrivals: [StopArrival]
    private(set) var trackingActivity: LiveActivityTrackingDescriptor?
    private(set) var unavailableStopIDs: Set<String>
    private(set) var notice: UserNotice?

    init(
        client: ArrivalClient,
        liveActivity: LiveActivityClient,
        clock: DateProvider,
        mainStops: [MainBusStop] = MainBusStop.allCases,
        initialArrivals: [StopArrival]? = nil,
        cache: ArrivalCacheClient = .disabled
    ) {
        self.client = client
        self.liveActivity = liveActivity
        self.clock = clock
        self.mainStops = mainStops
        self.cache = cache
        self.arrivals = initialArrivals ?? []
        self.unavailableStopIDs = []
        self.state = initialArrivals == nil
            ? .idle
            : (initialArrivals?.isEmpty == true ? .empty : .loaded)
    }

    var trackingTripID: String? {
        trackingActivity?.tripID
    }

    var trackedArrival: StopArrival? {
        guard let trackingActivity else { return nil }
        return arrivals.first(where: trackingActivity.matches)
    }

    var latestObservationAt: Date? {
        arrivals.map(\.arrival.observedAt).max()
    }

    var hasPartialFailure: Bool {
        !unavailableStopIDs.isEmpty
    }

    var mainRouteArrivals: [MainRouteArrival] {
        var availableByRoute: [MainBusRoute: StopArrival] = [:]

        for item in arrivals {
            guard let route = MainBusRoute(rawValue: item.arrival.routeName) else {
                continue
            }

            if let existing = availableByRoute[route],
               existing.arrival.estimatedArrivalAt <= item.arrival.estimatedArrivalAt {
                continue
            }
            availableByRoute[route] = item
        }

        return MainBusRoute.allCases.map { route in
            if let item = availableByRoute[route] {
                return .available(route: route, item: item)
            }
            return .unavailable(route: route, reason: missingRouteReason)
        }
    }

    func isTracking(_ item: StopArrival) -> Bool {
        trackingActivity?.matches(item) == true
    }

    func resumeLiveActivityTracking() async {
        await liveActivity.resume()
    }

    func load(
        force: Bool = false,
        showsFailureNotice: Bool = true
    ) async {
        if !force, state != .idle {
            return
        }

        let cachedResponses = Self.usableCachedResponses(
            await cache.load(),
            at: clock.now()
        )
        if arrivals.isEmpty, !cachedResponses.isEmpty {
            arrivals = Self.mergeResponses(cachedResponses)
            state = arrivals.isEmpty ? .empty : .loaded
        }

        let hadContent = !arrivals.isEmpty
        if !hadContent {
            state = .loading
        }

        do {
            let result = await fetchAllMainStops()
            guard !Task.isCancelled else { return }

            unavailableStopIDs = Set(result.failures.map(\.stop.id))
            guard !result.responses.isEmpty else {
                throw MainStopFetchError(failures: result.failures)
            }

            for response in result.responses {
                await cache.save(response)
            }

            let failedStopIDs = Set(result.failures.map(\.stop.id))
            let fallbackResponses = cachedResponses.filter {
                failedStopIDs.contains($0.stop.id)
            } + fallbackResponses(for: result.failures.map(\.stop))
            arrivals = Self.mergeFreshResponses(
                result.responses,
                fallbackResponses: fallbackResponses
            )
            state = arrivals.isEmpty ? .empty : .loaded
            trackingActivity = await liveActivity.activeTracking()

            if let trackedArrival {
                trackingActivity = trackingActivity?.updatingTripID(
                    trackedArrival.arrival.tripId
                )
                await liveActivity.update(trackedArrival)
            } else if trackingActivity != nil, result.failures.isEmpty {
                // An app/extension reinstall can leave an ActivityKit record whose
                // rendered archive no longer exists. Remove it when its route and
                // stop can no longer be reconciled with the latest arrivals instead
                // of leaving an empty card on the Lock Screen.
                await liveActivity.end(nil, true)
                trackingActivity = nil
            }
        } catch is CancellationError {
            return
        } catch {
            if !arrivals.isEmpty {
                state = .loaded
                if showsFailureNotice {
                    notice = UserNotice(
                        title: "새 정보를 불러오지 못했습니다",
                        message: "기존 도착 정보를 유지합니다. \(error.localizedDescription)"
                    )
                }
            } else {
                state = .failed(error.localizedDescription)
            }
        }
    }

    func toggleTracking(_ routeArrival: MainRouteArrival) async {
        guard let item = routeArrival.item else {
            return
        }
        await toggleTracking(item)
    }

    private func toggleTracking(_ item: StopArrival) async {
        if isTracking(item) {
            await liveActivity.end(item, true)
            trackingActivity = nil
            notice = UserNotice(title: "추적을 종료했습니다", message: "잠금화면 표시를 정리했습니다.")
            return
        }

        guard liveActivity.isEnabled() else {
            notice = UserNotice(
                title: "Live Activity를 사용할 수 없습니다",
                message: "설정 앱에서 UNIST Bus의 Live Activity 사용을 허용해 주세요."
            )
            return
        }

        do {
            try await liveActivity.start(item)
            trackingActivity = LiveActivityTrackingDescriptor(item: item)
            notice = UserNotice(
                title: "잠금화면 추적을 시작했습니다",
                message: "\(item.arrival.routeName)번 버스의 남은 시간이 자동으로 줄어듭니다."
            )
        } catch {
            notice = UserNotice(title: "추적을 시작하지 못했습니다", message: error.localizedDescription)
        }
    }

    func delayTrackedArrival() async {
        await updateTrackedArrival { item, now in
            item.updatingArrival(
                item.arrival.updating(
                    estimatedArrivalAt: item.arrival.estimatedArrivalAt.addingTimeInterval(2 * 60),
                    observedAt: now,
                    staleAt: now.addingTimeInterval(2 * 60),
                    status: .delayed
                )
            )
        }
    }

    func markTrackedArrivalStale() async {
        await updateTrackedArrival { item, now in
            item.updatingArrival(
                item.arrival.updating(
                    observedAt: now.addingTimeInterval(-3 * 60),
                    staleAt: now.addingTimeInterval(-1),
                    status: .unknown
                )
            )
        }
    }

    func markTrackedArrivalArrived() async {
        guard let index = trackedArrivalIndex else { return }
        let item = arrivals[index]
        let now = clock.now()
        let updated = item.updatingArrival(
            item.arrival.updating(
                estimatedArrivalAt: now,
                observedAt: now,
                staleAt: now.addingTimeInterval(2 * 60),
                status: .arrived
            )
        )
        arrivals[index] = updated
        await liveActivity.end(updated, false)
        trackingActivity = nil
    }

    func dismissNotice() {
        notice = nil
    }

    static func mergeResponses(_ responses: [ArrivalResponse]) -> [StopArrival] {
        var earliestByRoute: [String: StopArrival] = [:]

        for response in responses {
            for arrival in response.arrivals {
                let item = StopArrival(stop: response.stop, arrival: arrival)
                if let existing = earliestByRoute[item.logicalRouteKey] {
                    let shouldReplace = item.arrival.estimatedArrivalAt < existing.arrival.estimatedArrivalAt
                        || (
                            item.arrival.estimatedArrivalAt == existing.arrival.estimatedArrivalAt
                                && item.stop.id < existing.stop.id
                        )
                    if shouldReplace {
                        earliestByRoute[item.logicalRouteKey] = item
                    }
                } else {
                    earliestByRoute[item.logicalRouteKey] = item
                }
            }
        }

        return earliestByRoute.values.sorted { lhs, rhs in
            if lhs.arrival.estimatedArrivalAt == rhs.arrival.estimatedArrivalAt {
                return lhs.arrival.routeName.localizedStandardCompare(rhs.arrival.routeName) == .orderedAscending
            }
            return lhs.arrival.estimatedArrivalAt < rhs.arrival.estimatedArrivalAt
        }
    }

    static func mergeFreshResponses(
        _ freshResponses: [ArrivalResponse],
        fallbackResponses: [ArrivalResponse]
    ) -> [StopArrival] {
        let freshRouteIDs = Set(
            freshResponses.flatMap { response in
                response.arrivals.map(\.routeId)
            }
        )
        let filteredFallbacks = fallbackResponses.map { response in
            ArrivalResponse(
                stop: response.stop,
                arrivals: response.arrivals.filter { !freshRouteIDs.contains($0.routeId) }
            )
        }
        return mergeResponses(freshResponses + filteredFallbacks)
    }

    static func usableCachedResponses(
        _ responses: [ArrivalResponse],
        at now: Date,
        maxAge: TimeInterval = 30 * 60
    ) -> [ArrivalResponse] {
        responses.filter { response in
            guard let observedAt = response.arrivals.map(\.observedAt).max() else {
                return false
            }
            return now.timeIntervalSince(observedAt) <= maxAge
        }
    }

    private func fetchAllMainStops() async -> MainStopFetchResult {
        let client = client
        let stops = mainStops

        return await withTaskGroup(of: StopFetchOutcome.self) { group in
            for stop in stops {
                group.addTask {
                    do {
                        return .success(
                            stop: stop,
                            response: try await client.fetchArrivals(stop.id)
                        )
                    } catch {
                        return .failure(
                            stop: stop,
                            message: error.localizedDescription
                        )
                    }
                }
            }

            var responses: [ArrivalResponse] = []
            var failures: [StopFetchFailure] = []
            responses.reserveCapacity(stops.count)
            for await outcome in group {
                switch outcome {
                case .success(_, let response):
                    responses.append(response)
                case .failure(let stop, let message):
                    failures.append(StopFetchFailure(stop: stop, message: message))
                }
            }
            return MainStopFetchResult(responses: responses, failures: failures)
        }
    }

    private func fallbackResponses(for failedStops: [MainBusStop]) -> [ArrivalResponse] {
        failedStops.compactMap { stop in
            let cachedArrivals = arrivals
                .filter { $0.stop.id == stop.id }
                .map(\.arrival)
            guard !cachedArrivals.isEmpty else { return nil }
            return ArrivalResponse(stop: stop.summary, arrivals: cachedArrivals)
        }
    }

    private var trackedArrivalIndex: Int? {
        guard let trackingActivity else { return nil }
        return arrivals.firstIndex(where: trackingActivity.matches)
    }

    private var missingRouteReason: MainRouteUnavailableReason {
        if hasPartialFailure {
            return .checking
        }

        switch state {
        case .loaded, .empty:
            return .noCurrentArrival
        case .idle, .loading, .failed:
            return .checking
        }
    }

    private func updateTrackedArrival(
        _ transform: (_ item: StopArrival, _ now: Date) -> StopArrival
    ) async {
        guard let index = trackedArrivalIndex else { return }
        let updated = transform(arrivals[index], clock.now())
        arrivals[index] = updated
        trackingActivity = trackingActivity?.updatingTripID(
            updated.arrival.tripId
        )
        await liveActivity.update(updated)
    }
}

private struct MainStopFetchResult: Sendable {
    let responses: [ArrivalResponse]
    let failures: [StopFetchFailure]
}

private struct StopFetchFailure: Sendable {
    let stop: MainBusStop
    let message: String
}

private enum StopFetchOutcome: Sendable {
    case success(stop: MainBusStop, response: ArrivalResponse)
    case failure(stop: MainBusStop, message: String)
}

private struct MainStopFetchError: LocalizedError {
    let failures: [StopFetchFailure]

    var errorDescription: String? {
        failures.first?.message ?? "도착 정보를 불러오지 못했습니다."
    }
}

#if DEBUG
extension ArrivalsStore {
    static var preview: ArrivalsStore {
        let now = Date(timeIntervalSince1970: 1_784_678_400)
        let items = [
            previewItem(route: "513", destination: "덕하공영차고지", stop: .campus, minutes: 6, now: now),
            previewItem(route: "713", destination: "명촌차고지", stop: .origin, minutes: 9, now: now),
            previewItem(route: "743", destination: "덕하공영차고지", stop: .origin, minutes: 13, now: now),
            previewItem(route: "753", destination: "태화강역", stop: .origin, minutes: 17, now: now),
            previewItem(route: "1115", destination: "꽃바위", stop: .origin, minutes: 22, now: now)
        ]

        return ArrivalsStore(
            client: .fixture(clock: .constant(now), delay: nil),
            liveActivity: .disabled,
            clock: .constant(now),
            initialArrivals: items
        )
    }

    private static func previewItem(
        route: String,
        destination: String,
        stop: MainBusStop,
        minutes: Int,
        now: Date
    ) -> StopArrival {
        StopArrival(
            stop: stop.summary,
            arrival: Arrival(
                tripId: "preview-\(stop.id)-\(route)",
                routeId: "fixture-route-\(route)",
                routeName: route,
                destination: destination,
                scheduledArrivalAt: now.addingTimeInterval(TimeInterval((minutes + 2) * 60)),
                estimatedArrivalAt: now.addingTimeInterval(TimeInterval(minutes * 60)),
                observedAt: now,
                staleAt: now.addingTimeInterval(20 * 60),
                status: route == "753" ? .delayed : .approaching
            )
        )
    }
}
#endif
