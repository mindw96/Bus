import Foundation
import XCTest
@testable import UNISTBus

final class TrackingSubscriptionTests: XCTestCase {
    private let trackingSessionId = "00000000-0000-0000-0000-000000000001"

    func testLiveActivityContentStateUsesUnixEpochFields() throws {
        let estimatedArrivalAt = Date(timeIntervalSince1970: 1_784_679_428.9)
        let observedAt = Date(timeIntervalSince1970: 1_784_679_000.2)
        let staleAt = Date(timeIntervalSince1970: 1_784_679_120.7)
        let state = BusArrivalActivityAttributes.ContentState(
            estimatedArrivalAt: estimatedArrivalAt,
            observedAt: observedAt,
            staleAt: staleAt,
            status: .approaching
        )

        XCTAssertEqual(state.estimatedArrivalEpochSeconds, 1_784_679_428)
        XCTAssertEqual(state.observedAtEpochSeconds, 1_784_679_000)
        XCTAssertEqual(state.staleAtEpochSeconds, 1_784_679_120)
        XCTAssertEqual(
            state.estimatedArrivalAt.timeIntervalSince1970,
            1_784_679_428,
            accuracy: 0.001
        )

        let data = try JSONEncoder().encode(state)
        let object = try XCTUnwrap(
            JSONSerialization.jsonObject(with: data) as? [String: Any]
        )

        XCTAssertEqual(
            Set(object.keys),
            Set([
                "estimatedArrivalEpochSeconds",
                "observedAtEpochSeconds",
                "staleAtEpochSeconds",
                "status"
            ])
        )
        XCTAssertEqual(object["status"] as? String, "APPROACHING")
        XCTAssertNil(object["estimatedArrivalAt"])

        let decoded = try JSONDecoder().decode(
            BusArrivalActivityAttributes.ContentState.self,
            from: data
        )
        XCTAssertEqual(decoded, state)
    }

    func testPutRequestMatchesTrackingSubscriptionContract() throws {
        let pushTarget = String(repeating: "01", count: 16)
        let requestPayload = makeRequest(pushTarget: pushTarget)
        let request = try TrackingSubscriptionClient.makePutRequest(
            baseURL: URL(string: "https://api.example.com")!,
            trackingSessionId: trackingSessionId,
            payload: requestPayload
        )

        XCTAssertEqual(request.httpMethod, "PUT")
        XCTAssertEqual(
            request.url?.absoluteString,
            "https://api.example.com/api/v1/tracking-subscriptions/\(trackingSessionId)"
        )
        XCTAssertEqual(
            request.value(forHTTPHeaderField: "Content-Type"),
            "application/json"
        )

        let body = try XCTUnwrap(request.httpBody)
        let object = try XCTUnwrap(
            JSONSerialization.jsonObject(with: body) as? [String: Any]
        )
        XCTAssertEqual(object["installationId"] as? String, "installation-1")
        XCTAssertEqual(object["platform"] as? String, "IOS_LIVE_ACTIVITY")
        XCTAssertEqual(object["pushTarget"] as? String, pushTarget)
        XCTAssertEqual(object["pushEnvironment"] as? String, "DEVELOPMENT")
        XCTAssertEqual(object["tripId"] as? String, "trip-513")
        XCTAssertEqual(object["routeId"] as? String, "route-513")
        XCTAssertEqual(object["routeName"] as? String, "513")
        XCTAssertEqual(object["stopId"] as? String, "40234")
        XCTAssertTrue(
            try XCTUnwrap(object["estimatedArrivalAt"] as? String)
                .hasPrefix("2026-07-22T")
        )
        XCTAssertTrue(
            try XCTUnwrap(object["expiresAt"] as? String)
                .hasSuffix("Z")
        )
    }

    func testDeleteRequestUsesSameResourcePath() {
        let request = TrackingSubscriptionClient.makeDeleteRequest(
            baseURL: URL(string: "https://api.example.com")!,
            trackingSessionId: trackingSessionId
        )

        XCTAssertEqual(request.httpMethod, "DELETE")
        XCTAssertEqual(
            request.url?.absoluteString,
            "https://api.example.com/api/v1/tracking-subscriptions/\(trackingSessionId)"
        )
    }

    func testDecodesTrackingSubscriptionResponse() throws {
        let data = Data(
            """
            {
              "trackingSessionId": "\(trackingSessionId)",
              "state": "ACTIVE",
              "updatedAt": "2026-07-22T00:10:00Z",
              "expiresAt": "2026-07-22T01:10:00.500Z"
            }
            """.utf8
        )

        let response = try TrackingSubscriptionClient.decodeResponse(data)

        XCTAssertEqual(response.trackingSessionId, trackingSessionId)
        XCTAssertEqual(response.state, "ACTIVE")
        XCTAssertEqual(
            response.expiresAt.timeIntervalSince1970,
            1_784_682_600.5,
            accuracy: 0.001
        )
    }

    func testPersistenceKeepsIdentifiersAndPendingOperationsAcrossInstances() async throws {
        let suiteName = "TrackingSubscriptionTests.\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suiteName))
        defaults.removePersistentDomain(forName: suiteName)
        defer {
            defaults.removePersistentDomain(forName: suiteName)
        }

        let storageKey = "tracking-state"
        let first = TrackingSubscriptionPersistence(
            suiteName: suiteName,
            storageKey: storageKey
        )
        let installationId = await first.installationId()
        let trackingSessionId = await first.trackingSessionId(
            for: "activity-1"
        )
        let initialRequest = makeRequest(pushTarget: String(repeating: "aa", count: 16))
        await first.stageUpsert(
            activityId: "activity-1",
            request: initialRequest
        )

        let restored = TrackingSubscriptionPersistence(
            suiteName: suiteName,
            storageKey: storageKey
        )

        let restoredInstallationId = await restored.installationId()
        let restoredTrackingSessionId = await restored.trackingSessionId(
            for: "activity-1"
        )
        let initialPendingOperations = await restored.pendingOperations()

        XCTAssertEqual(restoredInstallationId, installationId)
        XCTAssertEqual(restoredTrackingSessionId, trackingSessionId)
        XCTAssertEqual(
            initialPendingOperations,
            [
                .upsert(
                    activityId: "activity-1",
                    trackingSessionId: trackingSessionId,
                    request: initialRequest
                )
            ]
        )

        let replacementRequest = makeRequest(pushTarget: String(repeating: "bb", count: 16))
        await restored.stageUpsert(
            activityId: "activity-1",
            request: replacementRequest
        )
        await restored.completeUpsert(
            activityId: "activity-1",
            request: initialRequest
        )
        let replacementPendingOperations = await restored.pendingOperations()
        XCTAssertEqual(
            replacementPendingOperations,
            [
                .upsert(
                    activityId: "activity-1",
                    trackingSessionId: trackingSessionId,
                    request: replacementRequest
                )
            ]
        )

        await restored.completeUpsert(
            activityId: "activity-1",
            request: replacementRequest
        )
        let operationsAfterUpsert = await restored.pendingOperations()
        XCTAssertEqual(operationsAfterUpsert, [])

        await restored.stageDelete(activityId: "activity-1")
        let deletePendingOperations = await restored.pendingOperations()
        XCTAssertEqual(
            deletePendingOperations,
            [
                .delete(
                    activityId: "activity-1",
                    trackingSessionId: trackingSessionId
                )
            ]
        )
        await restored.completeDelete(
            activityId: "activity-1",
            trackingSessionId: trackingSessionId
        )
        let operationsAfterDelete = await restored.pendingOperations()
        XCTAssertEqual(operationsAfterDelete, [])
    }

    @MainActor
    func testCoordinatorCompletesDeleteBeforeReturning() async throws {
        let suiteName = "TrackingSubscriptionTests.\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suiteName))
        defaults.removePersistentDomain(forName: suiteName)
        defer {
            defaults.removePersistentDomain(forName: suiteName)
        }

        let persistence = TrackingSubscriptionPersistence(
            suiteName: suiteName,
            storageKey: "coordinator-delete-state"
        )
        let activityId = "activity-to-delete"
        let trackingSessionId = await persistence.trackingSessionId(
            for: activityId
        )
        let deletedSessions = DeletedTrackingSessions()
        let client = TrackingSubscriptionClient(
            upsert: { sessionId, request in
                TrackingSubscriptionResponse(
                    trackingSessionId: sessionId,
                    state: "ACTIVE",
                    updatedAt: request.estimatedArrivalAt,
                    expiresAt: request.expiresAt
                )
            },
            delete: { sessionId in
                await deletedSessions.append(sessionId)
            }
        )
        let coordinator = LiveActivityPushCoordinator(
            isEnabled: true,
            pushEnvironment: .development,
            client: client,
            persistence: persistence
        )

        await coordinator.activityDidEnd(activityId: activityId)

        let recordedDeletes = await deletedSessions.values()
        let pendingOperations = await persistence.pendingOperations()
        XCTAssertEqual(recordedDeletes, [trackingSessionId])
        XCTAssertEqual(pendingOperations, [])
    }

    private func makeRequest(pushTarget: String) -> TrackingSubscriptionRequest {
        TrackingSubscriptionRequest(
            installationId: "installation-1",
            pushTarget: pushTarget,
            pushEnvironment: .development,
            tripId: "trip-513",
            routeId: "route-513",
            routeName: "513",
            stopId: "40234",
            estimatedArrivalAt: Date(timeIntervalSince1970: 1_784_679_420),
            expiresAt: Date(timeIntervalSince1970: 1_784_683_020)
        )
    }
}

private actor DeletedTrackingSessions {
    private var sessionIds: [String] = []

    func append(_ sessionId: String) {
        sessionIds.append(sessionId)
    }

    func values() -> [String] {
        sessionIds
    }
}
