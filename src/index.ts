import { NitroModules } from 'react-native-nitro-modules'
import type {
  MunimBluetooth as MunimBluetoothSpec,
  AdvertisingDataTypes,
  BLEDevice,
  BackgroundSessionOptions,
  ScanOptions,
  GATTService,
  CharacteristicValue,
  CentralReadyEvent,
} from './specs/munim-bluetooth.nitro'

const MunimBluetooth =
  NitroModules.createHybridObject<MunimBluetoothSpec>('MunimBluetooth')

// ========== Peripheral Features ==========

export function startAdvertising(options: {
  serviceUUIDs: string[]
  localName?: string
  manufacturerData?: string
  advertisingData?: AdvertisingDataTypes
}): void {
  return MunimBluetooth.startAdvertising(options)
}

export function updateAdvertisingData(advertisingData: AdvertisingDataTypes): void {
  return MunimBluetooth.updateAdvertisingData(advertisingData)
}

export function getAdvertisingData(): Promise<AdvertisingDataTypes> {
  return MunimBluetooth.getAdvertisingData()
}

export function stopAdvertising(): void {
  return MunimBluetooth.stopAdvertising()
}

export function setServices(services: GATTService[]): void {
  return MunimBluetooth.setServices(services)
}

export function notifyCharacteristic(
  serviceUUID: string,
  characteristicUUID: string,
  value: string
): Promise<void> {
  return MunimBluetooth.notifyCharacteristic(serviceUUID, characteristicUUID, value)
}

// ========== Central Features ==========

export function isBluetoothEnabled(): Promise<boolean> {
  return MunimBluetooth.isBluetoothEnabled()
}

export function requestBluetoothPermission(): Promise<boolean> {
  return MunimBluetooth.requestBluetoothPermission()
}

export function startScan(options?: ScanOptions): void {
  return MunimBluetooth.startScan(options)
}

export function stopScan(): void {
  return MunimBluetooth.stopScan()
}

export function connect(deviceId: string): Promise<void> {
  return MunimBluetooth.connect(deviceId)
}

export function disconnect(deviceId: string): void {
  return MunimBluetooth.disconnect(deviceId)
}

export function discoverServices(deviceId: string): Promise<GATTService[]> {
  return MunimBluetooth.discoverServices(deviceId)
}

export function readCharacteristic(
  deviceId: string,
  serviceUUID: string,
  characteristicUUID: string
): Promise<CharacteristicValue> {
  return MunimBluetooth.readCharacteristic(deviceId, serviceUUID, characteristicUUID)
}

export function writeCharacteristic(
  deviceId: string,
  serviceUUID: string,
  characteristicUUID: string,
  value: string,
  writeType?: 'write' | 'writeWithoutResponse'
): Promise<void> {
  return MunimBluetooth.writeCharacteristic(deviceId, serviceUUID, characteristicUUID, value, writeType)
}

export function subscribeToCharacteristic(
  deviceId: string,
  serviceUUID: string,
  characteristicUUID: string
): Promise<void> {
  return MunimBluetooth.subscribeToCharacteristic(deviceId, serviceUUID, characteristicUUID)
}

export function unsubscribeFromCharacteristic(
  deviceId: string,
  serviceUUID: string,
  characteristicUUID: string
): Promise<void> {
  return MunimBluetooth.unsubscribeFromCharacteristic(deviceId, serviceUUID, characteristicUUID)
}

export function getConnectedDevices(): Promise<string[]> {
  return MunimBluetooth.getConnectedDevices()
}

export function readRSSI(deviceId: string): Promise<number> {
  return MunimBluetooth.readRSSI(deviceId)
}

export function startBackgroundSession(options: BackgroundSessionOptions): void {
  return MunimBluetooth.startBackgroundSession(options)
}

export function stopBackgroundSession(): void {
  return MunimBluetooth.stopBackgroundSession()
}

/**
 * Signal from JS central that the peripheral is fully ready for communication —
 * connection established, services discovered, characteristics subscribed.
 * Triggers onPeripheralReady on any registered listeners.
 */
export function emitPeripheralReady(deviceId: string): void {
  return MunimBluetooth.emitPeripheralReady(deviceId)
}

// ========== Event Callbacks ==========

/**
 * Peripheral role: fires when a central has subscribed to all expected
 * characteristics and is ready to communicate.
 */
export function onCentralReady(
    callback: (event: CentralReadyEvent) => void
): () => void {
    return MunimBluetooth.onCentralReady(callback)
}

/**
 * Central role: fires when the peripheral is fully ready for communication.
 * Triggered by emitPeripheralReady() after subscriptions are confirmed.
 */
export function onPeripheralReady(callback: (deviceId: string) => void): () => void {
  return MunimBluetooth.onPeripheralReady(callback)
}

export function onDeviceDisconnected(callback: (deviceId: string) => void): () => void {
  return MunimBluetooth.onDeviceDisconnected(callback)
}

export function onCharacteristicValueChanged(
  callback: (deviceId: string, serviceUUID: string, characteristicUUID: string, value: string) => void
): () => void {
  return MunimBluetooth.onCharacteristicValueChanged(callback)
}

export function onPeripheralStateChanged(callback: (state: string) => void): () => void {
  return MunimBluetooth.onPeripheralStateChanged(callback)
}

export function onDeviceFound(callback: (device: BLEDevice) => void): () => void {
  return MunimBluetooth.onDeviceFound(callback)
}

/**
 * @deprecated Use onCentralReady (peripheral role) or onPeripheralReady (central role).
 * This callback has inconsistent timing across platforms and will be removed.
 */
export function onDeviceConnected(callback: (deviceId: string) => void): () => void {
  return MunimBluetooth.onDeviceConnected(callback)
}

// ========== Type Exports ==========

export type {
  AdvertisingDataTypes,
  BLEDevice,
  BackgroundSessionOptions,
  ScanOptions,
  GATTService,
  CharacteristicValue,
}

export default {
  // Peripheral
  startAdvertising,
  stopAdvertising,
  updateAdvertisingData,
  getAdvertisingData,
  setServices,
  notifyCharacteristic,
  // Central
  isBluetoothEnabled,
  requestBluetoothPermission,
  startScan,
  stopScan,
  connect,
  disconnect,
  discoverServices,
  readCharacteristic,
  writeCharacteristic,
  subscribeToCharacteristic,
  unsubscribeFromCharacteristic,
  getConnectedDevices,
  readRSSI,
  startBackgroundSession,
  stopBackgroundSession,
  emitPeripheralReady,
  // Events
  onCentralReady,
  onPeripheralReady,
  onDeviceFound,
  onDeviceConnected,
  onDeviceDisconnected,
  onCharacteristicValueChanged,
  onPeripheralStateChanged,
}
