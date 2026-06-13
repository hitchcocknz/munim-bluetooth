import { type HybridObject } from 'react-native-nitro-modules'

// Service Data Entry
export interface ServiceDataEntry {
  uuid: string
  data: string
}

// BLE Advertising Data Types - Only Platform-Supported Types
export interface AdvertisingDataTypes {
  flags?: number
  incompleteServiceUUIDs16?: string[]
  completeServiceUUIDs16?: string[]
  incompleteServiceUUIDs32?: string[]
  completeServiceUUIDs32?: string[]
  incompleteServiceUUIDs128?: string[]
  completeServiceUUIDs128?: string[]
  shortenedLocalName?: string
  completeLocalName?: string
  txPowerLevel?: number
  serviceSolicitationUUIDs16?: string[]
  serviceSolicitationUUIDs128?: string[]
  serviceData16?: ServiceDataEntry[]
  serviceData32?: ServiceDataEntry[]
  serviceData128?: ServiceDataEntry[]
  appearance?: number
  serviceSolicitationUUIDs32?: string[]
  manufacturerData?: string
}

export interface CentralReadyEvent {
  deviceId:            string
  characteristicUUIDs: string[]
}

// BLE Device information
export interface BLEDevice {
  id: string
  name?: string
  rssi?: number
  advertisingData?: AdvertisingDataTypes
  serviceUUIDs?: string[]
  isConnectable?: boolean
}

// Scan mode type
export type ScanMode = 'lowPower' | 'balanced' | 'lowLatency'

// Scan options
export interface ScanOptions {
  serviceUUIDs?: string[]
  allowDuplicates?: boolean
  scanMode?: ScanMode
}

// GATT Characteristic
export interface GATTCharacteristic {
  uuid: string
  properties: string[]
  value?: string
}

// GATT Service
export interface GATTService {
  uuid: string
  characteristics: GATTCharacteristic[]
}

// Characteristic value
export interface CharacteristicValue {
  value: string
  serviceUUID: string
  characteristicUUID: string
}

// Write type for characteristic writes
export type WriteType = 'write' | 'writeWithoutResponse'

// Advertising options for startAdvertising
export interface AdvertisingOptions {
  serviceUUIDs: string[]
  localName?: string
  manufacturerData?: string
  advertisingData?: AdvertisingDataTypes
}

export interface BackgroundSessionOptions {
  serviceUUIDs: string[]
  localName?: string
  allowDuplicates?: boolean
  scanMode?: ScanMode
  androidNotificationChannelId?: string
  androidNotificationChannelName?: string
  androidNotificationTitle?: string
  androidNotificationText?: string
}

export interface MunimBluetooth
  extends HybridObject<{ ios: 'swift'; android: 'kotlin' }> {

  // ========== Peripheral Features ==========

  startAdvertising(options: AdvertisingOptions): void
  updateAdvertisingData(advertisingData: AdvertisingDataTypes): void
  getAdvertisingData(): Promise<AdvertisingDataTypes>
  stopAdvertising(): void
  setServices(services: GATTService[]): void
  notifyCharacteristic(serviceUUID: string, characteristicUUID: string, value: string): Promise<void>

  // ========== Central Features ==========

  isBluetoothEnabled(): Promise<boolean>
  requestBluetoothPermission(): Promise<boolean>
  startScan(options?: ScanOptions): void
  stopScan(): void
  connect(deviceId: string): Promise<void>
  disconnect(deviceId: string): void
  discoverServices(deviceId: string): Promise<GATTService[]>
  readCharacteristic(deviceId: string, serviceUUID: string, characteristicUUID: string): Promise<CharacteristicValue>
  writeCharacteristic(deviceId: string, serviceUUID: string, characteristicUUID: string, value: string, writeType?: WriteType): Promise<void>
  subscribeToCharacteristic(deviceId: string, serviceUUID: string, characteristicUUID: string): Promise<void>
  unsubscribeFromCharacteristic(deviceId: string, serviceUUID: string, characteristicUUID: string): Promise<void>
  getConnectedDevices(): Promise<string[]>
  readRSSI(deviceId: string): Promise<number>
  startBackgroundSession(options: BackgroundSessionOptions): void
  stopBackgroundSession(): void

  /**
   * Signal that the central has finished subscribing to all characteristics
   * and the peripheral is ready for communication. Call this from JS after
   * monitorIncoming() completes in the central role.
   */
  emitPeripheralReady(deviceId: string): void

  // ========== Event Callbacks ==========

  /**
   * Peripheral role: fires when a central has subscribed to all expected
   * characteristics and is ready to communicate. Guaranteed to fire before
   * any writes arrive. Carries the list of characteristic UUIDs subscribed to.
   * Use this instead of the deprecated onDeviceConnected for peripheral role.
   */
  onCentralReady(callback: (event: CentralReadyEvent) => void): () => void

  /**
   * Central role: fires when emitPeripheralReady() is called from JS after
   * connection, service discovery and characteristic subscriptions are all
   * complete. Use this instead of the deprecated onDeviceConnected for central role.
   */
  onPeripheralReady(callback: (deviceId: string) => void): () => void

  onDeviceDisconnected(callback: (deviceId: string) => void): () => void
  onCharacteristicValueChanged(callback: (deviceId: string, serviceUUID: string, characteristicUUID: string, value: string) => void): () => void
  onPeripheralStateChanged(callback: (state: string) => void): () => void
  onDeviceFound(callback: (device: BLEDevice) => void): () => void

  /**
   * @deprecated Use onCentralReady (peripheral role) or onPeripheralReady (central role) instead.
   * This callback has inconsistent timing across platforms and roles and will be removed.
   */
  onDeviceConnected(callback: (deviceId: string) => void): () => void
}
