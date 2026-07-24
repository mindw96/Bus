import Foundation

struct DateProvider: Sendable {
    let now: @Sendable () -> Date

    static let live = DateProvider(now: Date.init)

    static func constant(_ date: Date) -> DateProvider {
        DateProvider(now: { date })
    }
}
