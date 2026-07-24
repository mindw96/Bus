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

                if store.state == .loaded {
                    Text("\(store.arrivals.count)개 노선")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }
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
        switch store.state {
        case .idle, .loading:
            Section("전체 도착 예정") {
                LoadingArrivalRows()
            }
        case .loaded:
            Section("전체 도착 예정") {
                ForEach(store.arrivals) { item in
                    ArrivalRow(
                        item: item,
                        now: now,
                        isTracking: store.trackingTripID == item.arrival.tripId
                    ) {
                        Task { await store.toggleTracking(item) }
                    }
                }
            }
        case .empty:
            Section {
                ContentUnavailableView(
                    "도착 예정 버스가 없습니다",
                    systemImage: "bus",
                    description: Text("잠시 후 다시 확인해 주세요.")
                )
            }
        case .failed(let message):
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

private struct LoadingArrivalRows: View {
    var body: some View {
        ForEach(0..<5, id: \.self) { _ in
            VStack(alignment: .leading, spacing: 10) {
                RoundedRectangle(cornerRadius: 4)
                    .frame(width: 72, height: 22)
                RoundedRectangle(cornerRadius: 4)
                    .frame(height: 16)
                RoundedRectangle(cornerRadius: 8)
                    .frame(height: 36)
            }
            .foregroundStyle(.quaternary)
            .padding(.vertical, 8)
            .redacted(reason: .placeholder)
        }
        .accessibilityLabel("도착 정보를 불러오는 중")
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
            Section("전체 도착 예정") {
                LoadingArrivalRows()
            }
        }
    }
#endif
