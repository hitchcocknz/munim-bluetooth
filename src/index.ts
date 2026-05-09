import { NitroModules } from 'react-native-nitro-modules'
import type {
  MunimBluetooth as MunimBluetoothSpec,
  AdvertisingDataTypes,
  BLEDevice,
  BackgroundSessionOptions,
  ScanOptions,
  GATTService,
  CharacteristicValue,
} from './specs/munim-bluetooth.nitro'

const MunimBluetooth =
  NitroModules.createHybridObject<MunimBluetoothSpec>('MunimBluetooth')

// ========== Peripheral Features ==========

/** Start advertising as a Bluetooth peripheral. */
export function startAdvertising(options: {
  serviceUUIDs: string[]
  localName?: string
  manufacturerData?: string
  advertisingData?: AdvertisingDataTypes
}): void {
  return MunimBluetooth.startAdvertising(options)
}

/** Update advertising data while advertising is active. */
export function updateAdvertisingData(advertisingData: AdvertisingDataTypes): void {
  return MunimBluetooth.updateAdvertisingData(advertisingData)
}

/** Get current advertising data. */
export function getAdvertisingData(): Promise<AdvertisingDataTypes> {
  return MunimBluetooth.getAdvertisingData()
}

/** Stop BLE advertising. */
export function stopAdvertising(): void {
  return MunimBluetooth.stopAdvertising()
}

/** Set GATT services and characteristics for the Bluetooth peripheral. */
export function setServices(services: GATTService[]): void {
  return MunimBluetooth.setServices(services)
}

// ========== Central/Manager Features ==========

/** Check if Bluetooth is enabled on the device. */
export function isBluetoothEnabled(): Promise<boolean> {
  return MunimBluetooth.isBluetoothEnabled()
}

/** Request Bluetooth permissions (Android) or check authorization status (iOS). */
export function requestBluetoothPermission(): Promise<boolean> {
  return MunimBluetooth.requestBluetoothPermission()
}

/** Start scanning for BLE devices. */
export function startScan(options?: ScanOptions): void {
  return MunimBluetooth.startScan(options)
}

/** Stop scanning for BLE devices. */
export function stopScan(): void {
  return MunimBluetooth.stopScan()
}

/** Connect to a BLE device. */
export function connect(deviceId: string): Promise<void> {
  return MunimBluetooth.connect(deviceId)
}

/** Disconnect from a BLE device. */
export function disconnect(deviceId: string): void {
  return MunimBluetooth.disconnect(deviceId)
}

/** Discover GATT services for a connected device. */
export function discoverServices(deviceId: string): Promise<GATTService[]> {
  return MunimBluetooth.discoverServices(deviceId)
}

/** Read a characteristic value from a connected device. */
export function readCharacteristic(
  deviceId: string,
  serviceUUID: string,
  characteristicUUID: string
): Promise<CharacteristicValue> {
  return MunimBluetooth.readCharacteristic(deviceId, serviceUUID, characteristicUUID)
}

/** Write a value to a characteristic on a connected device. */
export function writeCharacteristic(
  deviceId: string,
  serviceUUID: string,
  characteristicUUID: string,
  value: string,
  writeType?: 'write' | 'writeWithoutResponse'
): Promise<void> {
  return MunimBluetooth.writeCharacteristic(deviceId, serviceUUID, characteristicUUID, value, writeType)
}

/** Notify subscribed centrals of a characteristic value change (peripheral role). */
export function notifyCharacteristic(
  serviceUUID: string,
  characteristicUUID: string,
  value: string
): Promise<void> {
  return MunimBluetooth.notifyCharacteristic(serviceUUID, characteristicUUID, value)
}

/** Subscribe to notifications/indications from a characteristic. */
export function subscribeToCharacteristic(
  deviceId: string,
  serviceUUID: string,
  characteristicUUID: string
): Promise<void> {
  return MunimBluetooth.subscribeToCharacteristic(deviceId, serviceUUID, characteristicUUID)
}

/** Unsubscribe from notifications/indications from a characteristic. */
export function unsubscribeFromCharacteristic(
  deviceId: string,
  serviceUUID: string,
  characteristicUUID: string
): Promise<void> {
  return MunimBluetooth.unsubscribeFromCharacteristic(deviceId, serviceUUID, characteristicUUID)
}

/** Get list of currently connected devices. */
export function getConnectedDevices(): Promise<string[]> {
  return MunimBluetooth.getConnectedDevices()
}

/** Read RSSI (signal strength) for a connected device. */
export function readRSSI(deviceId: string): Promise<number> {
  return MunimBluetooth.readRSSI(deviceId)
}

/** Start a best-effort background BLE session. */
export function startBackgroundSession(options: BackgroundSessionOptions): void {
  return MunimBluetooth.startBackgroundSession(options)
}

/** Stop the active background BLE session. */
export function stopBackgroundSession(): void {
  return MunimBluetooth.stopBackgroundSession()
}

// ========== Event Callbacks ==========
// Nitro returns the cleanup function directly — just pass it through.

/** Subscribe to device connected events. Returns a cleanup function. */
export function onDeviceConnected(callback: (deviceId: string) => void): () => void {
  return MunimBluetooth.onDeviceConnected(callback)
}

/** Subscribe to device disconnected events. Returns a cleanup function. */
export function onDeviceDisconnected(callback: (deviceId: string) => void): () => void {
  return MunimBluetooth.onDeviceDisconnected(callback)
}

/** Subscribe to characteristic value change events. Returns a cleanup function. */
export function onCharacteristicValueChanged(
  callback: (deviceId: string, serviceUUID: string, characteristicUUID: string, value: string) => void
): () => void {
  return MunimBluetooth.onCharacteristicValueChanged(callback)
}

/** Subscribe to peripheral state change events. Returns a cleanup function. */
export function onPeripheralStateChanged(callback: (state: string) => void): () => void {
  return MunimBluetooth.onPeripheralStateChanged(callback)
}

/** Subscribe to device discovered events during scan. Returns a cleanup function. */
export function onDeviceFound(callback: (device: BLEDevice) => void): () => void {
  return MunimBluetooth.onDeviceFound(callback)
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
  // Events
  onDeviceFound,
  onDeviceConnected,
  onDeviceDisconnected,
  onCharacteristicValueChanged,
  onPeripheralStateChanged,
}