import SwiftUI
import CoreBluetooth

/// Connection state surfaced as a single glanceable value for the status banner.
///
/// The manager speaks in low-level BLE events; the UI needs one word.
enum BridgeConnectionState: Equatable {
    case idle
    case scanning
    case connecting
    case connected
    case transferring
    case unavailable(String)

    var title: String {
        switch self {
        case .idle: return "Čaká sa na hodinky"
        case .scanning: return "Hľadám Galaxy Watch…"
        case .connecting: return "Pripájam sa…"
        case .connected: return "Hodinky pripojené"
        case .transferring: return "Posielam fotku…"
        case .unavailable: return "Bluetooth nedostupný"
        }
    }

    var detail: String? {
        switch self {
        case .idle: return "Spusti hodinky a nechaj ich v dosahu."
        case .scanning: return "Drž hodinky blízko iPhonu."
        case .connecting: return "Pripájam sa k službe Bluetooth…"
        case .connected: return "Môžeš posielať fotky aj čítať zdravie."
        case .transferring: return "Veľká fotka môže chvíľu trvať."
        case .unavailable(let why): return why
        }
    }

    var isBusy: Bool {
        switch self {
        case .scanning, .connecting, .transferring: return true
        default: return false
        }
    }

    var isReadyToSend: Bool { self == .connected }
}

// MARK: - Transfer estimate

/// Rough seconds for a photo over BLE.
///
/// Derived from the two real constraints in the transfer path: the chunk count
/// at the negotiated MTU, and the sender's fixed pacing between chunks. BLE
/// throughput varies a lot in practice, so this is an order-of-magnitude
/// estimate shown as "~" — not a promise.
enum TransferEstimate {

    /// Pacing between chunks in the sender loop.
    static let interChunkDelay: Double = 0.005

    /// Conservative MTU guess: Galaxy watches commonly negotiate 185-247.
    static let assumedChunkSize = 185

    static func seconds(forByteCount bytes: Int) -> Int {
        guard bytes > 0 else { return 0 }
        let chunks = Double(bytes) / Double(assumedChunkSize)
        return Int(ceil(chunks * interChunkDelay))
    }

    static func string(forByteCount bytes: Int) -> String {
        let seconds = seconds(forByteCount: bytes)
        if seconds < 5 { return "menej než 5 s" }
        if seconds < 60 { return "približne \(seconds) s" }
        let minutes = Int((Double(seconds) / 60.0).rounded())
        return "približne \(minutes) min"
    }
}