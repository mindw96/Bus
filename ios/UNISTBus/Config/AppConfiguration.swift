import Foundation

struct AppConfiguration: Equatable, Sendable {
    enum ArrivalSource: Equatable, Sendable {
        case fixture
        case live(URL)
    }

    let arrivalSource: ArrivalSource
    let remoteLiveActivityEnabled: Bool
    let pushEnvironment: TrackingPushEnvironment

    var arrivalClient: ArrivalClient {
        switch arrivalSource {
        case .fixture:
            .fixture()
        case .live(let baseURL):
            .live(baseURL: baseURL)
        }
    }

    var trackingSubscriptionClient: TrackingSubscriptionClient {
        guard remoteLiveActivityEnabled else {
            return .disabled
        }
        guard case .live(let baseURL) = arrivalSource else {
            return .disabled
        }
        return .live(baseURL: baseURL)
    }

    static func current(
        bundle: Bundle = .main,
        arguments: [String] = ProcessInfo.processInfo.arguments
    ) -> AppConfiguration {
#if DEBUG
        let allowsFixture = true
#else
        let allowsFixture = false
#endif

        guard let configuration = resolve(
            baseURLString: bundle.object(
                forInfoDictionaryKey: "UNISTBusAPIBaseURL"
            ) as? String,
            arguments: arguments,
            allowsFixture: allowsFixture,
            remoteLiveActivityEnabled: boolValue(
                bundle.object(
                    forInfoDictionaryKey: "UNISTBusRemoteLiveActivityEnabled"
                )
            ),
            pushEnvironmentString: bundle.object(
                forInfoDictionaryKey: "UNISTBusPushEnvironment"
            ) as? String
        ) else {
            preconditionFailure("UNISTBusAPIBaseURL이 올바르게 설정되지 않았습니다.")
        }
        return configuration
    }

    static func resolve(
        baseURLString: String?,
        arguments: [String],
        allowsFixture: Bool,
        remoteLiveActivityEnabled: Bool = false,
        pushEnvironmentString: String? = "DEVELOPMENT"
    ) -> AppConfiguration? {
        guard
            let pushEnvironmentString,
            let pushEnvironment = TrackingPushEnvironment(
                rawValue: pushEnvironmentString.uppercased()
            )
        else {
            return nil
        }

        if allowsFixture, arguments.contains("-fixture-arrivals") {
            return AppConfiguration(
                arrivalSource: .fixture,
                remoteLiveActivityEnabled: false,
                pushEnvironment: pushEnvironment
            )
        }

        guard
            let baseURLString,
            let baseURL = URL(string: baseURLString),
            let scheme = baseURL.scheme?.lowercased(),
            scheme == "http" || scheme == "https"
        else {
            return nil
        }

        return AppConfiguration(
            arrivalSource: .live(baseURL),
            remoteLiveActivityEnabled: remoteLiveActivityEnabled,
            pushEnvironment: pushEnvironment
        )
    }

    private static func boolValue(_ value: Any?) -> Bool {
        if let value = value as? Bool {
            return value
        }
        if let value = value as? NSNumber {
            return value.boolValue
        }
        if let value = value as? String {
            return ["1", "TRUE", "YES"].contains(value.uppercased())
        }
        return false
    }
}
