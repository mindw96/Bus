import ActivityKit
import SwiftUI
import WidgetKit

struct BusArrivalLiveActivity: Widget {
    var body: some WidgetConfiguration {
        ActivityConfiguration(for: BusArrivalActivityAttributes.self) { context in
            BusArrivalLockScreenView(context: context)
                .activityBackgroundTint(.blue.opacity(0.12))
                .activitySystemActionForegroundColor(.primary)
        } dynamicIsland: { context in
            DynamicIsland {
                DynamicIslandExpandedRegion(.leading) {
                    Label("\(context.attributes.routeName)번", systemImage: "bus.fill")
                        .font(.headline)
                }

                DynamicIslandExpandedRegion(.trailing) {
                    BusArrivalCompactStatus(context: context, mode: .compact)
                }

                DynamicIslandExpandedRegion(.center) {
                    Text("\(context.attributes.destination) 방면")
                        .font(.subheadline.weight(.semibold))
                        .lineLimit(1)
                        .minimumScaleFactor(0.8)
                }

                DynamicIslandExpandedRegion(.bottom) {
                    BusArrivalExpandedDetails(context: context)
                }
            } compactLeading: {
                HStack(spacing: 3) {
                    Image(systemName: "bus.fill")
                    Text(context.attributes.routeName)
                        .fontWeight(.bold)
                }
                .font(.caption2)
                .accessibilityLabel("\(context.attributes.routeName)번 버스")
            } compactTrailing: {
                BusArrivalCompactStatus(context: context, mode: .compact)
            } minimal: {
                BusArrivalCompactStatus(context: context, mode: .minimal)
            }
            .keylineTint(.blue)
        }
    }
}

private struct BusArrivalLockScreenView: View {
    let context: ActivityViewContext<BusArrivalActivityAttributes>

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            BusArrivalLockScreenHeader(context: context)

            BusArrivalProminentStatus(context: context)
                .frame(maxWidth: .infinity)

            Divider()

            BusArrivalLockScreenFooter(context: context)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 8)
    }
}

private struct BusArrivalLockScreenHeader: View {
    let context: ActivityViewContext<BusArrivalActivityAttributes>

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 8) {
            Image(systemName: "bus.fill")
                .font(.system(size: 20, weight: .bold))
                .foregroundStyle(.blue)
                .accessibilityHidden(true)

            Text("\(context.attributes.routeName)번")
                .font(.system(size: 24, weight: .bold, design: .rounded))
                .fixedSize(horizontal: true, vertical: false)

            Text("\(context.attributes.destination) 방면")
                .font(.system(size: 16, weight: .semibold))
                .foregroundStyle(.secondary)
                .lineLimit(1)
                .minimumScaleFactor(0.8)
        }
        .accessibilityElement(children: .combine)
    }
}

private struct BusArrivalLockScreenFooter: View {
    let context: ActivityViewContext<BusArrivalActivityAttributes>

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 8) {
            Label(context.attributes.stopName, systemImage: "mappin.circle")
                .font(.system(size: 14, weight: .medium))
                .lineLimit(1)
                .minimumScaleFactor(0.75)

            Spacer(minLength: 4)

            HStack(spacing: 4) {
                if context.isStale {
                    Image(systemName: "exclamationmark.triangle.fill")
                        .foregroundStyle(.orange)
                        .accessibilityLabel("정보 갱신 지연")
                }

                Text(context.attributes.countdownKind.scheduledTitle)
                Text(context.state.estimatedArrivalAt, style: .time)
                    .monospacedDigit()
            }
            .font(.system(size: 14, weight: .semibold))
            .foregroundStyle(.primary)
            .lineLimit(1)
            .minimumScaleFactor(0.8)
        }
        .foregroundStyle(.secondary)
    }
}

private struct BusArrivalProminentStatus: View {
    let context: ActivityViewContext<BusArrivalActivityAttributes>

    private var kind: BusCountdownKind {
        context.attributes.countdownKind
    }

    private var phase: BusLiveActivityPhase {
        context.state.liveActivityPhase(isStale: context.isStale, now: Date())
    }

    var body: some View {
        Group {
            switch phase {
            case .completed:
                statusLabel(
                    kind.completedTitle,
                    systemImage: "checkmark.circle.fill",
                    color: .green
                )
            case .cancelled:
                statusLabel("운행 취소", systemImage: "xmark.circle.fill", color: .red)
            case .refreshing:
                statusLabel(
                    "\(kind.eventName) 정보 갱신 중",
                    systemImage: "exclamationmark.triangle.fill",
                    color: .orange
                )
            case .imminent:
                statusLabel(kind.imminentTitle, systemImage: "bus.fill", color: .green)
            case .unknown:
                statusLabel("정보 확인 중", systemImage: "ellipsis.circle", color: .secondary)
            case .countdown:
                countdown
            }
        }
        .frame(maxWidth: .infinity, alignment: .center)
    }

    private var countdown: some View {
        HStack(alignment: .firstTextBaseline, spacing: 10) {
            Text("남은 시간:")
                .font(.system(size: 18, weight: .bold))
                .foregroundStyle(context.isStale ? Color.orange : Color.secondary)
                .fixedSize(horizontal: true, vertical: false)

            KoreanUnitCountdownText(
                estimatedAt: context.state.estimatedArrivalAt,
                accessibilityPrefix: context.isStale
                    ? "마지막 정보 기준 \(kind.countdownTitle) 남은 시간"
                    : "\(kind.countdownTitle) 남은 시간",
                font: .system(size: 40, weight: .heavy, design: .rounded)
            )
            .foregroundStyle(countdownColor)
            .layoutPriority(1)

            if context.isStale {
                Image(systemName: "exclamationmark.triangle.fill")
                    .font(.caption)
                    .foregroundStyle(.orange)
                    .accessibilityLabel("마지막 정보 기준")
            } else if context.state.status == .delayed {
                Image(systemName: "clock.badge.exclamationmark")
                    .font(.caption)
                    .foregroundStyle(.orange)
                    .accessibilityLabel("지연")
            }
        }
        .frame(maxWidth: .infinity, alignment: .center)
    }

    private var countdownColor: Color {
        context.isStale || context.state.status == .delayed ? .orange : .primary
    }

    private func statusLabel(_ title: String, systemImage: String, color: Color) -> some View {
        Label(title, systemImage: systemImage)
            .font(.system(size: 24, weight: .bold))
            .foregroundStyle(color)
            .multilineTextAlignment(.center)
            .lineLimit(2)
            .frame(maxWidth: .infinity, alignment: .center)
    }
}

private struct KoreanUnitCountdownText: View {
    let estimatedAt: Date
    let accessibilityPrefix: String
    let font: Font

    var body: some View {
        let countdown = Text(estimatedAt, style: .relative)

        countdown
            .font(font)
            .monospacedDigit()
            .lineLimit(1)
            .minimumScaleFactor(0.7)
            .environment(\.locale, Locale(identifier: "ko_KR"))
            .contentTransition(.numericText(countsDown: true))
            .accessibilityLabel(accessibilityPrefix)
            .accessibilityValue(countdown)
    }
}

private struct BusArrivalExpandedDetails: View {
    let context: ActivityViewContext<BusArrivalActivityAttributes>

    var body: some View {
        VStack(spacing: 8) {
            HStack(spacing: 8) {
                Label(context.attributes.stopName, systemImage: "mappin.circle")
                    .lineLimit(1)

                Spacer(minLength: 4)

                freshnessLabel
            }
            .font(.caption)
            .foregroundStyle(.secondary)

            HStack(alignment: .firstTextBaseline, spacing: 8) {
                Text(expandedTitle)
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(context.isStale ? Color.orange : Color.secondary)
                    .lineLimit(1)
                    .minimumScaleFactor(0.75)

                Spacer(minLength: 6)

                BusArrivalExpandedStatus(context: context)
            }
        }
    }

    private var expandedTitle: String {
        if context.state.status == .arrived {
            return context.attributes.countdownKind.completedTitle
        }
        if context.state.status == .cancelled {
            return "운행 상태"
        }
        if context.isStale {
            return "마지막 정보 기준 · \(context.attributes.countdownKind.countdownTitle)"
        }
        return context.attributes.countdownKind.countdownTitle
    }

    @ViewBuilder
    private var freshnessLabel: some View {
        if context.isStale {
            Label("정보 갱신 지연", systemImage: "exclamationmark.triangle.fill")
                .foregroundStyle(.orange)
        } else {
            Label("최신 정보", systemImage: "checkmark.circle")
        }
    }
}

private struct BusArrivalExpandedStatus: View {
    let context: ActivityViewContext<BusArrivalActivityAttributes>

    private var phase: BusLiveActivityPhase {
        context.state.liveActivityPhase(isStale: context.isStale, now: Date())
    }

    var body: some View {
        Group {
            switch phase {
            case .completed:
                Image(systemName: "checkmark.circle.fill")
                    .foregroundStyle(.green)
                    .accessibilityLabel(context.attributes.countdownKind.completedTitle)
            case .cancelled:
                Label("운행 취소", systemImage: "xmark.circle.fill")
                    .foregroundStyle(.red)
            case .refreshing:
                Label("갱신 중", systemImage: "exclamationmark.triangle.fill")
                    .foregroundStyle(.orange)
            case .imminent:
                Text(context.attributes.countdownKind.imminentTitle)
                    .foregroundStyle(.green)
            case .unknown:
                Label("확인 중", systemImage: "ellipsis.circle")
                    .foregroundStyle(.secondary)
            case .countdown:
                SystemCountdownText(
                    estimatedAt: context.state.estimatedArrivalAt,
                    accessibilityPrefix: context.isStale
                        ? "마지막 정보 기준 \(context.attributes.countdownKind.countdownTitle)"
                        : context.attributes.countdownKind.countdownTitle,
                    font: .title2.bold()
                )
                .foregroundStyle(
                    context.isStale || context.state.status == .delayed
                        ? Color.orange
                        : Color.primary
                )
            }
        }
        .font(.headline)
        .monospacedDigit()
    }
}

private struct BusArrivalCompactStatus: View {
    enum Mode {
        case compact
        case minimal

        var countdownFont: Font {
            switch self {
            case .compact:
                .caption2
            case .minimal:
                .system(size: 9, weight: .semibold, design: .rounded)
            }
        }
    }

    let context: ActivityViewContext<BusArrivalActivityAttributes>
    let mode: Mode

    private var phase: BusLiveActivityPhase {
        context.state.liveActivityPhase(isStale: context.isStale, now: Date())
    }

    var body: some View {
        switch phase {
        case .completed:
            Image(systemName: "checkmark.circle.fill")
                .foregroundStyle(.green)
                .accessibilityLabel(context.attributes.countdownKind.completedTitle)
        case .cancelled:
            Image(systemName: "xmark.circle.fill")
                .foregroundStyle(.red)
                .accessibilityLabel("운행 취소")
        case .refreshing:
            Image(systemName: "exclamationmark.triangle.fill")
                .foregroundStyle(.orange)
                .accessibilityLabel("정보 갱신 지연")
        case .imminent:
            Text("곧")
                .foregroundStyle(.green)
                .accessibilityLabel(context.attributes.countdownKind.imminentTitle)
        case .unknown:
            Image(systemName: "ellipsis.circle")
                .foregroundStyle(.secondary)
                .accessibilityLabel("정보 확인 중")
        case .countdown:
            SystemCountdownText(
                estimatedAt: context.state.estimatedArrivalAt,
                accessibilityPrefix: context.isStale
                    ? "마지막 정보 기준 \(context.attributes.countdownKind.countdownTitle)"
                    : context.attributes.countdownKind.countdownTitle,
                font: mode.countdownFont
            )
            .foregroundStyle(
                context.isStale || context.state.status == .delayed
                    ? Color.orange
                    : Color.primary
            )
        }
    }
}

private struct SystemCountdownText: View {
    let estimatedAt: Date
    let accessibilityPrefix: String
    let font: Font

    var body: some View {
        let now = Date()
        let timer = Text(
            timerInterval: now...max(now, estimatedAt),
            pauseTime: estimatedAt,
            countsDown: true,
            showsHours: false
        )

        timer
            .font(font)
            .monospacedDigit()
            .lineLimit(1)
            .minimumScaleFactor(0.7)
            .accessibilityLabel(accessibilityPrefix)
            .accessibilityValue(timer)
    }
}

private extension BusArrivalActivityAttributes {
    static var arrivalPreview: BusArrivalActivityAttributes {
        BusArrivalActivityAttributes(
            tripID: "preview-trip-513",
            routeID: "preview-route-513",
            routeName: "513",
            destination: "덕하 차고지",
            stopID: "40234",
            stopName: "울산과학기술원",
            countdownKind: .arrival
        )
    }

    static var departurePreview: BusArrivalActivityAttributes {
        BusArrivalActivityAttributes(
            tripID: "preview-trip-713",
            routeID: "preview-route-713",
            routeName: "713",
            destination: "덕하 차고지",
            stopID: "40233",
            stopName: "울산과학기술원(기점)",
            countdownKind: .departure
        )
    }
}

private extension BusArrivalActivityAttributes.ContentState {
    static var approachingPreview: BusArrivalActivityAttributes.ContentState {
        let now = Date()
        return BusArrivalActivityAttributes.ContentState(
            estimatedArrivalAt: now.addingTimeInterval(8 * 60 + 26),
            observedAt: now,
            staleAt: now.addingTimeInterval(20 * 60),
            status: .approaching
        )
    }

    static var delayedPreview: BusArrivalActivityAttributes.ContentState {
        let now = Date()
        return BusArrivalActivityAttributes.ContentState(
            estimatedArrivalAt: now.addingTimeInterval(12 * 60),
            observedAt: now,
            staleAt: now.addingTimeInterval(20 * 60),
            status: .delayed
        )
    }

    static var arrivingPreview: BusArrivalActivityAttributes.ContentState {
        let now = Date()
        return BusArrivalActivityAttributes.ContentState(
            estimatedArrivalAt: now.addingTimeInterval(45),
            observedAt: now,
            staleAt: now.addingTimeInterval(2 * 60),
            status: .arriving
        )
    }

    static var arrivedPreview: BusArrivalActivityAttributes.ContentState {
        let now = Date()
        return BusArrivalActivityAttributes.ContentState(
            estimatedArrivalAt: now,
            observedAt: now,
            staleAt: now.addingTimeInterval(2 * 60),
            status: .arrived
        )
    }

    static var cancelledPreview: BusArrivalActivityAttributes.ContentState {
        let now = Date()
        return BusArrivalActivityAttributes.ContentState(
            estimatedArrivalAt: now.addingTimeInterval(8 * 60),
            observedAt: now,
            staleAt: now.addingTimeInterval(2 * 60),
            status: .cancelled
        )
    }
}

#Preview("잠금화면 · 도착", as: .content, using: BusArrivalActivityAttributes.arrivalPreview) {
    BusArrivalLiveActivity()
} contentStates: {
    BusArrivalActivityAttributes.ContentState.approachingPreview
    BusArrivalActivityAttributes.ContentState.delayedPreview
    BusArrivalActivityAttributes.ContentState.arrivingPreview
    BusArrivalActivityAttributes.ContentState.arrivedPreview
    BusArrivalActivityAttributes.ContentState.cancelledPreview
}

#Preview("잠금화면 · 출발", as: .content, using: BusArrivalActivityAttributes.departurePreview) {
    BusArrivalLiveActivity()
} contentStates: {
    BusArrivalActivityAttributes.ContentState.approachingPreview
    BusArrivalActivityAttributes.ContentState.delayedPreview
}

#Preview(
    "Dynamic Island · 확장",
    as: .dynamicIsland(.expanded),
    using: BusArrivalActivityAttributes.departurePreview
) {
    BusArrivalLiveActivity()
} contentStates: {
    BusArrivalActivityAttributes.ContentState.approachingPreview
    BusArrivalActivityAttributes.ContentState.delayedPreview
}
