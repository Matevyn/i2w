import Foundation
import HealthKit
import CoreBluetooth
import Combine

/// Writes health samples arriving from the Galaxy Watch into Apple Health.
///
/// The watch is the BLE peripheral and the iPhone is central, so health data
/// arrives as notifications on a dedicated characteristic rather than as writes.
/// This class owns that subscription and the HealthKit side of the handshake.
final class HealthKitBridge: NSObject, ObservableObject {

    /// Matches HealthGattBridge.HEALTH_CHARACTERISTIC_UUID on the watch.
    static let healthCharacteristicUUID = CBUUID(string: "0f0e0d0c-0b0a-0908-0706-050405030201")

    private let store = HKHealthStore()
    private var peripheral: CBPeripheral?

    @Published var authorizationState: String = "Nepoziadane"
    @Published var lastWrittenSummary: String = ""

    // MARK: - HealthKit types

    private var stepType: HKQuantityType? {
        HKObjectType.quantityType(forIdentifier: .stepCount)
    }

    private var heartRateType: HKQuantityType? {
        HKObjectType.quantityType(forIdentifier: .heartRate)
    }

    private var sleepType: HKCategoryType? {
        HKObjectType.categoryType(forIdentifier: .sleepAnalysis)
    }

    /// Types we write. Steps come from the watch's step sensor; heart rate and
    /// sleep come from the Samsung Health Data API, so all three can arrive.
    private var writeTypes: Set<HKSampleType> {
        var types: Set<HKSampleType> = []
        if let step = stepType { types.insert(step) }
        if let hr = heartRateType { types.insert(hr) }
        if let sleep = sleepType { types.insert(sleep) }
        return types
    }

    /// Types we read back for the app's own use.
    private var readTypes: Set<HKObjectType> {
        var types: Set<HKObjectType> = []
        if let hr = heartRateType { types.insert(hr) }
        if let sleep = sleepType { types.insert(sleep) }
        return types
    }

    // MARK: - Authorization

    /// Requests HealthKit authorization. Must be triggered from the foreground.
    func requestAuthorization() {
        guard HKHealthStore.isHealthDataAvailable() else {
            authorizationState = "HealthKit nedostupny"
            return
        }

        Task { @MainActor in
            do {
                try await store.requestAuthorization(toShare: writeTypes, read: readTypes)
                self.refreshAuthorizationState()
            } catch {
                self.authorizationState = "Chyba povolenia: \(error.localizedDescription)"
            }
        }
    }

    func refreshAuthorizationState() {
        var granted: [String] = []
        var denied: [String] = []

        for type in writeTypes {
            let name = self.humanName(for: type)
            switch store.authorizationStatus(for: type) {
            case .sharingAuthorized: granted.append(name)
            case .sharingDenied: denied.append(name)
            default: break
            }
        }

        if granted.isEmpty && !denied.isEmpty {
            authorizationState = "Zakazane: \(denied.joined(separator: ", "))"
        } else if !granted.isEmpty {
            authorizationState = "Povolene: \(granted.joined(separator: ", "))"
        } else {
            authorizationState = "Nepoziadane"
        }
    }

    private func humanName(for type: HKSampleType) -> String {
        // Compared by raw value: HKSampleType.identifier widens to
        // HKQuantityTypeIdentifier/HKCategoryTypeIdentifier depending on the
        // subclass, so there is no single enum type to compare against here.
        let identifier = type.identifier
        if identifier == HKQuantityTypeIdentifier.stepCount.rawValue { return "kroky" }
        if identifier == HKQuantityTypeIdentifier.heartRate.rawValue { return "tep" }
        if identifier == HKCategoryTypeIdentifier.sleepAnalysis.rawValue { return "spanok" }
        return identifier
    }

    // MARK: - BLE subscription

    /**
     * Subscribes to health notifications once the watch is connected.
     *
     * Deliberately does NOT set `peripheral.delegate`. The BLE manager already
     * owns it; a CBPeripheral has one delegate, and assigning it here would
     * silently stop photo-write callbacks reaching the manager. Instead the
     * manager forwards notification updates into this object.
     */
    func subscribe(peripheral: CBPeripheral?, service: CBService) {
        self.peripheral = peripheral

        guard let characteristic = service.characteristics?
            .first(where: { $0.uuid == Self.healthCharacteristicUUID })
        else {
            authorizationState = "Chyba: zdravotna charakteristika chyba"
            return
        }

        peripheral?.setNotifyValue(true, for: characteristic)
        print("Odomknute notifikacie pre zdravotne data")
    }

    /// Called by the BLE manager, which owns the peripheral delegate.
    func handleHealthNotification(_ characteristic: CBCharacteristic, error: Error?) {
        if let error = error {
            print("Chyba health notifikacie: \(error.localizedDescription)")
            return
        }
        guard let data = characteristic.value, !data.isEmpty else { return }

        // The watch wraps each MTU-sized chunk in an envelope. Decoding the
        // document out of it is what makes multi-notification batches work;
        // decoding the raw bytes would fail for every chunk.
        guard let envelope = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let docString = envelope["doc"] as? String,
              let docData = docString.data(using: .utf8),
              let payload = try? JSONDecoder().decode(HealthWirePayload.self, from: docData)
        else {
            print("Nepodarilo sa rozložit health obálku")
            return
        }

        write(payload)
    }

    // MARK: - HealthKit write

    private func write(_ payload: HealthWirePayload) {
        let quantities: [HKQuantitySample] = payload.samples.compactMap { makeQuantitySample($0) }
        let sleepSamples: [HKCategorySample] = payload.sleep.compactMap { makeSleepSample($0) }

        let all: [HKSample] = quantities + sleepSamples

        guard !all.isEmpty else {
            lastWrittenSummary = "Ziadne zapisy"
            return
        }

        let stepCount = quantities
            .filter { $0.quantityType.identifier == HKQuantityTypeIdentifier.stepCount.rawValue }
            .count
        let heartRateCount = quantities.count - stepCount

        store.save(all) { success, error in
            DispatchQueue.main.async {
                if let error = error {
                    self.lastWrittenSummary = "Chyba zapisu: \(error.localizedDescription)"
                    self.authorizationState = "Zapis zamietnuty"
                } else if success {
                    self.lastWrittenSummary = "Zapisane: \(stepCount) krokov, "
                        + "\(heartRateCount) tep, \(sleepSamples.count) spánok"
                } else {
                    self.lastWrittenSummary = "Nezapisane: \(all.count) zaznamov"
                }
            }
        }
    }

    private func makeQuantitySample(_ sample: HealthWireSample) -> HKQuantitySample? {
        let identifier: HKQuantityTypeIdentifier
        let unit: HKUnit

        switch sample.kind {
        case HealthWireKind.stepCount:
            identifier = .stepCount
            unit = .count()
        case HealthWireKind.heartRate:
            // Apple Health's canonical unit for heart rate is count/min.
            identifier = .heartRate
            unit = HKUnit(from: "count/min")
        default:
            // Unknown kinds are ignored rather than guessed at, so a new metric
            // added on the watch side cannot corrupt existing HealthKit data.
            return nil
        }

        guard let type = HKQuantityType.quantityType(forIdentifier: identifier) else { return nil }

        let quantity = HKQuantity(unit: unit, doubleValue: sample.value)
        return HKQuantitySample(
            type: type,
            quantity: quantity,
            start: Date(timeIntervalSince1970: sample.start / 1000.0),
            end: Date(timeIntervalSince1970: sample.end / 1000.0)
        )
    }

    /// Maps the watch's sleep stage onto HealthKit's sleep-analysis values.
    private func makeSleepSample(_ interval: HealthWireSleep) -> HKCategorySample? {
        guard let type = sleepType else { return nil }

        let value: Int
        switch interval.stage.lowercased() {
        case "awake": value = HKCategoryValueSleepAnalysis.awake.rawValue
        case "inbed": value = HKCategoryValueSleepAnalysis.inBed.rawValue
        default: value = HKCategoryValueSleepAnalysis.asleepUnspecified.rawValue
        }

        return HKCategorySample(
            type: type,
            value: value,
            start: Date(timeIntervalSince1970: interval.start / 1000.0),
            end: Date(timeIntervalSince1970: interval.end / 1000.0)
        )
    }
}

// MARK: - Wire format

enum HealthWireKind {
    static let stepCount = "stepCount"
    static let heartRate = "heartRate"
}

struct HealthWireSample: Codable {
    let kind: String
    let start: Double   // milliseconds since epoch
    let end: Double
    let value: Double
    let unit: String
}

struct HealthWireSleep: Codable {
    let start: Double
    let end: Double
    let stage: String
}

struct HealthWirePayload: Codable {
    let v: Int
    let samples: [HealthWireSample]
    let sleep: [HealthWireSleep]
}