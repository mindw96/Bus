import SwiftUI

@main
struct UNISTBusApp: App {
    @State private var store: ArrivalsStore

    init() {
        let configuration = AppConfiguration.current()
        _store = State(
            initialValue: ArrivalsStore(
                client: configuration.arrivalClient,
                liveActivity: .live,
                clock: .live,
                cache: .live
            )
        )
    }

    var body: some Scene {
        WindowGroup {
            NavigationStack {
                ArrivalsScreen(store: store)
            }
            .tint(.blue)
        }
    }
}
