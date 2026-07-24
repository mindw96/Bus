import Foundation

struct ArrivalCacheClient: Sendable {
    let load: @Sendable () async -> [ArrivalResponse]
    let save: @Sendable (_ response: ArrivalResponse) async -> Void

    static let disabled = ArrivalCacheClient(
        load: { [] },
        save: { _ in }
    )

    static let live: ArrivalCacheClient = {
        guard let directory = FileManager.default.urls(
            for: .cachesDirectory,
            in: .userDomainMask
        ).first else {
            return .disabled
        }

        let storage = FileArrivalCache(
            fileURL: directory.appending(path: "unist-bus-arrivals-v1.json")
        )
        return ArrivalCacheClient(
            load: { await storage.load() },
            save: { response in await storage.save(response) }
        )
    }()

    static func file(_ fileURL: URL) -> ArrivalCacheClient {
        let storage = FileArrivalCache(fileURL: fileURL)
        return ArrivalCacheClient(
            load: { await storage.load() },
            save: { response in await storage.save(response) }
        )
    }
}

private actor FileArrivalCache {
    private let fileURL: URL
    private var responsesByStopID: [String: ArrivalResponse]?

    init(fileURL: URL) {
        self.fileURL = fileURL
    }

    func load() -> [ArrivalResponse] {
        if let responsesByStopID {
            return Array(responsesByStopID.values)
        }

        guard
            let data = try? Data(contentsOf: fileURL),
            let responses = try? Self.decode(data)
        else {
            responsesByStopID = [:]
            return []
        }

        let indexed = Dictionary(
            uniqueKeysWithValues: responses.map { ($0.stop.id, $0) }
        )
        responsesByStopID = indexed
        return Array(indexed.values)
    }

    func save(_ response: ArrivalResponse) {
        var indexed = responsesByStopID
            ?? Dictionary(uniqueKeysWithValues: load().map { ($0.stop.id, $0) })
        indexed[response.stop.id] = response

        do {
            try FileManager.default.createDirectory(
                at: fileURL.deletingLastPathComponent(),
                withIntermediateDirectories: true
            )
            let data = try Self.encode(Array(indexed.values))
            try data.write(to: fileURL, options: .atomic)
            responsesByStopID = indexed
        } catch {
            // Cache writes are best-effort. A network response must remain usable
            // even when the cache directory is unavailable.
        }
    }

    private static func encode(_ responses: [ArrivalResponse]) throws -> Data {
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .custom { date, encoder in
            var container = encoder.singleValueContainer()
            try container.encode(
                date.formatted(
                    Date.ISO8601FormatStyle(includingFractionalSeconds: true)
                )
            )
        }
        return try encoder.encode(responses)
    }

    private static func decode(_ data: Data) throws -> [ArrivalResponse] {
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
                debugDescription: "지원하지 않는 캐시 날짜입니다: \(rawValue)"
            )
        }
        return try decoder.decode([ArrivalResponse].self, from: data)
    }
}
