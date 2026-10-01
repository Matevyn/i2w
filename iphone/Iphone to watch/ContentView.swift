import SwiftUI
import CoreBluetooth
import Combine
import PhotosUI

// 1. BLUETOOTH MANAŽÉR PRE IPHONE
class iPhoneBluetoothManager: NSObject, ObservableObject, CBCentralManagerDelegate, CBPeripheralDelegate {
    var centralManager: CBCentralManager!
    var discoveredPeripheral: CBPeripheral?
    var targetCharacteristic: CBCharacteristic?
    
    @Published var statusMessage = "Inicializácia iPhonu..."
    @Published var isSending = false
    @Published var progress: Float = 0.0

    /// Derived connection state for the status banner. Kept separate from
    /// statusMessage because that string carries multi-line detail the banner
    /// does not want to show.
    @Published var connectionState: BridgeConnectionState = .idle
    
    let SERVICE_UUID = CBUUID(string: "12345678-1234-1234-1234-123456789abc")
    let CHARACTERISTIC_UUID = CBUUID(string: "87654321-4321-4321-4321-cba987654321")

    // Zdravotné dáta: hodinky sú GATT server a notifikujú, iPhone ich zapíše do Apple Health.
    lazy var healthBridge = HealthKitBridge()
    
    override init() {
        super.init()
        centralManager = CBCentralManager(delegate: self, queue: nil)
    }
    
    // Stav Bluetooth na iPhone
    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        if central.state == .poweredOn {
            statusMessage = "Skenujem okolie (Hľadám hodinky)..."
            connectionState = .scanning
            // Skenujeme bez filtra, aby sme ich našli, aj keby maskovali UUID
            centralManager.scanForPeripherals(withServices: nil, options: nil)
        } else {
            connectionState = .unavailable("Zapni Bluetooth v Nastaveniach.")
            statusMessage = "Zapni Bluetooth na iPhone!"
        }
    }
    
    // Našlo sa nejaké Bluetooth zariadenie
    func centralManager(_ central: CBCentralManager, didDiscover peripheral: CBPeripheral, advertisementData: [String : Any], rssi: NSNumber) {
        let name = peripheral.name ?? "Neznáme zariadenie"
        
        // Ak sa názov zhoduje s hodinkami
        if name.contains("Watch") || name.contains("SM-R960") || name.contains("samsung") {
            statusMessage = "Nájdené: \(name)! Pripájam sa..."
            connectionState = .connecting
            discoveredPeripheral = peripheral
            discoveredPeripheral?.delegate = self
            
            centralManager.stopScan()
            centralManager.connect(peripheral, options: nil)
        }
    }
    
    // Úspešne pripojené k hodinkám
    func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        statusMessage = "Pripojené k \(peripheral.name ?? "hodinkám")!\nHľadám službu..."
        connectionState = .connecting
        peripheral.discoverServices([SERVICE_UUID])
    }
    
    // Ak pripojenie zlyhalo
    func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?) {
        statusMessage = "Chyba pripojenia: \(error?.localizedDescription ?? "Neznáma")"
        connectionState = .scanning
    }

    // Hodinky často uspia alebo odídú z dosahu. Bez tohto callbacku by UI
    // klamalo, že je stále pripojené, a tlačidlo by posielalo do prázdna.
    func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral, error: Error?) {
        statusMessage = "Hodinky sa odpojili. Hľadám znova..."
        discoveredPeripheral = nil
        targetCharacteristic = nil
        isSending = false
        connectionState = .scanning

        // Obnoviť skenovanie, inak by aplikácia čakala na večnosť.
        if central.state == .poweredOn {
            central.scanForPeripherals(withServices: nil, options: nil)
        }
    }
    
    // Objavenie služieb
    func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        guard let services = peripheral.services else { return }
        for service in services where service.uuid == SERVICE_UUID {
            statusMessage = "Služba nájdená. Hľadám kanál..."
            peripheral.discoverCharacteristics([CHARACTERISTIC_UUID], for: service)
        }
    }
    
    // Objavenie charakteristiky (Kanálu)
    func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        guard let characteristics = service.characteristics else { return }
        for characteristic in characteristics where characteristic.uuid == CHARACTERISTIC_UUID {
            targetCharacteristic = characteristic
            statusMessage = "Prepojené a pripravené na fotku! 📸"
            connectionState = .connected
        }

        // Zdravotné dáta prichádzajú opačným smerom (hodinky -> iPhone) cez
        // notifikačnú charakteristiku, takže sa oddomykuje tu, kde je služba objavená.
        if let healthChar = characteristics.first(where: {
            $0.uuid == HealthKitBridge.healthCharacteristicUUID
        }) {
            healthBridge.subscribe(peripheral: peripheral, service: service)
            if healthChar.uuid == HealthKitBridge.healthCharacteristicUUID {
                statusMessage += "\nZdravie: čakám na dáta z hodiniek ⌚"
            }
        }
    }

    // MARK: - Peripheral callbacks

    func peripheralIsReady(toSendWriteValue peripheral: CBPeripheral) {}

    /// Health notifications are forwarded to the HealthKit writer, which owns
    /// reassembly. This stays here because the manager owns the delegate.
    func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
        if characteristic.uuid == HealthKitBridge.healthCharacteristicUUID {
            healthBridge.handleHealthNotification(characteristic, error: error)
        }
    }
    
    // FUNKCIA NA ODOSLANIE FOTKY PO KÚSKOCH (CHUNKOCH)
    // Pridali sme parameter compression typu Double
    func sendImage(_ image: UIImage, compression: Double) {
        // Použijeme zvolenú hodnotu compression namiesto natvrdo zadanej 1.0
        guard let imageData = image.jpegData(compressionQuality: CGFloat(compression)) else {
            print("Chyba: Nepodarilo sa získať JPEG dáta")
            return
        }
        
        let totalBytes = imageData.count
        print("ODOSIELAM FOTKU S KOMPRESIOU \(Int(compression * 100))%: \(totalBytes) bajtov")
        
        self.progress = 0.0
        self.isSending = true
        connectionState = .transferring
        
        let startMessage = "START:\(totalBytes)"
        if let startData = startMessage.data(using: .utf8), let char = targetCharacteristic {
            discoveredPeripheral?.writeValue(startData, for: char, type: .withResponse)
        }
        
        DispatchQueue.global(qos: .background).async { [weak self] in
            guard let self = self else { return }
            
            // ATT MTU is negotiated per-connection and is often below 517 on Galaxy
            // Watches. Writing a larger payload than maximumWriteValueLength throws an
            // Objective-C exception that kills the app, so clamp to the negotiated
            // maximum and fall back to the 20-byte ATT default.
            let negotiated = self.discoveredPeripheral?.maximumWriteValueLength(for: .withResponse) ?? 20
            let chunkSize = max(20, min(512, negotiated))
            print("MTU pre max zápis: \(negotiated) -> chunk \(chunkSize)B")
            var offset = 0
            
            while offset < totalBytes {
                let length = min(chunkSize, totalBytes - offset)
                let chunk = imageData.subdata(in: offset..<(offset + length))
                
                DispatchQueue.main.async {
                    if let char = self.targetCharacteristic {
                        self.discoveredPeripheral?.writeValue(chunk, for: char, type: .withResponse)
                    }
                }
                
                offset += length
                
                let currentOffset = offset
                DispatchQueue.main.async {
                    self.progress = Float(currentOffset) / Float(totalBytes)
                }
                
                Thread.sleep(forTimeInterval: 0.005)
            }
            
            DispatchQueue.main.async {
                if let endData = "END".data(using: .utf8), let char = self.targetCharacteristic {
                    self.discoveredPeripheral?.writeValue(endData, for: char, type: .withResponse)
                    print("Odosielanie úspešne dokončené!")
                }
                self.isSending = false
                self.connectionState = .connected
            }
        }
    }
    private func resizeImage(image: UIImage, targetSize: CGSize) -> UIImage {
        let rect = CGRect(x: 0, y: 0, width: targetSize.width, height: targetSize.height)
        UIGraphicsBeginImageContextWithOptions(targetSize, false, 1.0)
        image.draw(in: rect)
        let newImage = UIGraphicsGetImageFromCurrentImageContext()
        UIGraphicsEndImageContext()
        return newImage ?? image
    }
}

// 2. UŽÍVATEĽSKÉ ROZHRANIE IPHONU

struct ContentView: View {
    @StateObject var bleManager = iPhoneBluetoothManager()
    @State private var selectedItem: PhotosPickerItem? = nil
    @State private var selectedImage: UIImage? = nil

    @State private var compressionQuality: Double = 0.8
    @State private var estimatedSizeString: String = "0 KB"
    @State private var estimatedSeconds: String = ""

    private let cardBackground = Color(white: 0.11)

    var body: some View {
        ScrollView {
            VStack(spacing: 18) {

                // Connection state is the first thing worth knowing: every other
                // control is useless until the watch is found, and previously the
                // app gave no sign of whether it was connected at all.
                ConnectionBanner(state: bleManager.connectionState)

                // --- FOTKA ---
                VStack(spacing: 12) {
                    ZStack {
                        RoundedRectangle(cornerRadius: 16)
                            .fill(Color(white: 0.06))
                            .frame(height: 240)

                        if let selectedImage = selectedImage {
                            Image(uiImage: selectedImage)
                                .resizable()
                                .scaledToFit()
                                .frame(maxHeight: 220)
                                .clipShape(RoundedRectangle(cornerRadius: 12))
                                .padding(10)
                        } else {
                            VStack(spacing: 10) {
                                Image(systemName: "photo.on.rectangle.angled")
                                    .font(.system(size: 42))
                                    .foregroundColor(.secondary)
                                Text("Žiadna vybratá fotka")
                                    .foregroundColor(.secondary)
                                    .font(.subheadline)
                            }
                        }
                    }

                    if selectedImage != nil {
                        // Size alone was misleading: a 2 MB JPEG takes roughly a
                        // minute over BLE, so the time is what actually predicts
                        // whether to wait.
                        HStack(spacing: 6) {
                            Image(systemName: "arrow.down.circle")
                                .font(.caption)
                            Text("\(estimatedSizeString) · \(estimatedSeconds)")
                                .font(.subheadline.weight(.semibold))
                        }
                        .foregroundColor(.green)
                    }
                }
                .padding(.horizontal)

                // --- KOMPRESIA ---
                VStack(spacing: 10) {
                    HStack {
                        Label("Kvalita fotky", systemImage: "dial.medium")
                            .font(.subheadline)
                            .foregroundColor(.white)
                        Spacer()
                        Text("\(Int(compressionQuality * 100)) %")
                            .font(.subheadline.bold())
                            .foregroundColor(.orange)
                    }

                    Slider(value: $compressionQuality, in: 0.1...1.0, step: 0.05)
                        .tint(.orange)
                        .disabled(bleManager.isSending)
                        .onChange(of: compressionQuality) { _, _ in updateEstimate() }

                    // Guiding the slider beats leaving the user to discover the
                    // tradeoff: low quality is fast but blocky, high is slow.
                    Text(compressionAdvice)
                        .font(.caption)
                        .foregroundColor(.secondary)
                }
                .padding(14)
                .background(cardBackground, in: RoundedRectangle(cornerRadius: 14))

                // --- ZDRAVIE ---
                VStack(alignment: .leading, spacing: 10) {
                    Label("Zdravie z hodiniek", systemImage: "heart.text.square.fill")
                        .font(.headline)
                        .foregroundColor(.pink)

                    Text("Kroky, tep a spánok prídu cez Bluetooth a zapíšu sa do Apple Health.")
                        .font(.caption)
                        .foregroundColor(.secondary)

                    HealthStatusRow(
                        state: bleManager.healthBridge.authorizationState,
                        summary: bleManager.healthBridge.lastWrittenSummary,
                        isWatchConnected: bleManager.connectionState.isReadyToSend
                    )

                    HStack(spacing: 10) {
                        Button {
                            bleManager.healthBridge.requestAuthorization()
                        } label: {
                            Label("Povoliť Apple Health", systemImage: "heart.fill")
                                .font(.subheadline.weight(.semibold))
                                .frame(maxWidth: .infinity)
                                .padding(.vertical, 10)
                                .background(Color.pink, in: RoundedRectangle(cornerRadius: 10))
                                .foregroundColor(.white)
                        }

                        Button {
                            bleManager.healthBridge.refreshAuthorizationState()
                        } label: {
                            Image(systemName: "arrow.clockwise")
                                .padding(10)
                                .background(Color(white: 0.22), in: RoundedRectangle(cornerRadius: 10))
                                .foregroundColor(.white)
                        }
                        .accessibilityLabel("Obnoviť stav povolenia")
                    }
                }
                .padding(14)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(cardBackground, in: RoundedRectangle(cornerRadius: 14))

                // --- AKCIE ---
                VStack(spacing: 12) {
                    PhotosPicker(selection: $selectedItem, matching: .images, photoLibrary: .shared()) {
                        Label(
                            selectedImage == nil ? "Vybrať fotku" : "Zmeniť fotku",
                            systemImage: "photo.badge.plus"
                        )
                        .font(.headline)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 14)
                        .background(Color.blue, in: RoundedRectangle(cornerRadius: 12))
                        .foregroundColor(.white)
                    }
                    .disabled(bleManager.isSending)

                    if let image = selectedImage {
                        Button {
                            bleManager.sendImage(image, compression: compressionQuality)
                        } label: {
                            sendButtonLabel
                        }
                        // Disabled with an explanation rather than silently: the
                        // old button stayed tappable with no watch connected and
                        // appeared to do nothing.
                        .disabled(!bleManager.connectionState.isReadyToSend || bleManager.isSending)
                    }
                }
            }
            .padding(.vertical, 16)
        }
        .background(Color.black.ignoresSafeArea())
        .onChange(of: selectedItem) { _, newItem in
            Task {
                if let data = try? await newItem?.loadTransferable(type: Data.self),
                   let image = UIImage(data: data) {
                    await MainActor.run {
                        self.selectedImage = image
                        updateEstimate()
                    }
                }
            }
        }
    }

    @ViewBuilder
    private var sendButtonLabel: some View {
        if bleManager.isSending {
            VStack(spacing: 6) {
                ProgressView(value: bleManager.progress)
                    .progressViewStyle(.linear)
                    .tint(.white)
                Text("Odosielam… \(Int(bleManager.progress * 100)) %")
                    .font(.subheadline.weight(.semibold))
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 14)
            .background(Color.gray, in: RoundedRectangle(cornerRadius: 12))
            .foregroundColor(.white)
        } else {
            let isReady = bleManager.connectionState.isReadyToSend
            Label(
                isReady ? "Poslať do hodiniek" : "Najprv pripoj hodinky",
                systemImage: isReady ? "paperplane.fill" : "antenna.radiowaves.left.and.right.slash"
            )
            .font(.headline)
            .frame(maxWidth: .infinity)
            .padding(.vertical, 14)
            .background(
                isReady ? Color.green : Color(white: 0.25),
                in: RoundedRectangle(cornerRadius: 12)
            )
            .foregroundColor(.white)
        }
    }

    private var compressionAdvice: String {
        switch compressionQuality {
        case ..<0.3: return "Najrýchlejšie, ale fotka môže vyzerať kostrbato."
        case ..<0.7: return "Dobrá rovnováha medzi kvalitou a rýchlosťou."
        default: return "Najlepšia kvalita, ale posielanie môže trvať dlho."
        }
    }

    private func updateEstimate() {
        guard let image = selectedImage,
              let data = image.jpegData(compressionQuality: CGFloat(compressionQuality)) else {
            estimatedSizeString = "0 KB"
            estimatedSeconds = ""
            return
        }

        let bytes = data.count
        if bytes >= 1024 * 1024 {
            estimatedSizeString = String(format: "%.2f MB", Double(bytes) / (1024.0 * 1024.0))
        } else {
            estimatedSizeString = String(format: "%.0f KB", Double(bytes) / 1024.0)
        }
        estimatedSeconds = TransferEstimate.string(forByteCount: bytes)
    }
}

/// Connection status as a single glanceable banner.
private struct ConnectionBanner: View {
    let state: BridgeConnectionState

    private var tint: Color {
        switch state {
        case .connected: return .green
        case .transferring: return .blue
        case .scanning, .connecting: return .orange
        case .idle: return .secondary
        case .unavailable: return .red
        }
    }

    private var symbol: String {
        switch state {
        case .connected: return "checkmark.circle.fill"
        case .transferring: return "arrow.up.circle.fill"
        case .scanning: return "dot.radiowaves.left.and.right"
        case .connecting: return "link"
        case .idle: return "moon.zzz"
        case .unavailable: return "exclamationmark.triangle.fill"
        }
    }

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: symbol)
                .font(.title3)
                .foregroundColor(tint)

            VStack(alignment: .leading, spacing: 2) {
                Text(state.title)
                    .font(.subheadline.weight(.semibold))
                    .foregroundColor(.white)
                if let detail = state.detail {
                    Text(detail)
                        .font(.caption)
                        .foregroundColor(.secondary)
                }
            }

            Spacer(minLength: 0)

            if state.isBusy {
                ProgressView()
                    .progressViewStyle(.circular)
                    .tint(tint)
            }
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(tint.opacity(0.15), in: RoundedRectangle(cornerRadius: 14))
        .padding(.horizontal)
    }
}

/// Health permission status, with guidance about why it may be unavailable.
private struct HealthStatusRow: View {
    let state: String
    let summary: String
    let isWatchConnected: Bool

    private var isAuthorized: Bool {
        state.hasPrefix("Povolene") || state.hasPrefix("Povolené")
    }

    private var tint: Color {
        if state.contains("Zakaz") || state.contains("zamietnut") { return .red }
        if isAuthorized { return .green }
        return .orange
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Label(state, systemImage: isAuthorized ? "checkmark.seal.fill" : "exclamationmark.circle")
                .font(.subheadline)
                .foregroundColor(tint)

            if !isAuthorized && !isWatchConnected {
                Text("Pre health sync treba mať hodinky pripojené.")
                    .font(.caption)
                    .foregroundColor(.secondary)
            }

            if !summary.isEmpty {
                Text(summary)
                    .font(.caption)
                    .foregroundColor(.green)
            }
        }
    }
}