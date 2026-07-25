import Foundation
import XCTest
@testable import UNISTBus

final class AppConfigurationTests: XCTestCase {
    func testDebugLaunchArgumentSelectsFixture() {
        let configuration = AppConfiguration.resolve(
            baseURLString: "http://127.0.0.1:8080",
            arguments: ["UNISTBus", "-fixture-arrivals"],
            allowsFixture: true
        )

        XCTAssertEqual(configuration?.arrivalSource, .fixture)
        XCTAssertEqual(configuration?.remoteLiveActivityEnabled, false)
        XCTAssertEqual(configuration?.pushEnvironment, .development)
    }

    func testReleaseStyleResolutionIgnoresFixtureArgument() {
        let baseURL = URL(string: "https://api.example.com")!
        let configuration = AppConfiguration.resolve(
            baseURLString: baseURL.absoluteString,
            arguments: ["UNISTBus", "-fixture-arrivals"],
            allowsFixture: false,
            remoteLiveActivityEnabled: true,
            pushEnvironmentString: "PRODUCTION"
        )

        XCTAssertEqual(configuration?.arrivalSource, .live(baseURL))
        XCTAssertEqual(configuration?.remoteLiveActivityEnabled, true)
        XCTAssertEqual(configuration?.pushEnvironment, .production)
    }

    func testInvalidBaseURLIsRejected() {
        XCTAssertNil(
            AppConfiguration.resolve(
                baseURLString: "not-a-url",
                arguments: [],
                allowsFixture: false
            )
        )
    }

    func testInvalidPushEnvironmentIsRejected() {
        XCTAssertNil(
            AppConfiguration.resolve(
                baseURLString: "https://api.example.com",
                arguments: [],
                allowsFixture: false,
                pushEnvironmentString: "UNKNOWN"
            )
        )
    }
}
