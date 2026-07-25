import Foundation

enum TrackingPushEnvironment: String, Codable, Equatable, Sendable {
    case development = "DEVELOPMENT"
    case production = "PRODUCTION"
}

struct TrackingSubscriptionRequest: Codable, Equatable, Sendable {
    let installationId: String
    let platform: String
    let pushTarget: String
    let pushEnvironment: TrackingPushEnvironment
    let tripId: String
    let routeId: String
    let routeName: String
    let stopId: String
    let estimatedArrivalAt: Date
    let expiresAt: Date

    init(
        installationId: String,
        pushTarget: String,
        pushEnvironment: TrackingPushEnvironment,
        tripId: String,
        routeId: String,
        routeName: String,
        stopId: String,
        estimatedArrivalAt: Date,
        expiresAt: Date
    ) {
        self.installationId = installationId
        platform = "IOS_LIVE_ACTIVITY"
        self.pushTarget = pushTarget
        self.pushEnvironment = pushEnvironment
        self.tripId = tripId
        self.routeId = routeId
        self.routeName = routeName
        self.stopId = stopId
        self.estimatedArrivalAt = estimatedArrivalAt
        self.expiresAt = expiresAt
    }
}

struct TrackingSubscriptionResponse: Codable, Equatable, Sendable {
    let trackingSessionId: String
    let state: String
    let updatedAt: Date
    let expiresAt: Date
}

struct TrackingSubscriptionClient: Sendable {
    let upsert: @Sendable (
        _ trackingSessionId: String,
        _ request: TrackingSubscriptionRequest
    ) async throws -> TrackingSubscriptionResponse
    let delete: @Sendable (_ trackingSessionId: String) async throws -> Void

    static let disabled = TrackingSubscriptionClient(
        upsert: { _, _ in throw TrackingSubscriptionClientError.disabled },
        delete: { _ in throw TrackingSubscriptionClientError.disabled }
    )

    static func live(
        baseURL: URL,
        session: URLSession = .shared
    ) -> TrackingSubscriptionClient {
        TrackingSubscriptionClient(
            upsert: { trackingSessionId, payload in
                let request = try makePutRequest(
                    baseURL: baseURL,
                    trackingSessionId: trackingSessionId,
                    payload: payload
                )
                let (data, response) = try await session.data(for: request)
                try validate(response, allowingNotFound: false)

                let decoded = try decodeResponse(data)
                guard decoded.trackingSessionId == trackingSessionId else {
                    throw TrackingSubscriptionClientError.mismatchedTrackingSession(
                        expected: trackingSessionId,
                        actual: decoded.trackingSessionId
                    )
                }
                return decoded
            },
            delete: { trackingSessionId in
                let request = makeDeleteRequest(
                    baseURL: baseURL,
                    trackingSessionId: trackingSessionId
                )
                let (_, response) = try await session.data(for: request)
                try validate(response, allowingNotFound: true)
            }
        )
    }

    static func makePutRequest(
        baseURL: URL,
        trackingSessionId: String,
        payload: TrackingSubscriptionRequest
    ) throws -> URLRequest {
        var request = URLRequest(
            url: subscriptionURL(
                baseURL: baseURL,
                trackingSessionId: trackingSessionId
            )
        )
        request.httpMethod = "PUT"
        request.timeoutInterval = 15
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try makeEncoder().encode(payload)
        return request
    }

    static func makeDeleteRequest(
        baseURL: URL,
        trackingSessionId: String
    ) -> URLRequest {
        var request = URLRequest(
            url: subscriptionURL(
                baseURL: baseURL,
                trackingSessionId: trackingSessionId
            )
        )
        request.httpMethod = "DELETE"
        request.timeoutInterval = 15
        return request
    }

    static func decodeResponse(_ data: Data) throws -> TrackingSubscriptionResponse {
        try makeDecoder().decode(TrackingSubscriptionResponse.self, from: data)
    }

    private static func subscriptionURL(
        baseURL: URL,
        trackingSessionId: String
    ) -> URL {
        baseURL
            .appending(path: "api")
            .appending(path: "v1")
            .appending(path: "tracking-subscriptions")
            .appending(path: trackingSessionId)
    }

    private static func validate(
        _ response: URLResponse,
        allowingNotFound: Bool
    ) throws {
        guard let response = response as? HTTPURLResponse else {
            throw TrackingSubscriptionClientError.invalidResponse
        }
        if allowingNotFound, response.statusCode == 404 {
            return
        }
        guard 200..<300 ~= response.statusCode else {
            throw TrackingSubscriptionClientError.server(
                statusCode: response.statusCode
            )
        }
    }

    private static func makeEncoder() -> JSONEncoder {
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .custom { date, encoder in
            var container = encoder.singleValueContainer()
            try container.encode(
                date.formatted(
                    Date.ISO8601FormatStyle(includingFractionalSeconds: true)
                )
            )
        }
        return encoder
    }

    private static func makeDecoder() -> JSONDecoder {
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .custom { decoder in
            let container = try decoder.singleValueContainer()
            let rawValue = try container.decode(String.self)

            let fractional = Date.ISO8601FormatStyle(includingFractionalSeconds: true)
            if let value = try? fractional.parse(rawValue) {
                return value
            }
            if let value = try? Date.ISO8601FormatStyle().parse(rawValue) {
                return value
            }
            throw DecodingError.dataCorruptedError(
                in: container,
                debugDescription: "지원하지 않는 RFC 3339 날짜입니다."
            )
        }
        return decoder
    }
}

enum TrackingSubscriptionClientError: LocalizedError, Sendable {
    case disabled
    case invalidResponse
    case server(statusCode: Int)
    case mismatchedTrackingSession(expected: String, actual: String)

    var errorDescription: String? {
        switch self {
        case .disabled:
            "원격 Live Activity 등록이 비활성화되어 있습니다."
        case .invalidResponse:
            "추적 등록 서버 응답을 확인할 수 없습니다."
        case .server(let statusCode):
            "추적 등록 서버가 오류를 반환했습니다. (HTTP \(statusCode))"
        case .mismatchedTrackingSession:
            "추적 등록 서버 응답의 세션이 요청과 일치하지 않습니다."
        }
    }
}
