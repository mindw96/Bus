import SwiftUI

@main
struct UNISTBusApp: App {
    @State private var store: ArrivalsStore

    init() {
        let configuration = AppConfiguration.current()
        let liveActivityClient = LiveActivityClient.live(
            remotePushEnabled: configuration.remoteLiveActivityEnabled,
            pushEnvironment: configuration.pushEnvironment,
            subscriptionClient: configuration.trackingSubscriptionClient
        )
        _store = State(
            initialValue: ArrivalsStore(
                client: configuration.arrivalClient,
                liveActivity: liveActivityClient,
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
            .task {
                await store.resumeLiveActivityTracking()
            }
        }
    }
}
