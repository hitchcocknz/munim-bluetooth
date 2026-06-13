package com.munimbluetooth

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanRecord
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import com.margelo.nitro.NitroModules
import com.margelo.nitro.core.Promise
import com.margelo.nitro.munimbluetooth.AdvertisingDataTypes
import com.margelo.nitro.munimbluetooth.AdvertisingOptions
import com.margelo.nitro.munimbluetooth.BackgroundSessionOptions
import com.margelo.nitro.munimbluetooth.CharacteristicValue
import com.margelo.nitro.munimbluetooth.GATTCharacteristic
import com.margelo.nitro.munimbluetooth.GATTService
import com.margelo.nitro.munimbluetooth.HybridMunimBluetoothSpec
import com.margelo.nitro.munimbluetooth.ScanMode
import com.margelo.nitro.munimbluetooth.ScanOptions
import com.margelo.nitro.munimbluetooth.ServiceDataEntry
import com.margelo.nitro.munimbluetooth.WriteType
import com.margelo.nitro.munimbluetooth.BLEDevice
import com.margelo.nitro.munimbluetooth.CentralReadyEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID

class HybridMunimBluetooth : HybridMunimBluetoothSpec() {
    private val bluetoothScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var advertiser: BluetoothLeAdvertiser? = null
    private var advertiseCallback: AdvertiseCallback? = null
    private var gattServer: BluetoothGattServer? = null
    private var gattServerReady = false
    private var advertiseJob: Job? = null
    private var currentAdvertisingData: AdvertisingDataTypes? = null
    private var currentServiceUUIDs: Array<String> = emptyArray()
    private var currentLocalName: String? = null
    private var currentManufacturerData: String? = null
    private var previousAdapterName: String? = null
    private var bluetoothManager: BluetoothManager? = null
    private var bluetoothAdapter: BluetoothAdapter? = null

    private var bluetoothLeScanner: BluetoothLeScanner? = null
    private var scanCallback: ScanCallback? = null
    private var isScanning = false
    private val discoveredDevices = mutableMapOf<String, BluetoothDevice>()
    private val connectedDevices = mutableMapOf<String, BluetoothGatt>()
    private val pendingConnections = mutableMapOf<String, Promise<Unit>>()
    private val pendingServiceDiscoveries = mutableMapOf<String, Promise<Array<GATTService>>>()
    private val pendingReads = mutableMapOf<String, Promise<CharacteristicValue>>()
    private val pendingWrites = mutableMapOf<String, Promise<Unit>>()
    private val pendingRssiReads = mutableMapOf<String, Promise<Double>>()
    private val lastCharacteristicValues = mutableMapOf<String, CharacteristicValue>()
    private val lastRssiValues = mutableMapOf<String, Double>()
    private var nextPermissionRequestCode = BLUETOOTH_PERMISSION_REQUEST_CODE

    // ── Subscription tracking for onCentralReady ──────────────────────────────
    // Counts notify/indicate characteristics registered in setServices.
    // onCentralReady fires once per central when this count is reached.
    private var expectedSubscriptionCount: Int = 0
    private val centralSubscriptions = mutableMapOf<String, MutableSet<String>>()

    // ── Callbacks ──────────────────────────────────────────────────────────────
    /** @deprecated Use onCentralReadyCallback or onPeripheralReadyCallback */
    private var onDeviceConnectedCallback: ((deviceId: String) -> Unit)? = null
    private var onDeviceDisconnectedCallback: ((deviceId: String) -> Unit)? = null
    private var onCharacteristicValueChangedCallback: ((deviceId: String, serviceUUID: String, characteristicUUID: String, value: String) -> Unit)? = null
    private var onPeripheralStateChangedCallback: ((state: String) -> Unit)? = null
    private var onDeviceFoundCallback: ((device: BLEDevice) -> Unit)? = null
    private var onCentralReadyCallback: ((event: CentralReadyEvent) -> Unit)? = null
    private var onPeripheralReadyCallback: ((deviceId: String) -> Unit)? = null

    private val pendingDescriptorWrites = mutableMapOf<String, Promise<Unit>>()
    private val pendingNotifications = ArrayDeque<Triple<BluetoothGattCharacteristic, ByteArray, Promise<Unit>>>()
    private var isNotifying = false

    private fun getBluetoothManager(): BluetoothManager? {
        val context = NitroModules.applicationContext ?: return null
        return context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    }

    private fun ensureBluetoothManager() {
        if (bluetoothManager == null) {
            bluetoothManager = getBluetoothManager()
            bluetoothAdapter = bluetoothManager?.adapter
        }
    }

    private fun hasRequiredBluetoothPermissions(): Boolean {
        val context = NitroModules.applicationContext ?: return false
        return BluetoothPermissionUtils.hasRequiredPermissions(context)
    }

    private fun ensureBluetoothPermissions(operationName: String): Boolean {
        val context = NitroModules.applicationContext ?: run {
            Log.w(TAG, "Unable to $operationName: React context unavailable")
            return false
        }
        val missingPermissions = BluetoothPermissionUtils.missingPermissions(context)
        if (missingPermissions.isNotEmpty()) {
            Log.w(TAG, "Unable to $operationName: missing permissions (${missingPermissions.joinToString()})")
            return false
        }
        return true
    }

    override fun startAdvertising(options: AdvertisingOptions) {
        if (!ensureBluetoothPermissions("start advertising")) return
        ensureBluetoothManager()
        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) { Log.e(TAG, "Bluetooth not available"); return }
        if (options.serviceUUIDs.isEmpty()) { Log.e(TAG, "No service UUIDs for advertising"); return }
        if (!gattServerReady) Log.w(TAG, "startAdvertising: GATT server not ready — call setServices() first")

        currentServiceUUIDs = options.serviceUUIDs
        currentLocalName = options.localName
        currentManufacturerData = options.manufacturerData
        currentAdvertisingData = normalizeAdvertisingData(options.advertisingData, options.localName, options.manufacturerData)

        if (!currentLocalName.isNullOrBlank() && previousAdapterName == null) {
            previousAdapterName = try { adapter.name } catch (e: SecurityException) { null }
        }
        if (!currentLocalName.isNullOrBlank()) {
            try { adapter.name = currentLocalName } catch (e: SecurityException) {
                Log.w(TAG, "Unable to apply custom localName", e)
            }
        }
        centralSubscriptions.clear()
        restartAdvertising(delayMs = 600L)
    }

    // override fun startAdvertising(options: AdvertisingOptions) {
    //     if (!ensureBluetoothPermissions("start advertising")) return
    //     ensureBluetoothManager()
    //     val adapter = bluetoothAdapter
    //     if (adapter == null || !adapter.isEnabled) { Log.e(TAG, "Bluetooth not available"); return }
    //     if (options.serviceUUIDs.isEmpty()) { Log.e(TAG, "No service UUIDs for advertising"); return }
    //     if (!gattServerReady) Log.w(TAG, "startAdvertising: GATT server not ready — call setServices() first")

    //     currentServiceUUIDs = options.serviceUUIDs
    //     currentLocalName = options.localName
    //     currentManufacturerData = options.manufacturerData

    //     // Encode localName into manufacturer data (company ID 0xFFFF + UTF-8 name bytes)
    //     // so iOS centrals can read the name from the advertisement packet regardless
    //     // of what the adapter name is set to.
    //     val manufacturerDataHex = options.localName
    //         ?.takeIf { it.isNotBlank() }
    //         ?.let { name ->
    //             val truncated = if (name.length > 10) name.take(10) + "..." else name
    //             val nameBytes = truncated.toByteArray(Charsets.UTF_8)
    //             byteArrayOf(0xFF.toByte(), 0xFF.toByte(), *nameBytes).toHexString()
    //         }
    //         ?: options.manufacturerData

    //     currentAdvertisingData = normalizeAdvertisingData(
    //         options.advertisingData,
    //         options.localName,
    //         manufacturerDataHex,   // ← use encoded value instead of raw manufacturerData
    //     )

    //     if (!currentLocalName.isNullOrBlank() && previousAdapterName == null) {
    //         previousAdapterName = try { adapter.name } catch (e: SecurityException) { null }
    //     }
    //     if (!currentLocalName.isNullOrBlank()) {
    //         try { adapter.name = currentLocalName } catch (e: SecurityException) {
    //             Log.w(TAG, "Unable to apply custom localName", e)
    //         }
    //     }
    //     centralSubscriptions.clear()
    //     restartAdvertising(delayMs = 600L)
    // }

    override fun updateAdvertisingData(advertisingData: AdvertisingDataTypes) {
        currentAdvertisingData = normalizeAdvertisingData(advertisingData, currentLocalName, currentManufacturerData)
        if (currentServiceUUIDs.isNotEmpty()) restartAdvertising(delayMs = 100L)
    }

    override fun getAdvertisingData(): Promise<AdvertisingDataTypes> =
        Promise.resolved(currentAdvertisingData ?: emptyAdvertisingData())

    override fun stopAdvertising() {
        advertiseJob?.cancel()
        advertiseCallback?.let { advertiser?.stopAdvertising(it) }
        advertiseCallback = null
        advertiser = null
        currentAdvertisingData = null
        currentServiceUUIDs = emptyArray()
        currentLocalName = null
        currentManufacturerData = null
        restoreAdapterName()
    }

    // ── GATT server setup — sole entry point ──────────────────────────────────
    override fun setServices(services: Array<GATTService>) {
        if (!ensureBluetoothPermissions("set GATT services")) return

        // Clear stale bonds to prevent iOS CBError code 14
        try {
            val bondedDevices = bluetoothAdapter?.bondedDevices ?: emptySet()
            for (device in bondedDevices) {
                device.javaClass.getMethod("removeBond").invoke(device)
                Log.d(TAG, "setServices: removed stale bond for ${device.address}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "setServices: could not remove bonds", e)
        }

        ensureBluetoothManager()
        gattServerReady = false
        expectedSubscriptionCount = 0
        centralSubscriptions.clear()

        val manager = bluetoothManager ?: return
        val context = NitroModules.applicationContext ?: return

        gattServer?.close()
        gattServer = manager.openGattServer(context, buildGattServerCallback())
        gattServer?.clearServices()

        for (serviceData in services) {
            val service = BluetoothGattService(UUID.fromString(serviceData.uuid), BluetoothGattService.SERVICE_TYPE_PRIMARY)

            for (characteristicData in serviceData.characteristics) {
                val properties = propertiesFromArray(characteristicData.properties)
                val characteristic = BluetoothGattCharacteristic(
                    UUID.fromString(characteristicData.uuid),
                    properties,
                    BluetoothGattCharacteristic.PERMISSION_READ or BluetoothGattCharacteristic.PERMISSION_WRITE
                )

                // Add CCCD descriptor to notify/indicate characteristics.
                // iOS requires this for setNotifyValue; also tracks subscription
                // count for onCentralReady deduplication.
                if (properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0 ||
                    properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) {
                    val cccd = BluetoothGattDescriptor(
                        CLIENT_CHARACTERISTIC_CONFIG_UUID,
                        BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
                    )
                    characteristic.addDescriptor(cccd)
                    expectedSubscriptionCount++
                    Log.d(TAG, "setServices: added CCCD to ${characteristicData.uuid} (expectedSubscriptionCount=$expectedSubscriptionCount)")
                }

                characteristicData.value?.let { characteristic.value = hexStringToByteArray(it) ?: it.toByteArray() }
                service.addCharacteristic(characteristic)
            }
            gattServer?.addService(service)
        }

        gattServerReady = true
        Log.d(TAG, "setServices: GATT server ready with ${services.size} service(s), expecting $expectedSubscriptionCount subscription(s)")
    }

    // ── emitPeripheralReady — called from JS central after monitorIncoming ────
    override fun emitPeripheralReady(deviceId: String) {
        Log.d(TAG, "emitPeripheralReady: firing onPeripheralReadyCallback for $deviceId")
        onPeripheralReadyCallback?.invoke(deviceId)
    }

    override fun isBluetoothEnabled(): Promise<Boolean> {
        if (!hasRequiredBluetoothPermissions()) return Promise.resolved(false)
        ensureBluetoothManager()
        return Promise.resolved(bluetoothAdapter?.isEnabled == true)
    }

    override fun requestBluetoothPermission(): Promise<Boolean> {
        val context = NitroModules.applicationContext ?: return Promise.resolved(false)
        return Promise.resolved(BluetoothPermissionUtils.missingPermissions(context).isEmpty())
    }

    override fun startScan(options: ScanOptions?) {
        if (!ensureBluetoothPermissions("start scanning")) return
        ensureBluetoothManager()
        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) { Log.e(TAG, "Bluetooth not available"); return }
        if (isScanning) return

        val scanner = adapter.bluetoothLeScanner ?: run { Log.e(TAG, "BLE scanner not available"); return }
        isScanning = true
        discoveredDevices.clear()
        bluetoothLeScanner = scanner

        val scanFilters = options?.serviceUUIDs?.takeIf { it.isNotEmpty() }
            ?.map { ScanFilter.Builder().setServiceUuid(ParcelUuid.fromString(it)).build() }
            ?: emptyList()

        val scanMode = when (options?.scanMode) {
            ScanMode.LOWPOWER -> ScanSettings.SCAN_MODE_LOW_POWER
            ScanMode.LOWLATENCY -> ScanSettings.SCAN_MODE_LOW_LATENCY
            else -> ScanSettings.SCAN_MODE_BALANCED
        }

        scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                discoveredDevices[result.device.address] = result.device
                onDeviceFoundCallback?.invoke(buildBLEDevice(result))
            }
            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach { discoveredDevices[it.device.address] = it.device; onDeviceFoundCallback?.invoke(buildBLEDevice(it)) }
            }
            override fun onScanFailed(errorCode: Int) { Log.e(TAG, "Scan failed: $errorCode"); isScanning = false }
        }

        scanner.startScan(scanFilters, ScanSettings.Builder().setScanMode(scanMode).build(), scanCallback)
    }

    override fun stopScan() {
        if (!isScanning) return
        scanCallback?.let { bluetoothLeScanner?.stopScan(it) }
        bluetoothLeScanner = null; scanCallback = null; isScanning = false
    }

    override fun connect(deviceId: String): Promise<Unit> {
        if (!ensureBluetoothPermissions("connect")) return Promise.rejected(IllegalStateException("Permissions not granted"))
        ensureBluetoothManager()

        connectedDevices.remove(deviceId)?.let { stale ->
            stale.disconnect()
            bluetoothScope.launch { delay(500); stale.close() }
        }

        val context = NitroModules.applicationContext ?: return Promise.rejected(IllegalStateException("Context unavailable"))
        val adapter = bluetoothAdapter ?: return Promise.rejected(IllegalStateException("Adapter unavailable"))
        val device = discoveredDevices[deviceId] ?: run {
            try { adapter.getRemoteDevice(deviceId) } catch (_: IllegalArgumentException) { null }
        } ?: return Promise.rejected(IllegalArgumentException("Device not found: $deviceId"))

        val promise = Promise<Unit>()
        pendingConnections[deviceId] = promise
        bluetoothScope.launch {
            delay(300)
            val gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                device.connectGatt(context, false, createGattCallback(deviceId), BluetoothDevice.TRANSPORT_LE)
            else
                device.connectGatt(context, false, createGattCallback(deviceId))
            connectedDevices[deviceId] = gatt
        }
        return promise
    }

    override fun disconnect(deviceId: String) {
        pendingConnections.remove(deviceId)
        pendingServiceDiscoveries.remove(deviceId)
        pendingRssiReads.remove(deviceId)
        val gatt = connectedDevices.remove(deviceId)
        gatt?.disconnect()
        bluetoothScope.launch { delay(500); gatt?.close() }
        rejectPendingOperationsForDevice(deviceId, IllegalStateException("Disconnected from $deviceId"))
        onDeviceDisconnectedCallback?.invoke(deviceId)
    }

    override fun discoverServices(deviceId: String): Promise<Array<GATTService>> {
        val gatt = connectedDevices[deviceId] ?: return Promise.rejected(IllegalStateException("Not connected: $deviceId"))
        val promise = Promise<Array<GATTService>>()
        pendingServiceDiscoveries[deviceId] = promise
        if (!gatt.discoverServices()) {
            pendingServiceDiscoveries.remove(deviceId)
            return Promise.rejected(IllegalStateException("Failed to start service discovery"))
        }
        return promise
    }

    override fun readCharacteristic(deviceId: String, serviceUUID: String, characteristicUUID: String): Promise<CharacteristicValue> {
        val gatt = connectedDevices[deviceId] ?: return Promise.rejected(IllegalStateException("Not connected: $deviceId"))
        val characteristic = findCharacteristic(gatt, serviceUUID, characteristicUUID)
            ?: return Promise.rejected(IllegalArgumentException("Characteristic not found: $serviceUUID/$characteristicUUID"))
        val promise = Promise<CharacteristicValue>()
        val key = characteristicKey(deviceId, serviceUUID, characteristicUUID)
        pendingReads[key] = promise
        if (!gatt.readCharacteristic(characteristic)) {
            pendingReads.remove(key)
            return Promise.rejected(IllegalStateException("Failed to start read"))
        }
        return promise
    }

    override fun writeCharacteristic(deviceId: String, serviceUUID: String, characteristicUUID: String, value: String, writeType: WriteType?): Promise<Unit> {
        val gatt = connectedDevices[deviceId]
        if (gatt != null) {
            Log.d(TAG, "writeCharacteristic (central role): deviceId=$deviceId")
            val characteristic = findCharacteristic(gatt, serviceUUID, characteristicUUID)
                ?: return Promise.rejected(IllegalArgumentException("Characteristic not found: $serviceUUID/$characteristicUUID"))
            characteristic.value = android.util.Base64.decode(value, android.util.Base64.NO_WRAP)
            characteristic.writeType = when (writeType) {
                WriteType.WRITEWITHOUTRESPONSE -> BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                else -> BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            }
            val promise = Promise<Unit>()
            val key = characteristicKey(deviceId, serviceUUID, characteristicUUID)
            pendingWrites[key] = promise
            val result = gatt.writeCharacteristic(characteristic)
            Log.d(TAG, "writeCharacteristic: returned $result")
            if (!result) { pendingWrites.remove(key); return Promise.rejected(IllegalStateException("Failed to write")) }
            return promise
        }
        Log.d(TAG, "writeCharacteristic (peripheral role): delegating to notifyCharacteristic")
        return notifyCharacteristic(serviceUUID, characteristicUUID, value)
    }

    override fun subscribeToCharacteristic(deviceId: String, serviceUUID: String, characteristicUUID: String): Promise<Unit> {
        val gatt = connectedDevices[deviceId] ?: return Promise.rejected(IllegalStateException("Not connected: $deviceId"))
        val characteristic = findCharacteristic(gatt, serviceUUID, characteristicUUID)
            ?: return Promise.rejected(IllegalArgumentException("Characteristic not found: $serviceUUID/$characteristicUUID"))
        gatt.setCharacteristicNotification(characteristic, true)
        val descriptor = characteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG_UUID)
        if (descriptor == null) {
            Log.w(TAG, "subscribeToCharacteristic: no CCCD — resolving immediately")
            return Promise.resolved(Unit)
        }
        descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        val promise = Promise<Unit>()
        val key = "${deviceId}|${characteristic.uuid}"
        pendingDescriptorWrites[key] = promise
        val result = gatt.writeDescriptor(descriptor)
        Log.d(TAG, "subscribeToCharacteristic: writeDescriptor=$result for $characteristicUUID")
        if (!result) { pendingDescriptorWrites.remove(key); return Promise.rejected(IllegalStateException("Failed to write CCCD")) }
        return promise
    }

    override fun unsubscribeFromCharacteristic(deviceId: String, serviceUUID: String, characteristicUUID: String): Promise<Unit> {
        val gatt = connectedDevices[deviceId] ?: return Promise.rejected(IllegalStateException("Not connected: $deviceId"))
        val characteristic = findCharacteristic(gatt, serviceUUID, characteristicUUID)
            ?: return Promise.rejected(IllegalArgumentException("Characteristic not found"))
        gatt.setCharacteristicNotification(characteristic, false)
        val descriptor = characteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG_UUID) ?: return Promise.resolved(Unit)
        descriptor.value = BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
        val promise = Promise<Unit>()
        val key = "${deviceId}|${characteristic.uuid}"
        pendingDescriptorWrites[key] = promise
        val result = gatt.writeDescriptor(descriptor)
        if (!result) { pendingDescriptorWrites.remove(key); return Promise.rejected(IllegalStateException("Failed to write CCCD")) }
        return promise
    }

    override fun getConnectedDevices(): Promise<Array<String>> = Promise.resolved(connectedDevices.keys.toTypedArray())

    override fun readRSSI(deviceId: String): Promise<Double> {
        val gatt = connectedDevices[deviceId] ?: return Promise.rejected(IllegalStateException("Not connected: $deviceId"))
        lastRssiValues[deviceId]?.let { return Promise.resolved(it) }
        val promise = Promise<Double>()
        pendingRssiReads[deviceId] = promise
        if (!gatt.readRemoteRssi()) { pendingRssiReads.remove(deviceId); return Promise.rejected(IllegalStateException("Failed to read RSSI")) }
        return promise
    }

    override fun startBackgroundSession(options: BackgroundSessionOptions) {
        val context = NitroModules.applicationContext ?: return
        if (!ensureBluetoothPermissions("start background session")) return
        val intent = Intent(context, MunimBluetoothBackgroundService::class.java).apply {
            action = MunimBluetoothBackgroundService.ACTION_START
            putExtra(MunimBluetoothBackgroundService.EXTRA_SERVICE_UUIDS, options.serviceUUIDs)
            putExtra(MunimBluetoothBackgroundService.EXTRA_LOCAL_NAME, options.localName)
            putExtra(MunimBluetoothBackgroundService.EXTRA_ALLOW_DUPLICATES, options.allowDuplicates ?: false)
            putExtra(MunimBluetoothBackgroundService.EXTRA_SCAN_MODE, options.scanMode?.name ?: ScanMode.LOWPOWER.name)
            putExtra(MunimBluetoothBackgroundService.EXTRA_NOTIFICATION_CHANNEL_ID, options.androidNotificationChannelId ?: MunimBluetoothBackgroundService.DEFAULT_CHANNEL_ID)
            putExtra(MunimBluetoothBackgroundService.EXTRA_NOTIFICATION_CHANNEL_NAME, options.androidNotificationChannelName ?: MunimBluetoothBackgroundService.DEFAULT_CHANNEL_NAME)
            putExtra(MunimBluetoothBackgroundService.EXTRA_NOTIFICATION_TITLE, options.androidNotificationTitle ?: MunimBluetoothBackgroundService.DEFAULT_NOTIFICATION_TITLE)
            putExtra(MunimBluetoothBackgroundService.EXTRA_NOTIFICATION_TEXT, options.androidNotificationText ?: MunimBluetoothBackgroundService.DEFAULT_NOTIFICATION_TEXT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent) else context.startService(intent)
    }

    override fun stopBackgroundSession() {
        val context = NitroModules.applicationContext ?: return
        context.startService(Intent(context, MunimBluetoothBackgroundService::class.java).apply { action = MunimBluetoothBackgroundService.ACTION_STOP })
    }

    // ── Event callback registrations ──────────────────────────────────────────
    override fun onCentralReady(callback: (event: CentralReadyEvent) -> Unit): () -> Unit {
        onCentralReadyCallback = callback
        return { onCentralReadyCallback = null }
    }

    override fun onPeripheralReady(callback: (deviceId: String) -> Unit): () -> Unit {
        onPeripheralReadyCallback = callback
        return { onPeripheralReadyCallback = null }
    }

    /** @deprecated Use onCentralReady or onPeripheralReady */
    override fun onDeviceConnected(callback: (deviceId: String) -> Unit): () -> Unit {
        onDeviceConnectedCallback = callback
        return { onDeviceConnectedCallback = null }
    }

    override fun onDeviceDisconnected(callback: (deviceId: String) -> Unit): () -> Unit {
        onDeviceDisconnectedCallback = callback
        return { onDeviceDisconnectedCallback = null }
    }

    override fun onCharacteristicValueChanged(callback: (deviceId: String, serviceUUID: String, characteristicUUID: String, value: String) -> Unit): () -> Unit {
        onCharacteristicValueChangedCallback = callback
        return { onCharacteristicValueChangedCallback = null }
    }

    override fun onPeripheralStateChanged(callback: (state: String) -> Unit): () -> Unit {
        onPeripheralStateChangedCallback = callback
        return { onPeripheralStateChangedCallback = null }
    }

    override fun onDeviceFound(callback: (device: BLEDevice) -> Unit): () -> Unit {
        onDeviceFoundCallback = callback
        return { onDeviceFoundCallback = null }
    }

    override fun notifyCharacteristic(serviceUUID: String, characteristicUUID: String, value: String): Promise<Unit> {
        val server = gattServer ?: return Promise.rejected(IllegalStateException("GATT server not initialized"))
        val characteristic = server.services
            ?.firstOrNull { it.uuid.toString().equals(serviceUUID, ignoreCase = true) }
            ?.characteristics
            ?.firstOrNull { it.uuid.toString().equals(characteristicUUID, ignoreCase = true) }
            ?: return Promise.rejected(IllegalArgumentException("Characteristic not found: $characteristicUUID"))
        val data = value.toByteArray(Charsets.UTF_8)
        val promise = Promise<Unit>()
        pendingNotifications.addLast(Triple(characteristic, data, promise))
        drainNotificationQueue()
        return promise
    }

    private fun drainNotificationQueue() {
        if (isNotifying) return
        val server = gattServer ?: return
        val connectedCentrals = bluetoothManager?.getConnectedDevices(BluetoothProfile.GATT) ?: emptyList()
        if (connectedCentrals.isEmpty()) {
            while (pendingNotifications.isNotEmpty()) pendingNotifications.removeFirst().third.reject(IllegalStateException("No connected centrals"))
            return
        }
        val next = pendingNotifications.firstOrNull() ?: return
        next.first.value = next.second
        isNotifying = true
        connectedCentrals.forEach { server.notifyCharacteristicChanged(it, next.first, false) }
    }

    private fun restartAdvertising(delayMs: Long) {
        if (!ensureBluetoothPermissions("restart advertising")) return
        ensureBluetoothManager()
        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) return

        advertiseJob?.cancel()
        advertiseCallback?.let { advertiser?.stopAdvertising(it) }

        advertiseJob = bluetoothScope.launch {
            if (delayMs > 0) delay(delayMs)
            advertiser = adapter.bluetoothLeAdvertiser ?: run { Log.e(TAG, "BLE advertiser not available"); return@launch }
            val dataBuilder = AdvertiseData.Builder()
            currentAdvertisingData?.let { processAdvertisingData(it, dataBuilder) }
            currentServiceUUIDs.forEach { dataBuilder.addServiceUuid(ParcelUuid.fromString(it)) }
            val settings = AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setConnectable(true).setTimeout(0)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH).build()
            advertiseCallback = object : AdvertiseCallback() {
                override fun onStartSuccess(s: AdvertiseSettings) { Log.i(TAG, "Advertising started successfully") }
                override fun onStartFailure(e: Int) { Log.e(TAG, "Advertising failed: $e") }
            }
            advertiser!!.startAdvertising(settings, dataBuilder.build(), advertiseCallback)
        }
    }

    private fun buildGattServerCallback(): BluetoothGattServerCallback {
        return object : BluetoothGattServerCallback() {

            override fun onNotificationSent(device: BluetoothDevice, status: Int) {
                val notification = pendingNotifications.firstOrNull() ?: return
                pendingNotifications.removeFirst()
                isNotifying = false
                if (status == BluetoothGatt.GATT_SUCCESS) notification.third.resolve(Unit)
                else { Log.w(TAG, "onNotificationSent failed status=$status"); notification.third.reject(IllegalStateException("Notification failed status=$status")) }
                bluetoothScope.launch { drainNotificationQueue() }
            }

            override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
                val deviceId = device.address
                Log.d(TAG, "GattServer onConnectionStateChange: deviceId=$deviceId newState=$newState")
                if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    centralSubscriptions.clear()
                    Log.d(TAG, "GattServer: central $deviceId disconnected")
                    onDeviceDisconnectedCallback?.invoke(deviceId)
                }
            }

            override fun onDescriptorWriteRequest(
                device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor,
                preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?
            ) {
                Log.d(TAG, "onDescriptorWriteRequest from ${device.address} on ${descriptor.uuid}")
                descriptor.value = value
                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
                    Log.d(TAG, "onDescriptorWriteRequest: sent GATT_SUCCESS response")
                }

                // Track subscriptions per central; fire onCentralReady once all confirmed
                val deviceId = device.address
                val charUUID = descriptor.characteristic?.uuid?.toString() ?: return
                val subscribed = centralSubscriptions.getOrPut(deviceId) { mutableSetOf() }
                subscribed.add(charUUID)
                Log.d(TAG, "central $deviceId subscribed to $charUUID (${subscribed.size}/$expectedSubscriptionCount)")

                if (subscribed.size >= expectedSubscriptionCount && expectedSubscriptionCount > 0) {
                    val uuids = subscribed.toTypedArray()
                    Log.d(TAG, "onCentralReady: firing for $deviceId with ${uuids.size} characteristic(s)")
                    onCentralReadyCallback?.invoke(CentralReadyEvent(deviceId, uuids))
                }
            }

            override fun onCharacteristicReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, characteristic: BluetoothGattCharacteristic) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, characteristic.value)
            }

            override fun onCharacteristicWriteRequest(
                device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic,
                preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?
            ) {
                val data = value ?: byteArrayOf()
                characteristic.value = data
                if (responseNeeded) gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
                val text = android.util.Base64.encodeToString(data, android.util.Base64.NO_WRAP)
                onCharacteristicValueChangedCallback?.invoke(device.address, characteristic.service?.uuid?.toString() ?: "", characteristic.uuid.toString(), text)
                Log.d(TAG, "Peripheral received write from ${device.address} on ${characteristic.uuid}")
            }
        }
    }

    private fun createGattCallback(deviceId: String): BluetoothGattCallback {
        return object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS && newState != BluetoothProfile.STATE_CONNECTED) {
                    pendingConnections.remove(deviceId)?.reject(IllegalStateException("Failed to connect (status=$status)"))
                    connectedDevices.remove(deviceId)?.close()
                    return
                }
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        connectedDevices[deviceId] = gatt
                        Log.d(TAG, "Connected to $deviceId — requesting MTU 512")
                        gatt.requestMtu(512)
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        pendingConnections.remove(deviceId)?.reject(IllegalStateException("Disconnected from $deviceId"))
                        val staleGatt = connectedDevices.remove(deviceId)
                        bluetoothScope.launch { delay(500); staleGatt?.close(); Log.d(TAG, "GATT closed after disconnect for $deviceId") }
                        rejectPendingOperationsForDevice(deviceId, IllegalStateException("Disconnected from $deviceId"))
                        onDeviceDisconnectedCallback?.invoke(deviceId)
                    }
                }
            }

            override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
                Log.d(TAG, "MTU changed to $mtu for $deviceId (status=$status)")
                // Resolve connection promise — JS calls emitPeripheralReady after
                // discoverServices + subscribeToCharacteristic complete
                pendingConnections.remove(deviceId)?.resolve(Unit)
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                Log.d(TAG, "onServicesDiscovered: deviceId=$deviceId status=$status services=${gatt.services.size}")
                if (status == BluetoothGatt.GATT_SUCCESS) pendingServiceDiscoveries.remove(deviceId)?.resolve(buildGattServices(gatt))
                else pendingServiceDiscoveries.remove(deviceId)?.reject(IllegalStateException("Service discovery failed (status=$status)"))
            }

            override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
                val key = "${deviceId}|${descriptor.characteristic.uuid}"
                Log.d(TAG, "onDescriptorWrite: key=$key status=$status")
                if (status == BluetoothGatt.GATT_SUCCESS) pendingDescriptorWrites.remove(key)?.resolve(Unit)
                else pendingDescriptorWrites.remove(key)?.reject(IllegalStateException("Descriptor write failed status=$status"))
            }

            override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
                val key = characteristicKey(deviceId, characteristic.service.uuid.toString(), characteristic.uuid.toString())
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    val value = buildCharacteristicValue(characteristic)
                    lastCharacteristicValues[key] = value
                    pendingReads.remove(key)?.resolve(value)
                    onCharacteristicValueChangedCallback?.invoke(deviceId, value.serviceUUID, value.characteristicUUID, value.value)
                } else pendingReads.remove(key)?.reject(IllegalStateException("Read failed (status=$status)"))
            }

            override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
                val key = characteristicKey(deviceId, characteristic.service.uuid.toString(), characteristic.uuid.toString())
                if (status == BluetoothGatt.GATT_SUCCESS) pendingWrites.remove(key)?.resolve(Unit)
                else pendingWrites.remove(key)?.reject(IllegalStateException("Write failed (status=$status)"))
            }

            override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
                val value = buildCharacteristicValue(characteristic)
                lastCharacteristicValues[characteristicKey(deviceId, value.serviceUUID, value.characteristicUUID)] = value
                onCharacteristicValueChangedCallback?.invoke(deviceId, value.serviceUUID, value.characteristicUUID, value.value)
            }

            override fun onReadRemoteRssi(gatt: BluetoothGatt, rssi: Int, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS) { lastRssiValues[deviceId] = rssi.toDouble(); pendingRssiReads.remove(deviceId)?.resolve(rssi.toDouble()) }
                else pendingRssiReads.remove(deviceId)?.reject(IllegalStateException("RSSI read failed (status=$status)"))
            }
        }
    }

    private fun rejectPendingOperationsForDevice(deviceId: String, error: Throwable) {
        pendingReads.keys.filter { it.startsWith("$deviceId|") }.forEach { pendingReads.remove(it)?.reject(error) }
        pendingWrites.keys.filter { it.startsWith("$deviceId|") }.forEach { pendingWrites.remove(it)?.reject(error) }
        pendingServiceDiscoveries.remove(deviceId)?.reject(error)
        pendingRssiReads.remove(deviceId)?.reject(error)
    }

    private fun buildGattServices(gatt: BluetoothGatt): Array<GATTService> = gatt.services.map { service ->
        GATTService(uuid = service.uuid.toString(), characteristics = service.characteristics.map { char ->
            GATTCharacteristic(uuid = char.uuid.toString(), properties = propertiesToArray(char.properties),
                value = char.value?.let { android.util.Base64.encodeToString(it, android.util.Base64.NO_WRAP) })
        }.toTypedArray())
    }.toTypedArray()

    private fun buildCharacteristicValue(characteristic: BluetoothGattCharacteristic) = CharacteristicValue(
        value = characteristic.value?.let { android.util.Base64.encodeToString(it, android.util.Base64.NO_WRAP) } ?: "",
        serviceUUID = characteristic.service.uuid.toString(),
        characteristicUUID = characteristic.uuid.toString()
    )

    private fun findCharacteristic(gatt: BluetoothGatt, serviceUUID: String, characteristicUUID: String): BluetoothGattCharacteristic? =
        gatt.services.firstOrNull { it.uuid.toString().equals(serviceUUID, ignoreCase = true) }
            ?.characteristics?.firstOrNull { it.uuid.toString().equals(characteristicUUID, ignoreCase = true) }

    private fun characteristicKey(deviceId: String, serviceUUID: String, characteristicUUID: String) =
        "$deviceId|${serviceUUID.lowercase()}|${characteristicUUID.lowercase()}"

    private fun extractManufacturerData(record: ScanRecord?): String? {
        val data = record?.manufacturerSpecificData ?: return null
        return if (data.size() == 0) null else data.valueAt(0)?.toHexString()
    }

    private fun extractServiceData(record: ScanRecord?): List<ServiceDataEntry>? =
        record?.serviceData?.entries?.mapNotNull { entry ->
            val value = entry.value ?: return@mapNotNull null
            ServiceDataEntry(entry.key.uuid.toString(), value.toHexString())
        }?.takeIf { it.isNotEmpty() }

    private fun processAdvertisingData(data: AdvertisingDataTypes, dataBuilder: AdvertiseData.Builder) {
        addServiceUUIDs(data.incompleteServiceUUIDs16, dataBuilder)
        addServiceUUIDs(data.completeServiceUUIDs16, dataBuilder)
        addServiceUUIDs(data.incompleteServiceUUIDs32, dataBuilder)
        addServiceUUIDs(data.completeServiceUUIDs32, dataBuilder)
        addServiceUUIDs(data.incompleteServiceUUIDs128, dataBuilder)
        addServiceUUIDs(data.completeServiceUUIDs128, dataBuilder)
        if (data.shortenedLocalName != null || data.completeLocalName != null) dataBuilder.setIncludeDeviceName(true)
        if (data.txPowerLevel != null) dataBuilder.setIncludeTxPowerLevel(true)
        addServiceUUIDs(data.serviceSolicitationUUIDs16, dataBuilder)
        addServiceUUIDs(data.serviceSolicitationUUIDs32, dataBuilder)
        addServiceUUIDs(data.serviceSolicitationUUIDs128, dataBuilder)
        addServiceData(data.serviceData16, dataBuilder)
        addServiceData(data.serviceData32, dataBuilder)
        addServiceData(data.serviceData128, dataBuilder)
        data.appearance?.toInt()?.let { appearance ->
            dataBuilder.addServiceData(ParcelUuid.fromString("00001800-0000-1000-8000-00805F9B34FB"),
                byteArrayOf((appearance and 0xFF).toByte(), ((appearance shr 8) and 0xFF).toByte()))
        }
        data.manufacturerData?.let { hexStringToByteArray(it)?.let { bytes -> dataBuilder.addManufacturerData(0x0000, bytes) } }
    }

    private fun normalizeAdvertisingData(advertisingData: AdvertisingDataTypes?, localName: String?, manufacturerData: String?): AdvertisingDataTypes {
        val base = advertisingData ?: emptyAdvertisingData()
        return base.copy(completeLocalName = base.completeLocalName ?: localName, manufacturerData = base.manufacturerData ?: manufacturerData)
    }

    private fun emptyAdvertisingData() = AdvertisingDataTypes(
        flags = null, incompleteServiceUUIDs16 = null, completeServiceUUIDs16 = null,
        incompleteServiceUUIDs32 = null, completeServiceUUIDs32 = null, incompleteServiceUUIDs128 = null,
        completeServiceUUIDs128 = null, shortenedLocalName = null, completeLocalName = null,
        txPowerLevel = null, serviceSolicitationUUIDs16 = null, serviceSolicitationUUIDs128 = null,
        serviceData16 = null, serviceData32 = null, serviceData128 = null, appearance = null,
        serviceSolicitationUUIDs32 = null, manufacturerData = null
    )

    private fun propertiesFromArray(properties: Array<String>): Int {
        var result = 0
        properties.forEach { when (it) {
            "read" -> result = result or BluetoothGattCharacteristic.PROPERTY_READ
            "write" -> result = result or BluetoothGattCharacteristic.PROPERTY_WRITE
            "notify" -> result = result or BluetoothGattCharacteristic.PROPERTY_NOTIFY
            "indicate" -> result = result or BluetoothGattCharacteristic.PROPERTY_INDICATE
            "writeWithoutResponse" -> result = result or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE
        }}
        return result
    }

    private fun propertiesToArray(properties: Int): Array<String> {
        val result = mutableListOf<String>()
        if (properties and BluetoothGattCharacteristic.PROPERTY_READ != 0) result += "read"
        if (properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) result += "write"
        if (properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) result += "notify"
        if (properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) result += "indicate"
        if (properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) result += "writeWithoutResponse"
        return result.toTypedArray()
    }

    private fun addServiceUUIDs(uuids: Array<String>?, dataBuilder: AdvertiseData.Builder) {
        uuids?.forEach { dataBuilder.addServiceUuid(ParcelUuid.fromString(it)) }
    }

    private fun addServiceData(entries: Array<ServiceDataEntry>?, dataBuilder: AdvertiseData.Builder) {
        entries?.forEach { entry -> hexStringToByteArray(entry.data)?.let { dataBuilder.addServiceData(ParcelUuid.fromString(entry.uuid), it) } }
    }

    private fun hexStringToByteArray(hexString: String?): ByteArray? {
        if (hexString == null) return null
        val clean = hexString.replace(" ", "")
        if (clean.length % 2 != 0) return null
        return try { ByteArray(clean.length / 2) { i -> clean.substring(i * 2, i * 2 + 2).toInt(16).toByte() } }
        catch (_: NumberFormatException) { null }
    }

    private fun ByteArray.toHexString() = joinToString("") { "%02x".format(it) }

    private fun restoreAdapterName() {
        val adapter = bluetoothAdapter ?: return
        val original = previousAdapterName ?: return
        try { adapter.name = original } catch (e: SecurityException) { Log.w(TAG, "Unable to restore adapter name", e) }
        previousAdapterName = null
    }

    private fun buildBLEDevice(result: ScanResult): BLEDevice {
        val record = result.scanRecord
        val serviceUUIDs = record?.serviceUuids?.map { it.uuid.toString() }?.toTypedArray()
        val advertisedName = record?.deviceName?.takeIf { it.isNotBlank() } ?: result.device.name
        return BLEDevice(
            id = result.device.address, name = advertisedName, rssi = result.rssi.toDouble(),
            advertisingData = AdvertisingDataTypes(
                flags = null, incompleteServiceUUIDs16 = null, completeServiceUUIDs16 = serviceUUIDs,
                incompleteServiceUUIDs32 = null, completeServiceUUIDs32 = null, incompleteServiceUUIDs128 = null,
                completeServiceUUIDs128 = null, shortenedLocalName = null, completeLocalName = advertisedName,
                txPowerLevel = record?.txPowerLevel?.takeIf { it != Int.MIN_VALUE }?.toDouble(),
                serviceSolicitationUUIDs16 = null, serviceSolicitationUUIDs128 = null,
                serviceData16 = extractServiceData(record)?.toTypedArray(), serviceData32 = null,
                serviceData128 = null, appearance = null, serviceSolicitationUUIDs32 = null,
                manufacturerData = extractManufacturerData(record)
            ),
            serviceUUIDs = serviceUUIDs,
            isConnectable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) result.isConnectable else null
        )
    }

    companion object {
        private const val TAG = "HybridMunimBluetooth"
        private const val BLUETOOTH_PERMISSION_REQUEST_CODE = 9137
        private val CLIENT_CHARACTERISTIC_CONFIG_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
