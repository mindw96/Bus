import SwiftUI

struct ArrivalRow: View {
    let item: StopArrival
    let now: Date
    let isTracking: Bool
    let onToggleTracking: () -> Void

    private var arrival: Arrival {
        item.arrival
    }

    private var presentation: ArrivalPresentation {
        arrival.presentation(at: now)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            VStack(alignment: .leading, spacing: 12) {
                HStack(alignment: .firstTextBaseline) {
                    Text(arrival.routeName)
                        .font(.title2.bold())
                        .foregroundStyle(.primary)

                    Text("번")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)

                    Spacer()

                    arrivalStatus
                }

                Label(arrival.destination, systemImage: "arrow.right")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)

                Label("\(item.stop.name) · \(item.stop.id)", systemImage: "mappin.circle")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            .accessibilityElement(children: .combine)
            .accessibilityLabel(
                "\(arrival.routeName)번, \(arrival.destination) 방면, \(item.stop.name) 정류장 \(item.stop.id), \(presentation.accessibilityText)"
            )

            if isTracking {
                Button(role: .destructive, action: onToggleTracking) {
                    Label("추적 중단", systemImage: "xmark.circle")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.bordered)
            } else {
                Button(action: onToggleTracking) {
                    Label("잠금화면에서 보기", systemImage: "iphone")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
            }
        }
        .padding(.vertical, 6)
    }

    @ViewBuilder
    private var arrivalStatus: some View {
        switch presentation {
        case .countdown(let eta, let badge):
            VStack(alignment: .trailing, spacing: 2) {
                Text(
                    timerInterval: now...max(now, eta),
                    pauseTime: eta,
                    countsDown: true,
                    showsHours: false
                )
                .font(.title3.bold().monospacedDigit())

                if let badge {
                    Label(badge, systemImage: "clock.badge.exclamationmark")
                        .font(.caption2)
                        .foregroundStyle(.orange)
                }
            }
        case .arriving:
            Label("곧 도착", systemImage: "bus.fill")
                .foregroundStyle(.green)
        case .arrived:
            Label("도착", systemImage: "checkmark.circle.fill")
                .foregroundStyle(.green)
        case .cancelled:
            Label("운행 취소", systemImage: "xmark.circle.fill")
                .foregroundStyle(.red)
        case .stale:
            Label("데이터 지연", systemImage: "exclamationmark.triangle.fill")
                .foregroundStyle(.orange)
        case .unknown:
            Label("확인 중", systemImage: "ellipsis.circle")
                .foregroundStyle(.secondary)
        }
    }
}
