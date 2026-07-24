import Foundation
import XCTest
@testable import UNISTBus

final class ArrivalDecodingTests: XCTestCase {
    func testDecodesRFC3339DatesWithTimezoneOffset() throws {
        let json = """
        {
          "stop": { "id": "40234", "name": "울산과학기술원" },
          "arrivals": [
            {
              "tripId": "fixture-trip-001",
              "routeId": "fixture-route-513",
              "routeName": "513",
              "destination": "덕하 차고지",
              "scheduledArrivalAt": "2026-07-22T09:20:00+09:00",
              "estimatedArrivalAt": "2026-07-22T09:17:00.500+09:00",
              "observedAt": "2026-07-22T09:07:00+09:00",
              "staleAt": "2026-07-22T09:09:00+09:00",
              "status": "APPROACHING"
            }
          ]
        }
        """

        let response = try ArrivalClient.decode(Data(json.utf8))

        let arrival = try XCTUnwrap(response.arrivals.first)
        XCTAssertEqual(response.stop.id, "40234")
        XCTAssertEqual(response.stop.name, "울산과학기술원")
        XCTAssertEqual(arrival.routeName, "513")
        XCTAssertEqual(arrival.status, .approaching)
        XCTAssertEqual(
            arrival.estimatedArrivalAt.timeIntervalSince1970,
            1_784_679_420.5,
            accuracy: 0.001
        )
    }
}
