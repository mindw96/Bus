import Foundation

struct AppConfiguration: Equatable, Sendable {
    enum ArrivalSource: Equatable, Sendable {
        case fixture
        case live(URL)
    }

    let arrivalSource: ArrivalSource

    var arrivalClient: ArrivalClient {
        switch arrivalSource {
        case .fixture:
            .fixture()
        case .live(let baseURL):
            .live(baseURL: baseURL)
        }
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
            allowsFixture: allowsFixture
        ) else {
            preconditionFailure("UNISTBusAPIBaseURL이 올바르게 설정되지 않았습니다.")
        }
        return configuration
    }

    static func resolve(
        baseURLString: String?,
        arguments: [String],
        allowsFixture: Bool
    ) -> AppConfiguration? {
        if allowsFixture, arguments.contains("-fixture-arrivals") {
            return AppConfiguration(arrivalSource: .fixture)
        }

        guard
            let baseURLString,
            let baseURL = URL(string: baseURLString),
            let scheme = baseURL.scheme?.lowercased(),
            scheme == "http" || scheme == "https"
        else {
            return nil
        }

        return AppConfiguration(arrivalSource: .live(baseURL))
    }
}
