import Foundation
import Network

@MainActor
final class AetherNetworkMonitor: ObservableObject {
    @Published private(set) var isConnected = true
    @Published private(set) var hasReceivedStatus = false

    private let monitor: NWPathMonitor
    private let queue = DispatchQueue(label: "com.canopychat.network-monitor")

    init(monitor: NWPathMonitor = NWPathMonitor()) {
        self.monitor = monitor
        self.monitor.pathUpdateHandler = { [weak self] path in
            Task { @MainActor in
                self?.hasReceivedStatus = true
                self?.isConnected = path.status == .satisfied
            }
        }
        self.monitor.start(queue: queue)
    }

    deinit {
        monitor.cancel()
    }
}
