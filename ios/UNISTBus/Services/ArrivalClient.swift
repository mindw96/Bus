import Foundation

struct ArrivalClient: Sendable {
    let fetchArrivals: @Sendable (_ stopID: String) async throws -> ArrivalResponse

    static func fixture(
        clock: DateProvider = .live,
        delay: Duration? = .milliseconds(180)
    ) -> ArrivalClient {
        ArrivalClient { stopID in
            if let delay {
                try await Task.sleep(for: delay)
            }

            let now = clock.now()
            let stop = MainBusStop(rawValue: stopID)?.summary
                ?? StopSummary(id: stopID, name: "정류장 \(stopID)")

            let routes: [(name: String, destination: String, minutes: Int, status: ArrivalStatus)]
            switch MainBusStop(rawValue: stopID) {
            case .campus:
                routes = [
                    ("513", "덕하 차고지", 6, .approaching)
                ]
            case .origin:
                routes = [
                    ("713", "덕하 차고지", 9, .approaching),
                    ("743", "덕하 차고지", 13, .scheduled),
                    ("753", "덕하 차고지", 17, .delayed),
                    ("1115", "덕하 차고지", 22, .scheduled)
                ]
            case .terminus:
                // 기점과 종점 API가 같은 노선을 반환해도 화면에는 가장 이른 한 건만 남깁니다.
                routes = [
                    ("713", "UNIST", 29, .scheduled),
                    ("743", "UNIST", 33, .scheduled),
                    ("753", "UNIST", 37, .scheduled),
                    ("1115", "UNIST", 42, .scheduled)
                ]
            case nil:
                routes = []
            }

            return ArrivalResponse(
                stop: stop,
                arrivals: routes.map { route in
                    Arrival(
                        tripId: "fixture-\(stopID)-trip-\(route.name)",
                        routeId: "fixture-route-\(route.name)",
                        routeName: route.name,
                        destination: route.destination,
                        scheduledArrivalAt: now.addingTimeInterval(TimeInterval((route.minutes + 2) * 60)),
                        estimatedArrivalAt: now.addingTimeInterval(TimeInterval(route.minutes * 60)),
                        observedAt: now,
                        staleAt: now.addingTimeInterval(2 * 60),
                        status: route.status
                    )
                }
            )
        }
    }

    static func live(baseURL: URL, session: URLSession = .shared) -> ArrivalClient {
        ArrivalClient { stopID in
            let url = baseURL.appending(path: "api/v1/stops/\(stopID)/arrivals")
            let (data, response) = try await session.data(from: url)

            guard let response = response as? HTTPURLResponse else {
                throw ArrivalClientError.invalidResponse
            }
            guard 200..<300 ~= response.statusCode else {
                throw ArrivalClientError.server(statusCode: response.statusCode)
            }

            let decoded = try decode(data)
            guard decoded.stop.id == stopID else {
                throw ArrivalClientError.mismatchedStop(
                    expected: stopID,
                    actual: decoded.stop.id
                )
            }
            return decoded
        }
    }

    static func decode(_ data: Data) throws -> ArrivalResponse {
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .custom { decoder in
            let container = try decoder.singleValueContainer()
            let rawValue = try container.decode(String.self)

            let fractional = Date.ISO8601FormatStyle(includingFractionalSeconds: true)
            if let value = try? fractional.parse(rawValue) {
                return value
            }

            let standard = Date.ISO8601FormatStyle()
            if let value = try? standard.parse(rawValue) {
                return value
            }

            throw DecodingError.dataCorruptedError(
                in: container,
                debugDescription: "지원하지 않는 RFC 3339 날짜입니다: \(rawValue)"
            )
        }
        return try decoder.decode(ArrivalResponse.self, from: data)
    }
}

enum ArrivalClientError: LocalizedError, Sendable {
    case invalidResponse
    case server(statusCode: Int)
    case mismatchedStop(expected: String, actual: String)

    var errorDescription: String? {
        switch self {
        case .invalidResponse:
            "서버 응답을 확인할 수 없습니다."
        case .server(let statusCode):
            "서버가 오류를 반환했습니다. (HTTP \(statusCode))"
        case .mismatchedStop(let expected, let actual):
            "요청한 정류장(\(expected))과 서버 응답(\(actual))이 일치하지 않습니다."
        }
    }
}
