//
//  HybridMunimBluetooth.swift
//  munim-bluetooth
//

import Foundation
import CoreBluetooth
import NitroModules

private protocol MunimBluetoothOwner: AnyObject {
    func handlePeripheralManagerDidUpdateState(_ peripheral: CBPeripheralManager)
    func handlePeripheralManagerDidStartAdvertising(_ peripheral: CBPeripheralManager, error: Error?)
    func handlePeripheralManagerDidAddService(_ peripheral: CBPeripheralManager, service: CBService, error: Error?)
    func handlePeripheralManagerWillRestoreState(_ peripheral: CBPeripheralManager, state: [String: Any])
    func handleDidReceiveWriteRequests(_ peripheral: CBPeripheralManager, requests: [CBATTRequest])
    func handleCentralDidSubscribe(_ peripheral: CBPeripheralManager, central: CBCentral, characteristic: CBCharacteristic)
    func handleCentralDidUnsubscribe(_ peripheral: CBPeripheralManager, central: CBCentral, characteristic: CBCharacteristic)
    func handleCentralManagerDidUpdateState(_ central: CBCentralManager)
    func handleCentralManagerWillRestoreState(_ central: CBCentralManager, state: [String: Any])
    func handleCentralManagerDidDiscover(_ central: CBCentralManager, peripheral: CBPeripheral, advertisementData: [String: Any], rssi: NSNumber)
    func handleCentralManagerDidConnect(_ central: CBCentralManager, peripheral: CBPeripheral)
    func handleCentralManagerDidDisconnectPeripheral(_ central: CBCentralManager, peripheral: CBPeripheral, error: Error?)
    func handleCentralManagerDidFailToConnect(_ central: CBCentralManager, peripheral: CBPeripheral, error: Error?)
    func handlePeripheralDidDiscoverServices(_ peripheral: CBPeripheral, error: Error?)
    func handlePeripheralDidDiscoverCharacteristics(_ peripheral: CBPeripheral, service: CBService, error: Error?)
    func handlePeripheralDidUpdateValue(_ peripheral: CBPeripheral, characteristic: CBCharacteristic, error: Error?)
    func handlePeripheralDidUpdateNotificationState(_ peripheral: CBPeripheral, characteristic: CBCharacteristic, error: Error?)
    func handlePeripheralDidWriteValue(_ peripheral: CBPeripheral, characteristic: CBCharacteristic, error: Error?)
    func handlePeripheralDidReadRSSI(_ peripheral: CBPeripheral, rssi: NSNumber, error: Error?)
    func handlePeripheralManagerIsReadyToUpdateSubscribers(_ peripheral: CBPeripheralManager)
}

private let centralRestoreIdentifier   = "com.munimbluetooth.central"
private let peripheralRestoreIdentifier = "com.munimbluetooth.peripheral"

private final class PeripheralManagerDelegateProxy: NSObject, CBPeripheralManagerDelegate {
    weak var owner: (any MunimBluetoothOwner)?
    init(owner: any MunimBluetoothOwner) { self.owner = owner }
    func peripheralManagerDidUpdateState(_ peripheral: CBPeripheralManager) { owner?.handlePeripheralManagerDidUpdateState(peripheral) }
    func peripheralManagerDidStartAdvertising(_ peripheral: CBPeripheralManager, error: Error?) { owner?.handlePeripheralManagerDidStartAdvertising(peripheral, error: error) }
    func peripheralManager(_ peripheral: CBPeripheralManager, didAdd service: CBService, error: Error?) { owner?.handlePeripheralManagerDidAddService(peripheral, service: service, error: error) }
    func peripheralManager(_ peripheral: CBPeripheralManager, willRestoreState dict: [String: Any]) { owner?.handlePeripheralManagerWillRestoreState(peripheral, state: dict) }
    func peripheralManager(_ peripheral: CBPeripheralManager, didReceiveWrite requests: [CBATTRequest]) { owner?.handleDidReceiveWriteRequests(peripheral, requests: requests) }
    func peripheralManager(_ peripheral: CBPeripheralManager, central: CBCentral, didSubscribeTo characteristic: CBCharacteristic) { owner?.handleCentralDidSubscribe(peripheral, central: central, characteristic: characteristic) }
    func peripheralManager(_ peripheral: CBPeripheralManager, central: CBCentral, didUnsubscribeFrom characteristic: CBCharacteristic) { owner?.handleCentralDidUnsubscribe(peripheral, central: central, characteristic: characteristic) }
    func peripheralManagerIsReady(toUpdateSubscribers peripheral: CBPeripheralManager) { owner?.handlePeripheralManagerIsReadyToUpdateSubscribers(peripheral) }
}

private final class CentralManagerDelegateProxy: NSObject, CBCentralManagerDelegate {
    weak var owner: (any MunimBluetoothOwner)?
    init(owner: any MunimBluetoothOwner) { self.owner = owner }
    func centralManagerDidUpdateState(_ central: CBCentralManager) { owner?.handleCentralManagerDidUpdateState(central) }
    func centralManager(_ central: CBCentralManager, willRestoreState dict: [String: Any]) { owner?.handleCentralManagerWillRestoreState(central, state: dict) }
    func centralManager(_ central: CBCentralManager, didDiscover peripheral: CBPeripheral, advertisementData: [String: Any], rssi RSSI: NSNumber) { owner?.handleCentralManagerDidDiscover(central, peripheral: peripheral, advertisementData: advertisementData, rssi: RSSI) }
    func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) { owner?.handleCentralManagerDidConnect(central, peripheral: peripheral) }
    func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral, error: Error?) { owner?.handleCentralManagerDidDisconnectPeripheral(central, peripheral: peripheral, error: error) }
    func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?) { owner?.handleCentralManagerDidFailToConnect(central, peripheral: peripheral, error: error) }
}

private final class PeripheralDelegateProxy: NSObject, CBPeripheralDelegate {
    weak var owner: (any MunimBluetoothOwner)?
    init(owner: any MunimBluetoothOwner) { self.owner = owner }
    func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) { owner?.handlePeripheralDidDiscoverServices(peripheral, error: error) }
    func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) { owner?.handlePeripheralDidDiscoverCharacteristics(peripheral, service: service, error: error) }
    func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) { owner?.handlePeripheralDidUpdateValue(peripheral, characteristic: characteristic, error: error) }
    func peripheral(_ peripheral: CBPeripheral, didUpdateNotificationStateFor characteristic: CBCharacteristic, error: Error?) { owner?.handlePeripheralDidUpdateNotificationState(peripheral, characteristic: characteristic, error: error) }
    func peripheral(_ peripheral: CBPeripheral, didWriteValueFor characteristic: CBCharacteristic, error: Error?) { owner?.handlePeripheralDidWriteValue(peripheral, characteristic: characteristic, error: error) }
    func peripheral(_ peripheral: CBPeripheral, didReadRSSI RSSI: NSNumber, error: Error?) { owner?.handlePeripheralDidReadRSSI(peripheral, rssi: RSSI, error: error) }
}

class HybridMunimBluetooth: HybridMunimBluetoothSpec, MunimBluetoothOwner {

    // ── Peripheral Manager ────────────────────────────────────────────────────
    private var peripheralManager: CBPeripheralManager?
    private var peripheralServices: [CBMutableService] = []
    private var currentAdvertisingData: AdvertisingDataTypes?
    private var onCharacteristicValueChangedCallback: ((_ deviceId: String, _ serviceUUID: String, _ characteristicUUID: String, _ value: String) -> Void)?
    private var onPeripheralStateChangedCallback: ((_ state: String) -> Void)?

    // ── Subscription tracking for onCentralReady ──────────────────────────────
    // expectedSubscriptionCount is set in setServices by counting notify/indicate chars.
    // onCentralReady fires once per central when all subscriptions are confirmed.
    private var expectedSubscriptionCount: Int = 0
    private var centralSubscriptions: [String: Set<String>] = [:]

    // ── Central Manager ───────────────────────────────────────────────────────
    private var centralManager: CBCentralManager?
    private var discoveredPeripherals: [String: CBPeripheral] = [:]
    private var connectedPeripherals: [String: CBPeripheral] = [:]
    private var peripheralCharacteristics: [String: [CBCharacteristic]] = [:]
    private var pendingConnectionPromises: [String: Promise<Void>] = [:]
    private var pendingServiceDiscoveryPromises: [String: Promise<[GATTService]>] = [:]
    private var pendingCharacteristicDiscoveryCounts: [String: Int] = [:]
    private var pendingReadPromises: [String: Promise<CharacteristicValue>] = [:]
    private var pendingRSSIPromises: [String: Promise<Double>] = [:]
    private var scanOptions: ScanOptions?
    private var isScanning = false
    private var isBackgroundSessionActive = false

    private lazy var peripheralManagerDelegateProxy = PeripheralManagerDelegateProxy(owner: self)
    private lazy var centralManagerDelegateProxy    = CentralManagerDelegateProxy(owner: self)
    private lazy var peripheralDelegateProxy        = PeripheralDelegateProxy(owner: self)

    private var connectedCentrals: [String: CBCentral] = [:]
    private var onDeviceFoundCallback: ((_ device: BLEDevice) -> Void)?
    private var pendingWritePromises: [String: Promise<Void>] = [:]
    private var pendingSubscribePromises: [String: Promise<Void>] = [:]
    private var pendingNotifications: [(data: Data, char: CBMutableCharacteristic, promise: Promise<Void>)] = []
    private let notificationQueue = DispatchQueue(label: "com.munimbluetooth.notifications")

    // ── New role-aware connection callbacks ───────────────────────────────────
    /// Peripheral role: fires when a central has subscribed to all expected characteristics.
    // Keep internal storage as two params — bridge to CentralReadyEvent at the boundary
    private var onCentralReadyCallback: ((_ event: CentralReadyEvent) -> Void)?

    /// Central role: fires when emitPeripheralReady() is called from JS.
    private var onPeripheralReadyCallback: ((_ deviceId: String) -> Void)?

    /// @deprecated Use onCentralReadyCallback or onPeripheralReadyCallback
    private var onDeviceConnectedCallback: ((_ deviceId: String) -> Void)?
    private var onDeviceDisconnectedCallback: ((_ deviceId: String) -> Void)?

    override init() {
        super.init()
        #if !targetEnvironment(simulator)
        peripheralManager = CBPeripheralManager(
            delegate: peripheralManagerDelegateProxy, queue: nil,
            options: [CBPeripheralManagerOptionRestoreIdentifierKey: peripheralRestoreIdentifier]
        )
        #endif
        centralManager = CBCentralManager(
            delegate: centralManagerDelegateProxy, queue: nil,
            options: [CBCentralManagerOptionRestoreIdentifierKey: centralRestoreIdentifier]
        )
    }

    // ── Event callback registrations ──────────────────────────────────────────
    func onCentralReady(callback: @escaping (_ event: CentralReadyEvent) -> Void) throws -> () -> Void {
        onCentralReadyCallback = callback
        return { [weak self] in self?.onCentralReadyCallback = nil }
    }

    func onPeripheralReady(callback: @escaping (_ deviceId: String) -> Void) throws -> () -> Void {
        onPeripheralReadyCallback = callback
        return { [weak self] in self?.onPeripheralReadyCallback = nil }
    }

    func onDeviceConnected(callback: @escaping (_ deviceId: String) -> Void) throws -> () -> Void {
        onDeviceConnectedCallback = callback
        return { [weak self] in self?.onDeviceConnectedCallback = nil }
    }

    func onDeviceDisconnected(callback: @escaping (_ deviceId: String) -> Void) throws -> () -> Void {
        onDeviceDisconnectedCallback = callback
        return { [weak self] in self?.onDeviceDisconnectedCallback = nil }
    }

    func onCharacteristicValueChanged(callback: @escaping (_ deviceId: String, _ serviceUUID: String, _ characteristicUUID: String, _ value: String) -> Void) throws -> () -> Void {
        onCharacteristicValueChangedCallback = callback
        return { [weak self] in self?.onCharacteristicValueChangedCallback = nil }
    }

    func onPeripheralStateChanged(callback: @escaping (_ state: String) -> Void) throws -> () -> Void {
        onPeripheralStateChangedCallback = callback
        return { [weak self] in self?.onPeripheralStateChangedCallback = nil }
    }

    func onDeviceFound(callback: @escaping (_ device: BLEDevice) -> Void) throws -> () -> Void {
        onDeviceFoundCallback = callback
        return { [weak self] in self?.onDeviceFoundCallback = nil }
    }

    // ── emitPeripheralReady — called from JS central after monitorIncoming ────
    func emitPeripheralReady(deviceId: String) throws -> Void {
        NSLog("[MunimBluetooth] emitPeripheralReady: firing onPeripheralReadyCallback for %@", deviceId)
        onPeripheralReadyCallback?(deviceId)
    }

    // ── Peripheral features ───────────────────────────────────────────────────

    func startAdvertising(options: AdvertisingOptions) throws {
        guard let peripheralManager = peripheralManager else {
            throw NSError(domain: "MunimBluetooth", code: 1, userInfo: [NSLocalizedDescriptionKey: "Peripheral manager not initialized"])
        }
        guard peripheralManager.state == .poweredOn else {
            throw NSError(domain: "MunimBluetooth", code: 2, userInfo: [NSLocalizedDescriptionKey: "Bluetooth not powered on"])
        }
        if peripheralManager.isAdvertising { peripheralManager.stopAdvertising() }

        var advertisingData: [String: Any] = [:]
        if !options.serviceUUIDs.isEmpty {
            advertisingData[CBAdvertisementDataServiceUUIDsKey] = options.serviceUUIDs.compactMap { CBUUID(string: $0) }
        }
        // if let localName = options.localName, let nameData = localName.data(using: .utf8) {
        //     // 2-byte company ID prefix (0xFFFF = not registered) + name bytes
        //     var manufacturerData = Data([0xFF, 0xFF])
        //     manufacturerData.append(nameData)
        //     advertisingData[CBAdvertisementDataManufacturerDataKey] = manufacturerData
        // }
        if let localName = options.localName {
            advertisingData[CBAdvertisementDataLocalNameKey] = localName
        }
        if let advertisingDataTypes = options.advertisingData, let completeLocalName = advertisingDataTypes.completeLocalName {
            advertisingData[CBAdvertisementDataLocalNameKey] = completeLocalName
        }

        currentAdvertisingData = options.advertisingData
        NSLog("[MunimBluetooth] Starting advertising: %@", advertisingData)
        peripheralManager.startAdvertising(advertisingData)
    }

    func updateAdvertisingData(advertisingData: AdvertisingDataTypes) throws {
        guard let peripheralManager = peripheralManager, peripheralManager.state == .poweredOn else {
            throw NSError(domain: "MunimBluetooth", code: 1, userInfo: [NSLocalizedDescriptionKey: "Bluetooth not powered on"])
        }
        peripheralManager.stopAdvertising()
        var newAdvertisingData: [String: Any] = [:]
        processAdvertisingData(advertisingData, into: &newAdvertisingData)
        currentAdvertisingData = advertisingData
        peripheralManager.startAdvertising(newAdvertisingData)
    }

    func getAdvertisingData() throws -> Promise<AdvertisingDataTypes> {
        let promise = Promise<AdvertisingDataTypes>()
        promise.resolve(withResult: currentAdvertisingData ?? AdvertisingDataTypes())
        return promise
    }

    func stopAdvertising() throws {
        peripheralManager?.stopAdvertising()
        currentAdvertisingData = nil
    }

    // ── GATT server setup — sole entry point ──────────────────────────────────
    func setServices(services: [GATTService]) throws {
        guard let peripheralManager = peripheralManager else {
            throw NSError(domain: "MunimBluetooth", code: 1, userInfo: [NSLocalizedDescriptionKey: "Peripheral manager not initialized"])
        }
        guard peripheralManager.state == .poweredOn else {
            throw NSError(domain: "MunimBluetooth", code: 2, userInfo: [NSLocalizedDescriptionKey: "Bluetooth not powered on"])
        }

        // Reset subscription tracking
        expectedSubscriptionCount = 0
        centralSubscriptions.removeAll()

        // Clean slate before applying new config
        _resetServices()

        NSLog("[MunimBluetooth] Setting up %d services", services.count)

        for service in services {
            let serviceUUID    = CBUUID(string: service.uuid)
            let mutableService = CBMutableService(type: serviceUUID, primary: true)
            var characteristics: [CBMutableCharacteristic] = []

            for characteristic in service.characteristics {
                let charUUID = CBUUID(string: characteristic.uuid)
                var properties: CBCharacteristicProperties = []
                for prop in characteristic.properties {
                    switch prop {
                    case "read":                 properties.insert(.read)
                    case "write":                properties.insert(.write)
                    case "writeWithoutResponse": properties.insert(.writeWithoutResponse)
                    case "notify":               properties.insert(.notify)
                    case "indicate":             properties.insert(.indicate)
                    default: break
                    }
                }

                let hasWriteProperty = properties.contains(.write) || properties.contains(.writeWithoutResponse)
                var value: Data? = nil
                if !hasWriteProperty, let valueString = characteristic.value {
                    value = hexStringToData(valueString)
                }
                if value != nil && !properties.contains(.read) { properties.insert(.read) }

                var permissions: CBAttributePermissions = []
                if properties.contains(.read)  { permissions.insert(.readable) }
                if hasWriteProperty             { permissions.insert(.writeable) }

                let mutableChar = CBMutableCharacteristic(type: charUUID, properties: properties, value: value, permissions: permissions)
                characteristics.append(mutableChar)

                // Count notify/indicate characteristics for onCentralReady deduplication
                if properties.contains(.notify) || properties.contains(.indicate) {
                    expectedSubscriptionCount += 1
                    NSLog("[MunimBluetooth] setServices: notify char %@ (expectedSubscriptionCount=%d)", characteristic.uuid, expectedSubscriptionCount)
                }
            }

            mutableService.characteristics = characteristics
            peripheralServices.append(mutableService)
            peripheralManager.add(mutableService)
            NSLog("[MunimBluetooth] Added service: %@", service.uuid)
        }
        NSLog("[MunimBluetooth] All services added, expecting %d subscription(s)", expectedSubscriptionCount)
    }

    func notifyCharacteristic(serviceUUID: String, characteristicUUID: String, value: String) throws -> Promise<Void> {
        let promise = Promise<Void>()
        guard let peripheralManager = peripheralManager, peripheralManager.state == .poweredOn else {
            promise.reject(withError: NSError(domain: "MunimBluetooth", code: 1, userInfo: [NSLocalizedDescriptionKey: "Bluetooth not powered on"]))
            return promise
        }
        guard let data = value.data(using: .utf8) else {
            promise.reject(withError: NSError(domain: "MunimBluetooth", code: 2, userInfo: [NSLocalizedDescriptionKey: "Failed to encode value"]))
            return promise
        }
        let targetServiceUUID = CBUUID(string: serviceUUID)
        let targetCharUUID    = CBUUID(string: characteristicUUID)
        guard let service = peripheralServices.first(where: { $0.uuid == targetServiceUUID }),
            let char   = (service.characteristics as? [CBMutableCharacteristic])?.first(where: { $0.uuid == targetCharUUID }) else {
            promise.reject(withError: NSError(domain: "MunimBluetooth", code: 3, userInfo: [NSLocalizedDescriptionKey: "Characteristic \(characteristicUUID) not found"]))
            return promise
        }

        notificationQueue.async { [weak self] in
            guard let self = self else { return }
            if peripheralManager.updateValue(data, for: char, onSubscribedCentrals: nil) {
                NSLog("[MunimBluetooth] Notified subscribers on %@", characteristicUUID)
                promise.resolve(withResult: ())
            } else {
                NSLog("[MunimBluetooth] Transmit queue full — queuing for %@", characteristicUUID)
                self.pendingNotifications.append((data: data, char: char, promise: promise))
            }
        }
        return promise
    }

    // ── Central features ──────────────────────────────────────────────────────

    func isBluetoothEnabled() throws -> Promise<Bool> {
        let promise = Promise<Bool>()
        promise.resolve(withResult: centralManager?.state == .poweredOn)
        return promise
    }

    func requestBluetoothPermission() throws -> Promise<Bool> {
        let promise = Promise<Bool>()
        promise.resolve(withResult: true)
        return promise
    }

    func startScan(options: ScanOptions?) throws {
        guard let centralManager = centralManager, centralManager.state == .poweredOn else {
            throw NSError(domain: "MunimBluetooth", code: 1, userInfo: [NSLocalizedDescriptionKey: "Bluetooth not powered on"])
        }
        scanOptions = options
        isScanning  = true
        var scanOpts: [String: Any] = [:]
        if let allowDuplicates = options?.allowDuplicates { scanOpts[CBCentralManagerScanOptionAllowDuplicatesKey] = allowDuplicates }
        let serviceUUIDs = options?.serviceUUIDs?.map { CBUUID(string: $0) }
        centralManager.scanForPeripherals(withServices: serviceUUIDs?.isEmpty == false ? serviceUUIDs : nil, options: scanOpts)
    }

    func stopScan() throws { centralManager?.stopScan(); isScanning = false }

    func connect(deviceId: String) throws -> Promise<Void> {
        let promise = Promise<Void>()
        if connectedPeripherals[deviceId] != nil { promise.resolve(withResult: ()); return promise }
        guard let peripheral = discoveredPeripherals[deviceId] else {
            promise.reject(withError: NSError(domain: "MunimBluetooth", code: 1, userInfo: [NSLocalizedDescriptionKey: "Device not found"]))
            return promise
        }
        pendingConnectionPromises[deviceId] = promise
        peripheral.delegate = peripheralDelegateProxy
        centralManager?.connect(peripheral, options: nil)
        return promise
    }

    func disconnect(deviceId: String) throws {
        guard let peripheral = connectedPeripherals[deviceId] else { return }
        centralManager?.cancelPeripheralConnection(peripheral)
        connectedPeripherals.removeValue(forKey: deviceId)
        peripheralCharacteristics.removeValue(forKey: deviceId)
        rejectPendingOperations(for: deviceId, error: NSError(domain: "MunimBluetooth", code: 1, userInfo: [NSLocalizedDescriptionKey: "Disconnected from \(deviceId)"]))
    }

    func discoverServices(deviceId: String) throws -> Promise<[GATTService]> {
        let promise = Promise<[GATTService]>()
        guard let peripheral = connectedPeripherals[deviceId] else {
            promise.reject(withError: NSError(domain: "MunimBluetooth", code: 1, userInfo: [NSLocalizedDescriptionKey: "Device not connected"]))
            return promise
        }
        NSLog("[MunimBluetooth] discoverServices — forcing rediscovery for %@", deviceId)
        pendingServiceDiscoveryPromises[deviceId] = promise
        peripheral.discoverServices(nil)
        return promise
    }

    func readCharacteristic(deviceId: String, serviceUUID: String, characteristicUUID: String) throws -> Promise<CharacteristicValue> {
        let promise = Promise<CharacteristicValue>()
        guard let peripheral = connectedPeripherals[deviceId] else {
            promise.reject(withError: NSError(domain: "MunimBluetooth", code: 1, userInfo: [NSLocalizedDescriptionKey: "Device not connected"]))
            return promise
        }
        guard let characteristic = findCharacteristic(deviceId: deviceId, serviceUUID: serviceUUID, characteristicUUID: characteristicUUID) else {
            promise.reject(withError: NSError(domain: "MunimBluetooth", code: 1, userInfo: [NSLocalizedDescriptionKey: "Characteristic not found"]))
            return promise
        }
        pendingReadPromises[characteristicKey(deviceId: deviceId, serviceUUID: serviceUUID, characteristicUUID: characteristicUUID)] = promise
        peripheral.readValue(for: characteristic)
        return promise
    }

    func writeCharacteristic(deviceId: String, serviceUUID: String, characteristicUUID: String, value: String, writeType: WriteType?) throws -> Promise<Void> {
        let promise = Promise<Void>()

        // ── Central role ───────────────────────────────────────────────────────
        if let peripheral = connectedPeripherals[deviceId] {
            guard let data = Data(base64Encoded: value) else {
                promise.reject(withError: NSError(domain: "MunimBluetooth", code: 2, userInfo: [NSLocalizedDescriptionKey: "Failed to decode base64"]))
                return promise
            }
            guard let characteristic = findCharacteristic(deviceId: deviceId, serviceUUID: serviceUUID, characteristicUUID: characteristicUUID) else {
                promise.reject(withError: NSError(domain: "MunimBluetooth", code: 3, userInfo: [NSLocalizedDescriptionKey: "Characteristic not found"]))
                return promise
            }
            let cbWriteType: CBCharacteristicWriteType = writeType == .writewithoutresponse ? .withoutResponse : .withResponse
            if cbWriteType == .withResponse {
                pendingWritePromises[characteristicKey(deviceId: deviceId, serviceUUID: serviceUUID, characteristicUUID: characteristicUUID)] = promise
            } else {
                promise.resolve(withResult: ())
            }
            peripheral.writeValue(data, for: characteristic, type: cbWriteType)
            NSLog("[MunimBluetooth] writeCharacteristic (central role) to %@", characteristicUUID)
            return promise
        }

        // ── Peripheral role ────────────────────────────────────────────────────
        NSLog("[MunimBluetooth] writeCharacteristic (peripheral role) delegating to notifyCharacteristic")
        return try notifyCharacteristic(serviceUUID: serviceUUID, characteristicUUID: characteristicUUID, value: value)
    }

    func subscribeToCharacteristic(deviceId: String, serviceUUID: String, characteristicUUID: String) throws -> Promise<Void> {
        let promise = Promise<Void>()
        guard let peripheral = connectedPeripherals[deviceId],
              let characteristic = findCharacteristic(deviceId: deviceId, serviceUUID: serviceUUID, characteristicUUID: characteristicUUID) else {
            promise.reject(withError: NSError(domain: "MunimBluetooth", code: 1, userInfo: [NSLocalizedDescriptionKey: "Device or characteristic not found"]))
            return promise
        }
        pendingSubscribePromises[characteristicKey(deviceId: deviceId, serviceUUID: serviceUUID, characteristicUUID: characteristicUUID)] = promise
        peripheral.setNotifyValue(true, for: characteristic)
        NSLog("[MunimBluetooth] subscribeToCharacteristic: setNotifyValue called for %@", characteristicUUID)
        return promise
    }

    func unsubscribeFromCharacteristic(deviceId: String, serviceUUID: String, characteristicUUID: String) throws -> Promise<Void> {
        let promise = Promise<Void>()
        guard let peripheral = connectedPeripherals[deviceId],
              let characteristic = findCharacteristic(deviceId: deviceId, serviceUUID: serviceUUID, characteristicUUID: characteristicUUID) else {
            promise.reject(withError: NSError(domain: "MunimBluetooth", code: 1, userInfo: [NSLocalizedDescriptionKey: "Device or characteristic not found"]))
            return promise
        }
        pendingSubscribePromises[characteristicKey(deviceId: deviceId, serviceUUID: serviceUUID, characteristicUUID: characteristicUUID)] = promise
        peripheral.setNotifyValue(false, for: characteristic)
        return promise
    }

    func getConnectedDevices() throws -> Promise<[String]> {
        let promise = Promise<[String]>()
        promise.resolve(withResult: Array(connectedCentrals.keys) + Array(connectedPeripherals.keys))
        return promise
    }

    func readRSSI(deviceId: String) throws -> Promise<Double> {
        let promise = Promise<Double>()
        guard let peripheral = connectedPeripherals[deviceId] else {
            promise.reject(withError: NSError(domain: "MunimBluetooth", code: 1, userInfo: [NSLocalizedDescriptionKey: "Device not connected"]))
            return promise
        }
        pendingRSSIPromises[deviceId] = promise
        peripheral.readRSSI()
        return promise
    }

    func startBackgroundSession(options: BackgroundSessionOptions) throws {
        isBackgroundSessionActive = true
        try startAdvertising(options: AdvertisingOptions(serviceUUIDs: options.serviceUUIDs, localName: options.localName, manufacturerData: nil, advertisingData: nil))
        try startScan(options: ScanOptions(serviceUUIDs: options.serviceUUIDs, allowDuplicates: options.allowDuplicates, scanMode: options.scanMode))
    }

    func stopBackgroundSession() throws {
        isBackgroundSessionActive = false
        try stopScan()
        try stopAdvertising()
    }

    // ── Peripheral manager delegate handlers ──────────────────────────────────

    func handleCentralDidSubscribe(_ peripheral: CBPeripheralManager, central: CBCentral, characteristic: CBCharacteristic) {
        let centralId = central.identifier.uuidString
        let charUUID  = characteristic.uuid.uuidString
        connectedCentrals[centralId] = central
        NSLog("[MunimBluetooth] Central subscribed: %@ to %@", centralId, charUUID)

        // Track subscriptions; fire onCentralReady once all expected are confirmed
        if centralSubscriptions[centralId] == nil { centralSubscriptions[centralId] = [] }
        centralSubscriptions[centralId]?.insert(charUUID)
        let count = centralSubscriptions[centralId]?.count ?? 0
        NSLog("[MunimBluetooth] central %@ subscribed to %@ (%d/%d)", centralId, charUUID, count, expectedSubscriptionCount)

        if count >= expectedSubscriptionCount && expectedSubscriptionCount > 0 {
            let uuids = Array(centralSubscriptions[centralId] ?? [])
            NSLog("[MunimBluetooth] onCentralReady: all %d subscriptions confirmed for %@", expectedSubscriptionCount, centralId)
            onCentralReadyCallback?(CentralReadyEvent(deviceId: centralId, characteristicUUIDs: uuids))
        }
    }

    func handleCentralDidUnsubscribe(_ peripheral: CBPeripheralManager, central: CBCentral, characteristic: CBCharacteristic) {
        let centralId = central.identifier.uuidString
        connectedCentrals.removeValue(forKey: centralId)
        centralSubscriptions.removeValue(forKey: centralId)
        NSLog("[MunimBluetooth] Central unsubscribed: %@", centralId)
        onDeviceDisconnectedCallback?(centralId)
        // No service reset — avoids MAC address rotation on iOS
    }

    func handleDidReceiveWriteRequests(_ peripheral: CBPeripheralManager, requests: [CBATTRequest]) {
        for request in requests {
            guard let data = request.value else { continue }
            let centralId = request.central.identifier.uuidString
            let base64String = data.base64EncodedString()  // ← encode as base64, same as Android
            onCharacteristicValueChangedCallback?(
                centralId,
                request.characteristic.service?.uuid.uuidString ?? "",
                request.characteristic.uuid.uuidString,
                base64String
            )
            peripheral.respond(to: request, withResult: .success)
        }
    }

    func handlePeripheralManagerIsReadyToUpdateSubscribers(_ peripheral: CBPeripheralManager) {
        notificationQueue.async { [weak self] in
            guard let self = self else { return }
            NSLog("[MunimBluetooth] Buffer drained — flushing %d pending notifications", self.pendingNotifications.count)
            while !self.pendingNotifications.isEmpty {
                let notification = self.pendingNotifications.first!
                if peripheral.updateValue(notification.data, for: notification.char, onSubscribedCentrals: nil) {
                    self.pendingNotifications.removeFirst()
                    notification.promise.resolve(withResult: ())
                } else {
                    break
                }
            }
        }
    }

    func handlePeripheralManagerDidUpdateState(_ peripheral: CBPeripheralManager) {
        let stateStr: String
        switch peripheral.state {
        case .poweredOn:    stateStr = "poweredOn"
        case .poweredOff:   stateStr = "poweredOff"
        case .resetting:    stateStr = "resetting"
        case .unauthorized: stateStr = "unauthorized"
        case .unsupported:  stateStr = "unsupported"
        default:            stateStr = "unknown"
        }
        NSLog("[MunimBluetooth] Peripheral manager state: %@", stateStr)
        onPeripheralStateChangedCallback?(stateStr)
    }

    func handlePeripheralManagerWillRestoreState(_ peripheral: CBPeripheralManager, state: [String: Any]) {
        if peripheral.isAdvertising { isBackgroundSessionActive = true }
    }

    func handlePeripheralManagerDidStartAdvertising(_ peripheral: CBPeripheralManager, error: Error?) {
        if error != nil { NSLog("[MunimBluetooth] Error starting advertising") }
        else            { NSLog("[MunimBluetooth] Peripheral manager starting advertising successfully") }
    }

    func handlePeripheralManagerDidAddService(_ peripheral: CBPeripheralManager, service: CBService, error: Error?) {
        if error != nil { NSLog("[MunimBluetooth] Peripheral manager failed to add service") }
        else            { NSLog("[MunimBluetooth] Peripheral manager added service successfully") }
    }

    // ── Central manager delegate handlers ─────────────────────────────────────

    func handleCentralManagerDidUpdateState(_ central: CBCentralManager) {
        NSLog("[MunimBluetooth] Central manager updated state: %d", central.state.rawValue)
    }

    func handleCentralManagerWillRestoreState(_ central: CBCentralManager, state: [String: Any]) {
        if let scanServices = state[CBCentralManagerRestoredStateScanServicesKey] as? [CBUUID] {
            scanOptions = ScanOptions(serviceUUIDs: scanServices.map { $0.uuidString }, allowDuplicates: nil, scanMode: nil)
            isScanning = true
            isBackgroundSessionActive = true
        }
    }

    func handleCentralManagerDidDiscover(_ central: CBCentralManager, peripheral: CBPeripheral, advertisementData: [String: Any], rssi RSSI: NSNumber) {
        let deviceId = peripheral.identifier.uuidString
        discoveredPeripherals[deviceId] = peripheral
        onDeviceFoundCallback?(buildBLEDevice(peripheral: peripheral, advertisementData: advertisementData, rssi: RSSI))
        NSLog("[MunimBluetooth] deviceFound: %@", deviceId)
    }

    func handleCentralManagerDidConnect(_ central: CBCentralManager, peripheral: CBPeripheral) {
        let deviceId = peripheral.identifier.uuidString
        connectedPeripherals[deviceId] = peripheral
        peripheral.delegate = peripheralDelegateProxy
        NSLog("[MunimBluetooth] Connected to: %@ — MTU: %d", deviceId, peripheral.maximumWriteValueLength(for: .withResponse))
        // Resolve connection promise only — onPeripheralReady fires after JS
        // calls emitPeripheralReady() once subscriptions are confirmed
        pendingConnectionPromises.removeValue(forKey: deviceId)?.resolve(withResult: ())
    }

    func handleCentralManagerDidDisconnectPeripheral(_ central: CBCentralManager, peripheral: CBPeripheral, error: Error?) {
        let deviceId = peripheral.identifier.uuidString
        connectedPeripherals.removeValue(forKey: deviceId)
        peripheralCharacteristics.removeValue(forKey: deviceId)
        pendingCharacteristicDiscoveryCounts.removeValue(forKey: deviceId)
        rejectPendingOperations(for: deviceId, error: error ?? NSError(domain: "MunimBluetooth", code: 1, userInfo: [NSLocalizedDescriptionKey: "Disconnected from \(deviceId)"]))
        onDeviceDisconnectedCallback?(deviceId)
        NSLog("[MunimBluetooth] Disconnected from: %@", deviceId)
    }

    func handleCentralManagerDidFailToConnect(_ central: CBCentralManager, peripheral: CBPeripheral, error: Error?) {
        let deviceId = peripheral.identifier.uuidString
        let nsError  = error as? NSError
        if nsError?.domain == CBErrorDomain && nsError?.code == 14 {
            NSLog("[MunimBluetooth] Pairing info mismatch for %@ — clearing cached peripheral", deviceId)
            discoveredPeripherals.removeValue(forKey: deviceId)
            connectedPeripherals.removeValue(forKey: deviceId)
            pendingConnectionPromises.removeValue(forKey: deviceId)?.reject(
                withError: error ?? NSError(domain: CBErrorDomain, code: 14, userInfo: [NSLocalizedDescriptionKey: "Peer removed pairing information — retry connection"])
            )
            return
        }
        rejectPendingOperations(for: deviceId, error: error ?? NSError(domain: "MunimBluetooth", code: 1, userInfo: [NSLocalizedDescriptionKey: "Failed to connect to \(deviceId)"]))
        NSLog("[MunimBluetooth]: connectionFailed")
    }

    // ── Peripheral delegate handlers ──────────────────────────────────────────

    func handlePeripheralDidDiscoverServices(_ peripheral: CBPeripheral, error: Error?) {
        let deviceId = peripheral.identifier.uuidString
        if let error = error {
            pendingServiceDiscoveryPromises.removeValue(forKey: deviceId)?.reject(withError: error)
            pendingCharacteristicDiscoveryCounts.removeValue(forKey: deviceId)
            return
        }
        guard let services = peripheral.services, !services.isEmpty else {
            pendingServiceDiscoveryPromises.removeValue(forKey: deviceId)?.resolve(withResult: [])
            return
        }
        // Deduplicate by UUID in case CoreBluetooth returns duplicates
        let uniqueServices = Dictionary(grouping: services, by: { $0.uuid }).compactMap { $0.value.first }
        pendingCharacteristicDiscoveryCounts[deviceId] = uniqueServices.count
        for service in uniqueServices { peripheral.discoverCharacteristics(nil, for: service) }
    }

    func handlePeripheralDidDiscoverCharacteristics(_ peripheral: CBPeripheral, service: CBService, error: Error?) {
        let deviceId = peripheral.identifier.uuidString

        if let error = error {
            NSLog("[MunimBluetooth] Error discovering characteristics for %@: %@", service.uuid.uuidString, error.localizedDescription)
            pendingServiceDiscoveryPromises.removeValue(forKey: deviceId)?.reject(withError: error)
            pendingCharacteristicDiscoveryCounts.removeValue(forKey: deviceId)
            return
        }

        NSLog("[MunimBluetooth] characteristics discovered for %@ service %@", deviceId, service.uuid.uuidString)

        // If count entry is gone the promise already resolved — drop late callbacks
        guard var remaining = pendingCharacteristicDiscoveryCounts[deviceId] else {
            NSLog("[MunimBluetooth] characteristics discovered — ignoring late callback for %@", deviceId)
            return
        }

        remaining -= 1

        if remaining <= 0 {
            // Remove count entry BEFORE resolving to ensure late callbacks are dropped
            pendingCharacteristicDiscoveryCounts.removeValue(forKey: deviceId)

            for svc in peripheral.services ?? [] {
                NSLog("[MunimBluetooth] resolving — service %@ has %d characteristics", svc.uuid.uuidString, svc.characteristics?.count ?? 0)
                for char in svc.characteristics ?? [] { NSLog("[MunimBluetooth]   char: %@", char.uuid.uuidString) }
            }

            pendingServiceDiscoveryPromises.removeValue(forKey: deviceId)?.resolve(
                withResult: buildGATTServices(from: peripheral.services ?? [])
            )
        } else {
            pendingCharacteristicDiscoveryCounts[deviceId] = remaining
        }
    }

    func handlePeripheralDidUpdateNotificationState(_ peripheral: CBPeripheral, characteristic: CBCharacteristic, error: Error?) {
        let deviceId    = peripheral.identifier.uuidString
        let serviceUUID = characteristic.service?.uuid.uuidString ?? ""
        let key = characteristicKey(deviceId: deviceId, serviceUUID: serviceUUID, characteristicUUID: characteristic.uuid.uuidString)
        if let error = error {
            NSLog("[MunimBluetooth] updateNotificationState error: %@", error.localizedDescription)
            pendingSubscribePromises.removeValue(forKey: key)?.reject(withError: error)
        } else {
            NSLog("[MunimBluetooth] updateNotificationState success for %@", characteristic.uuid.uuidString)
            pendingSubscribePromises.removeValue(forKey: key)?.resolve(withResult: ())
        }
    }

    func handlePeripheralDidUpdateValue(_ peripheral: CBPeripheral, characteristic: CBCharacteristic, error: Error?) {
        let deviceId = peripheral.identifier.uuidString
        guard connectedPeripherals[deviceId] != nil else {
            NSLog("[MunimBluetooth] handlePeripheralDidUpdateValue — ignoring loopback for %@", deviceId)
            return
        }

        let serviceUUID = characteristic.service?.uuid.uuidString ?? ""

        if let error = error {
            pendingReadPromises.removeValue(forKey: characteristicKey(deviceId: deviceId, serviceUUID: serviceUUID, characteristicUUID: characteristic.uuid.uuidString))?.reject(withError: error)
            return
        }

        guard let data = characteristic.value else { return }

        // Use base64 consistently across platforms
        let base64String = data.base64EncodedString()

        pendingReadPromises.removeValue(forKey: characteristicKey(deviceId: deviceId, serviceUUID: serviceUUID, characteristicUUID: characteristic.uuid.uuidString))?.resolve(
            withResult: CharacteristicValue(value: base64String, serviceUUID: serviceUUID, characteristicUUID: characteristic.uuid.uuidString)
        )

        onCharacteristicValueChangedCallback?(deviceId, serviceUUID, characteristic.uuid.uuidString, base64String)
        NSLog("[MunimBluetooth] characteristicValueChanged")
    }

    func handlePeripheralDidWriteValue(_ peripheral: CBPeripheral, characteristic: CBCharacteristic, error: Error?) {
        let deviceId    = peripheral.identifier.uuidString
        let serviceUUID = characteristic.service?.uuid.uuidString ?? ""
        let key = characteristicKey(deviceId: deviceId, serviceUUID: serviceUUID, characteristicUUID: characteristic.uuid.uuidString)
        if let error = error {
            pendingWritePromises.removeValue(forKey: key)?.reject(withError: error)
            NSLog("[MunimBluetooth] writeCharacteristic error: %@", error.localizedDescription)
        } else {
            pendingWritePromises.removeValue(forKey: key)?.resolve(withResult: ())
            NSLog("[MunimBluetooth] writeCharacteristic succeeded for %@", characteristic.uuid.uuidString)
        }
    }

    func handlePeripheralDidReadRSSI(_ peripheral: CBPeripheral, rssi RSSI: NSNumber, error: Error?) {
        let deviceId = peripheral.identifier.uuidString
        if let error = error {
            pendingRSSIPromises.removeValue(forKey: deviceId)?.reject(withError: error)
        } else {
            pendingRSSIPromises.removeValue(forKey: deviceId)?.resolve(withResult: RSSI.doubleValue)
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private func _resetServices() {
        guard let peripheralManager = peripheralManager, peripheralManager.state == .poweredOn else { return }
        peripheralManager.removeAllServices()
        peripheralServices.removeAll()
        NSLog("[MunimBluetooth] Services reset")
    }

    private func hexStringToData(_ hex: String) -> Data? {
        var data = Data()
        var hex  = hex
        if hex.count % 2 != 0 { hex = "0" + hex }
        let regex = try! NSRegularExpression(pattern: "[0-9a-f]{1,2}", options: .caseInsensitive)
        regex.enumerateMatches(in: hex, range: NSRange(hex.startIndex..., in: hex)) { match, _, _ in
            let byteStr = (hex as NSString).substring(with: match!.range)
            let num = UInt8(byteStr, radix: 16)!
            data.append(num)
        }
        return data.isEmpty ? nil : data
    }

    private func processAdvertisingData(_ data: AdvertisingDataTypes, into advertisingData: inout [String: Any]) {
        if let completeLocalName = data.completeLocalName { advertisingData[CBAdvertisementDataLocalNameKey] = completeLocalName }
    }

    private func characteristicKey(deviceId: String, serviceUUID: String, characteristicUUID: String) -> String {
        "\(deviceId.lowercased())|\(serviceUUID.lowercased())|\(characteristicUUID.lowercased())"
    }

    private func buildGATTServices(from services: [CBService]) -> [GATTService] {
        services.map { service in
            GATTService(uuid: service.uuid.uuidString, characteristics: (service.characteristics ?? []).map { char in
                GATTCharacteristic(uuid: char.uuid.uuidString, properties: mapProperties(char.properties), value: char.value?.map { String(format: "%02x", $0) }.joined())
            })
        }
    }

    private func mapProperties(_ properties: CBCharacteristicProperties) -> [String] {
        var result: [String] = []
        if properties.contains(.read)                 { result.append("read") }
        if properties.contains(.write)                { result.append("write") }
        if properties.contains(.writeWithoutResponse) { result.append("writeWithoutResponse") }
        if properties.contains(.notify)               { result.append("notify") }
        if properties.contains(.indicate)             { result.append("indicate") }
        return result
    }

    private func findCharacteristic(deviceId: String, serviceUUID: String, characteristicUUID: String) -> CBCharacteristic? {
        guard let peripheral = connectedPeripherals[deviceId], let services = peripheral.services else { return nil }
        let matchingService = services.first { $0.uuid.uuidString.caseInsensitiveCompare(serviceUUID) == .orderedSame }
        return matchingService?.characteristics?.first { $0.uuid.uuidString.caseInsensitiveCompare(characteristicUUID) == .orderedSame }
    }

    private func rejectPendingOperations(for deviceId: String, error: Error) {
        pendingConnectionPromises.removeValue(forKey: deviceId)?.reject(withError: error)
        pendingServiceDiscoveryPromises.removeValue(forKey: deviceId)?.reject(withError: error)
        pendingCharacteristicDiscoveryCounts.removeValue(forKey: deviceId)
        pendingRSSIPromises.removeValue(forKey: deviceId)?.reject(withError: error)
        let prefix = "\(deviceId.lowercased())|"
        for key in pendingReadPromises.keys where key.hasPrefix(prefix) { pendingReadPromises.removeValue(forKey: key)?.reject(withError: error) }
    }

    private func buildBLEDevice(peripheral: CBPeripheral, advertisementData: [String: Any], rssi: NSNumber) -> BLEDevice {
        let serviceUUIDs    = (advertisementData[CBAdvertisementDataServiceUUIDsKey] as? [CBUUID])?.map { $0.uuidString }
        let manufacturerData = (advertisementData[CBAdvertisementDataManufacturerDataKey] as? Data)?.map { String(format: "%02x", $0) }.joined()
        let txPower         = (advertisementData[CBAdvertisementDataTxPowerLevelKey] as? NSNumber)?.doubleValue
        let advData = AdvertisingDataTypes(
            flags: nil, incompleteServiceUUIDs16: nil, completeServiceUUIDs16: serviceUUIDs,
            incompleteServiceUUIDs32: nil, completeServiceUUIDs32: nil, incompleteServiceUUIDs128: nil,
            completeServiceUUIDs128: nil, shortenedLocalName: nil,
            completeLocalName: advertisementData[CBAdvertisementDataLocalNameKey] as? String,
            txPowerLevel: txPower, serviceSolicitationUUIDs16: nil, serviceSolicitationUUIDs128: nil,
            serviceData16: nil, serviceData32: nil, serviceData128: nil, appearance: nil,
            serviceSolicitationUUIDs32: nil, manufacturerData: manufacturerData
        )
        return BLEDevice(
            id: peripheral.identifier.uuidString, name: peripheral.name, rssi: rssi.doubleValue,
            advertisingData: advData, serviceUUIDs: serviceUUIDs,
            isConnectable: advertisementData[CBAdvertisementDataIsConnectable] as? Bool
        )
    }
}
