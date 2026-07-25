import SwiftUI

struct ArrivalsScreen: View {
    @Environment(\.scenePhase) private var scenePhase

    let store: ArrivalsStore

    var body: some View {
        TimelineView(.periodic(from: .now, by: 15)) { context in
            List {
                mainStopsSection
                arrivalsContent(at: context.date)

#if DEBUG
                if store.trackedArrival != nil {
                    Section("Live Activity 테스트") {
                        Button("ETA 2분 지연") {
                            Task { await store.delayTrackedArrival() }
                        }
                        Button("데이터 지연 상태로 전환") {
                            Task { await store.markTrackedArrivalStale() }
                        }
                        Button("도착 처리") {
                            Task { await store.markTrackedArrivalArrived() }
                        }
                    }
                }
#endif
            }
            .listStyle(.insetGrouped)
            .refreshable {
                await store.load(force: true)
            }
        }
        .navigationTitle("UNIST Bus")
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button {
                    Task { await store.load(force: true) }
                } label: {
                    Label("새로고침", systemImage: "arrow.clockwise")
                }
            }
        }
        .task(id: scenePhase) {
            guard scenePhase == .active else { return }

            await store.load()
            while !Task.isCancelled {
                do {
                    try await Task.sleep(for: .seconds(30))
                } catch {
                    return
                }
                guard !Task.isCancelled else { return }
                await store.load(force: true, showsFailureNotice: false)
            }
        }
        .alert(
            store.notice?.title ?? "",
            isPresented: Binding(
                get: { store.notice != nil },
                set: { isPresented in
                    if !isPresented { store.dismissNotice() }
                }
            ),
            actions: {
                Button("확인") { store.dismissNotice() }
            },
            message: {
                if let notice = store.notice {
                    Text(notice.message)
                }
            }
        )
    }

    private var mainStopsSection: some View {
        Section("주요 정류장 3곳") {
            HStack(alignment: .firstTextBaseline) {
                Label("통합 도착 정보", systemImage: "bus.fill")
                    .font(.subheadline.weight(.semibold))

                Spacer()

                Text("\(store.mainRouteArrivals.count)개 노선")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }

            Text(MainBusStop.allCases.map(\.id).joined(separator: " · "))
                .font(.caption.monospacedDigit())
                .foregroundStyle(.secondary)
                .accessibilityLabel("정류장 번호 \(MainBusStop.allCases.map(\.id).joined(separator: ", "))")

            if store.hasPartialFailure {
                Label(
                    "일부 정류장 정보 지연 · \(store.unavailableStopIDs.sorted().joined(separator: " · "))",
                    systemImage: "exclamationmark.triangle.fill"
                )
                .font(.caption.weight(.semibold))
                .foregroundStyle(.orange)
                .accessibilityLabel(
                    "일부 정류장 정보가 지연되고 있습니다. 정류장 번호 \(store.unavailableStopIDs.sorted().joined(separator: ", "))"
                )
            }

            if let latestObservationAt = store.latestObservationAt {
                Label {
                    Text("마지막 확인 \(latestObservationAt, style: .relative)")
                } icon: {
                    Image(systemName: "clock")
                }
                .font(.caption)
                .foregroundStyle(.secondary)
            }
        }
    }

    @ViewBuilder
    private func arrivalsContent(at now: Date) -> some View {
        Section("주요 노선 도착 정보") {
            ForEach(store.mainRouteArrivals) { routeArrival in
                switch routeArrival {
                case .available(_, let item):
                    ArrivalRow(
                        item: item,
                        now: now,
                        isTracking: store.isTracking(item)
                    ) {
                        Task { await store.toggleTracking(routeArrival) }
                    }
                case .unavailable(let route, let reason):
                    UnavailableArrivalRow(route: route, reason: reason)
                }
            }
        }

        if case .failed(let message) = store.state {
            Section {
                ContentUnavailableView {
                    Label("도착 정보를 불러오지 못했습니다", systemImage: "wifi.exclamationmark")
                } description: {
                    Text(message)
                } actions: {
                    Button("다시 시도") {
                        Task { await store.load(force: true) }
                    }
                    .buttonStyle(.borderedProminent)
                }
            }
        }
    }
}

#if DEBUG
    #Preview("도착 목록") {
        NavigationStack {
            ArrivalsScreen(store: .preview)
        }
    }

    #Preview("불러오는 중") {
        List {
            Section("주요 노선 도착 정보") {
                ForEach(MainBusRoute.allCases) { route in
                    UnavailableArrivalRow(route: route, reason: .checking)
                }
            }
        }
    }
#endif
