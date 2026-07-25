import Foundation

enum PendingTrackingSubscriptionOperation: Equatable, Sendable {
    case upsert(
        activityId: String,
        trackingSessionId: String,
        request: TrackingSubscriptionRequest
    )
    case delete(
        activityId: String,
        trackingSessionId: String
    )
}

actor TrackingSubscriptionPersistence {
    static let live = TrackingSubscriptionPersistence()

    private struct PersistedState: Codable {
        var installationId: String?
        var sessionsByActivityId: [String: PersistedSession]

        static let empty = PersistedState(
            installationId: nil,
            sessionsByActivityId: [:]
        )
    }

    private struct PersistedSession: Codable {
        let trackingSessionId: String
        var pendingRequest: TrackingSubscriptionRequest?
        var isPendingDelete: Bool
    }

    private let defaults: UserDefaults
    private let storageKey: String
    private var state: PersistedState

    init(
        suiteName: String? = nil,
        storageKey: String = "kr.ac.unist.bus.live-activity-tracking.v1"
    ) {
        let defaults = suiteName.flatMap(UserDefaults.init(suiteName:))
            ?? .standard
        self.defaults = defaults
        self.storageKey = storageKey

        if
            let data = defaults.data(forKey: storageKey),
            let decoded = try? JSONDecoder().decode(PersistedState.self, from: data)
        {
            state = decoded
        } else {
            state = .empty
        }
    }

    func installationId() -> String {
        if let installationId = state.installationId {
            return installationId
        }

        let installationId = UUID().uuidString.lowercased()
        state.installationId = installationId
        save()
        return installationId
    }

    func trackingSessionId(for activityId: String) -> String {
        if let session = state.sessionsByActivityId[activityId] {
            return session.trackingSessionId
        }

        let trackingSessionId = UUID().uuidString.lowercased()
        state.sessionsByActivityId[activityId] = PersistedSession(
            trackingSessionId: trackingSessionId,
            pendingRequest: nil,
            isPendingDelete: false
        )
        save()
        return trackingSessionId
    }

    func stageUpsert(
        activityId: String,
        request: TrackingSubscriptionRequest
    ) {
        let trackingSessionId = trackingSessionId(for: activityId)
        guard state.sessionsByActivityId[activityId]?.isPendingDelete != true else {
            return
        }

        state.sessionsByActivityId[activityId] = PersistedSession(
            trackingSessionId: trackingSessionId,
            pendingRequest: request,
            isPendingDelete: false
        )
        save()
    }

    func completeUpsert(
        activityId: String,
        request: TrackingSubscriptionRequest
    ) {
        guard
            var session = state.sessionsByActivityId[activityId],
            !session.isPendingDelete,
            session.pendingRequest == request
        else {
            return
        }

        session.pendingRequest = nil
        state.sessionsByActivityId[activityId] = session
        save()
    }

    @discardableResult
    func stageDelete(activityId: String) -> String? {
        guard var session = state.sessionsByActivityId[activityId] else {
            return nil
        }

        session.pendingRequest = nil
        session.isPendingDelete = true
        state.sessionsByActivityId[activityId] = session
        save()
        return session.trackingSessionId
    }

    func completeDelete(
        activityId: String,
        trackingSessionId: String
    ) {
        guard
            let session = state.sessionsByActivityId[activityId],
            session.trackingSessionId == trackingSessionId,
            session.isPendingDelete
        else {
            return
        }

        state.sessionsByActivityId.removeValue(forKey: activityId)
        save()
    }

    func pendingOperations() -> [PendingTrackingSubscriptionOperation] {
        state.sessionsByActivityId
            .sorted { $0.key < $1.key }
            .compactMap { activityId, session in
                if session.isPendingDelete {
                    return .delete(
                        activityId: activityId,
                        trackingSessionId: session.trackingSessionId
                    )
                }
                if let request = session.pendingRequest {
                    return .upsert(
                        activityId: activityId,
                        trackingSessionId: session.trackingSessionId,
                        request: request
                    )
                }
                return nil
            }
    }

    private func save() {
        guard let data = try? JSONEncoder().encode(state) else {
            return
        }
        defaults.set(data, forKey: storageKey)
    }
}
