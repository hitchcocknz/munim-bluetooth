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
    // private val eventEmitter = NitroEventEmitter(TAG)
    private var nextPermissionRequestCode = BLUETOOTH_PERMISSION_REQUEST_CODE

    private var onDeviceConnectedCallback: ((deviceId: String) -> Unit)? = null
    private var onDeviceDisconnectedCallback: ((deviceId: String) -> Unit)? = null
    private var onCharacteristicValueChangedCallback: ((deviceId: String, serviceUUID: String, characteristicUUID: String, value: String) -> Unit)? = null
    private var onPeripheralStateChangedCallback: ((state: String) -> Unit)? = null
    private var onDeviceFoundCallback: ((device: BLEDevice) -> Unit)? = null
    private val pendingDescriptorWrites = mutableMapOf<String, Promise<Unit>>()

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
        val context = NitroModules.applicationContext
        if (context == null) {
            Log.w(TAG, "Unable to $operationName: React context unavailable")
            return false
        }

        val missingPermissions = BluetoothPermissionUtils.missingPermissions(context)
        if (missingPermissions.isNotEmpty()) {
            Log.w(
                TAG,
                "Unable to $operationName: missing Bluetooth permissions (${missingPermissions.joinToString()})"
            )
            return false
        }

        return true
    }

    override fun startAdvertising(options: AdvertisingOptions) {
        if (!ensureBluetoothPermissions("start advertising")) {
            return
        }

        ensureBluetoothManager()
        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            Log.e(TAG, "Bluetooth is not enabled or not available")
            return
        }
        if (options.serviceUUIDs.isEmpty()) {
            Log.e(TAG, "No service UUIDs provided for advertising")
            return
        }

        currentServiceUUIDs = options.serviceUUIDs
        currentLocalName = options.localName
        currentManufacturerData = options.manufacturerData
        currentAdvertisingData = normalizeAdvertisingData(
            options.advertisingData,
            options.localName,
            options.manufacturerData
        )

        if (!currentLocalName.isNullOrBlank() && previousAdapterName == null) {
            previousAdapterName = try {
                adapter.name
            } catch (error: SecurityException) {
                Log.w(TAG, "Unable to read Bluetooth adapter name", error)
                null
            }
        }
        if (!currentLocalName.isNullOrBlank()) {
            try {
                adapter.name = currentLocalName
            } catch (error: SecurityException) {
                Log.w(TAG, "Unable to apply custom localName to Bluetooth adapter", error)
            }
        }

        if (!gattServerReady) {
            setServicesFromOptions(options.serviceUUIDs)
        }
        restartAdvertising(delayMs = 300L)
    }

    override fun updateAdvertisingData(advertisingData: AdvertisingDataTypes) {
        currentAdvertisingData = normalizeAdvertisingData(
            advertisingData,
            currentLocalName,
            currentManufacturerData
        )
        if (currentServiceUUIDs.isNotEmpty()) {
            restartAdvertising(delayMs = 100L)
        }
    }

    override fun getAdvertisingData(): Promise<AdvertisingDataTypes> {
        return Promise.resolved(currentAdvertisingData ?: emptyAdvertisingData())
    }

    override fun stopAdvertising() {
        advertiseJob?.cancel()
        advertiseCallback?.let { callback ->
            advertiser?.stopAdvertising(callback)
        }
        advertiseCallback = null
        advertiser = null
        currentAdvertisingData = null
        currentServiceUUIDs = emptyArray()
        currentLocalName = null
        currentManufacturerData = null
        restoreAdapterName()
    }

    override fun setServices(services: Array<GATTService>) {
        if (!ensureBluetoothPermissions("set GATT services")) {
            return
        }

        ensureBluetoothManager()
        gattServerReady = false

        val manager = bluetoothManager ?: return
        val context = NitroModules.applicationContext ?: return

        gattServer?.close()
        gattServer = manager.openGattServer(context, buildGattServerCallback())
        gattServer?.clearServices()

        for (serviceData in services) {
            val service = BluetoothGattService(
                UUID.fromString(serviceData.uuid),
                BluetoothGattService.SERVICE_TYPE_PRIMARY
            )

            for (characteristicData in serviceData.characteristics) {
                val characteristic = BluetoothGattCharacteristic(
                    UUID.fromString(characteristicData.uuid),
                    propertiesFromArray(characteristicData.properties),
                    BluetoothGattCharacteristic.PERMISSION_READ or
                        BluetoothGattCharacteristic.PERMISSION_WRITE
                )
                characteristicData.value?.let { value ->
                    characteristic.value = hexStringToByteArray(value) ?: value.toByteArray()
                }
                service.addCharacteristic(characteristic)
            }

            gattServer?.addService(service)
        }

        gattServerReady = true
    }

    override fun isBluetoothEnabled(): Promise<Boolean> {
        Log.d(TAG, "isBluetoothEnabled called")
        Log.d(TAG, "hasPermissions: ${hasRequiredBluetoothPermissions()}")
        Log.d(TAG, "bluetoothAdapter: $bluetoothAdapter")
        Log.d(TAG, "adapter.isEnabled: ${bluetoothAdapter?.isEnabled}")
        
        if (!hasRequiredBluetoothPermissions()) {
            Log.d(TAG, "returning false - no permissions")
            return Promise.resolved(false)
        }

        ensureBluetoothManager()
        Log.d(TAG, "after ensureManager - adapter.isEnabled: ${bluetoothAdapter?.isEnabled}")
        return Promise.resolved(bluetoothAdapter?.isEnabled == true)
    }

    override fun requestBluetoothPermission(): Promise<Boolean> {
        val context = NitroModules.applicationContext ?: return Promise.resolved(false)
        val missing = BluetoothPermissionUtils.missingPermissions(context)
        // In bridgeless mode we can only report current state — the host app
        // must request permissions via its own flow before calling BLE APIs.
        return Promise.resolved(missing.isEmpty())
    }

    override fun startScan(options: ScanOptions?) {
        if (!ensureBluetoothPermissions("start scanning")) {
            return
        }

        ensureBluetoothManager()
        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            Log.e(TAG, "Bluetooth is not enabled or not available")
            return
        }
        if (isScanning) return

        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            Log.e(TAG, "Bluetooth LE scanner is not available")
            return
        }

        isScanning = true
        discoveredDevices.clear()
        bluetoothLeScanner = scanner

        val scanFilters = options?.serviceUUIDs
            ?.takeIf { it.isNotEmpty() }
            ?.map { uuid ->
                ScanFilter.Builder()
                    .setServiceUuid(ParcelUuid.fromString(uuid))
                    .build()
            }
            ?: emptyList()

        val scanMode = when (options?.scanMode) {
            ScanMode.LOWPOWER -> ScanSettings.SCAN_MODE_LOW_POWER
            ScanMode.LOWLATENCY -> ScanSettings.SCAN_MODE_LOW_LATENCY
            else -> ScanSettings.SCAN_MODE_BALANCED
        }

        val scanSettings = ScanSettings.Builder()
            .setScanMode(scanMode)
            .build()

        scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device
                discoveredDevices[device.address] = device
                onDeviceFoundCallback?.invoke(buildBLEDevice(result))  // ← replace eventEmitter.emit
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach { result ->
                    val device = result.device
                    discoveredDevices[device.address] = device
                    onDeviceFoundCallback?.invoke(buildBLEDevice(result))  // ← replace eventEmitter.emit
                }
            }

            override fun onScanFailed(errorCode: Int) {
                Log.e(TAG, "Scan failed: $errorCode")
                isScanning = false
            }
        }

        scanner.startScan(scanFilters, scanSettings, scanCallback)
    }

    override fun stopScan() {
        if (!isScanning) return
        scanCallback?.let { callback ->
            bluetoothLeScanner?.stopScan(callback)
        }
        bluetoothLeScanner = null
        scanCallback = null
        isScanning = false
    }

    override fun connect(deviceId: String): Promise<Unit> {
        if (!ensureBluetoothPermissions("connect to BLE device")) {
            return Promise.rejected(IllegalStateException("Bluetooth permissions not granted"))
        }

        ensureBluetoothManager()

        // Close any stale GATT before reconnecting
        connectedDevices.remove(deviceId)?.let { staleGatt ->
            staleGatt.disconnect()
            bluetoothScope.launch {
                delay(500)
                staleGatt.close()
            }
        }

        val context = NitroModules.applicationContext
            ?: return Promise.rejected(IllegalStateException("React context unavailable"))
        val adapter = bluetoothAdapter
            ?: return Promise.rejected(IllegalStateException("Bluetooth adapter unavailable"))

        val device = discoveredDevices[deviceId] ?: run {
            try {
                adapter.getRemoteDevice(deviceId)
            } catch (_: IllegalArgumentException) {
                null
            }
        } ?: return Promise.rejected(IllegalArgumentException("Device not found: $deviceId"))

        val promise = Promise<Unit>()
        pendingConnections[deviceId] = promise

        // Delay connection attempt to let BLE stack settle after previous disconnect
        bluetoothScope.launch {
            delay(300)
            val gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                device.connectGatt(context, false, createGattCallback(deviceId), BluetoothDevice.TRANSPORT_LE)
            } else {
                device.connectGatt(context, false, createGattCallback(deviceId))
            }
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
        // Delay close to allow stack to process disconnect
        bluetoothScope.launch {
            delay(500)
            gatt?.close()
        }

        rejectPendingOperationsForDevice(deviceId, IllegalStateException("Disconnected from $deviceId"))
        onDeviceDisconnectedCallback?.invoke(deviceId)
    }

    override fun discoverServices(deviceId: String): Promise<Array<GATTService>> {
        val gatt = connectedDevices[deviceId]
            ?: return Promise.rejected(IllegalStateException("Device not connected: $deviceId"))

        // Always rediscover — cached services can cause write failures
        // if the GATT isn't fully ready even though services appear populated
        val promise = Promise<Array<GATTService>>()
        pendingServiceDiscoveries[deviceId] = promise
        if (!gatt.discoverServices()) {
            pendingServiceDiscoveries.remove(deviceId)
            return Promise.rejected(IllegalStateException("Failed to start service discovery for $deviceId"))
        }
        return promise
    }

    override fun readCharacteristic(
        deviceId: String,
        serviceUUID: String,
        characteristicUUID: String
    ): Promise<CharacteristicValue> {
        val gatt = connectedDevices[deviceId]
            ?: return Promise.rejected(IllegalStateException("Device not connected: $deviceId"))
        val characteristic = findCharacteristic(gatt, serviceUUID, characteristicUUID)
            ?: return Promise.rejected(
                IllegalArgumentException("Characteristic not found: $serviceUUID/$characteristicUUID")
            )

        val promise = Promise<CharacteristicValue>()
        val key = characteristicKey(deviceId, serviceUUID, characteristicUUID)
        pendingReads[key] = promise

        if (!gatt.readCharacteristic(characteristic)) {
            pendingReads.remove(key)
            return Promise.rejected(IllegalStateException("Failed to start characteristic read"))
        }
        return promise
    }

    override fun writeCharacteristic(
        deviceId: String,
        serviceUUID: String,
        characteristicUUID: String,
        value: String,
        writeType: WriteType?
    ): Promise<Unit> {

        // ── Central role: write to remote peripheral ───────────────────────────
        val gatt = connectedDevices[deviceId]
        if (gatt != null) {
            Log.d(TAG, "writeCharacteristic (central role): deviceId=$deviceId")
            
            val characteristic = findCharacteristic(gatt, serviceUUID, characteristicUUID)
                ?: return Promise.rejected(IllegalArgumentException(
                    "Characteristic not found: $serviceUUID/$characteristicUUID"))

            characteristic.value = android.util.Base64.decode(value, android.util.Base64.NO_WRAP)
            characteristic.writeType = when (writeType) {
                WriteType.WRITEWITHOUTRESPONSE -> BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                else -> BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            }

            val promise = Promise<Unit>()
            val key = characteristicKey(deviceId, serviceUUID, characteristicUUID)
            pendingWrites[key] = promise

            val result = gatt.writeCharacteristic(characteristic)
            Log.d(TAG, "writeCharacteristic: gatt.writeCharacteristic returned $result")

            if (!result) {
                pendingWrites.remove(key)
                return Promise.rejected(IllegalStateException("Failed to start characteristic write"))
            }
            return promise
        }

        // ── Peripheral role: notify connected central ──────────────────────────
        Log.d(TAG, "writeCharacteristic (peripheral role): notifying central $deviceId")
        val server = gattServer
            ?: return Promise.rejected(IllegalStateException("GATT server not initialized"))

        val targetService = server.services
            ?.firstOrNull { it.uuid.toString().equals(serviceUUID, ignoreCase = true) }
            ?: return Promise.rejected(IllegalArgumentException("Service not found: $serviceUUID"))

        val characteristic = targetService.characteristics
            ?.firstOrNull { it.uuid.toString().equals(characteristicUUID, ignoreCase = true) }
            ?: return Promise.rejected(IllegalArgumentException("Characteristic not found: $characteristicUUID"))

        val data = android.util.Base64.decode(value, android.util.Base64.NO_WRAP)
        characteristic.value = data

        // Find the specific central device to notify
        val centralDevice = bluetoothManager
            ?.getConnectedDevices(android.bluetooth.BluetoothProfile.GATT)
            ?.firstOrNull { it.address == deviceId }

        if (centralDevice == null) {
            // No specific device found — notify all connected centrals
            val connectedCentrals = bluetoothManager
                ?.getConnectedDevices(android.bluetooth.BluetoothProfile.GATT)
                ?: emptyList()

            if (connectedCentrals.isEmpty()) {
                return Promise.rejected(IllegalStateException("No connected centrals to notify"))
            }

            connectedCentrals.forEach { device ->
                server.notifyCharacteristicChanged(device, characteristic, false)
            }
        } else {
            server.notifyCharacteristicChanged(centralDevice, characteristic, false)
        }

        return Promise.resolved(Unit)
    }

    override fun subscribeToCharacteristic(
        deviceId: String,
        serviceUUID: String,
        characteristicUUID: String
    ): Promise<Unit> {
        val gatt = connectedDevices[deviceId]
            ?: return Promise.rejected(IllegalStateException("Device not connected: $deviceId"))
        val characteristic = findCharacteristic(gatt, serviceUUID, characteristicUUID)
            ?: return Promise.rejected(IllegalArgumentException("Characteristic not found: $serviceUUID/$characteristicUUID"))

        gatt.setCharacteristicNotification(characteristic, true)

        val descriptor = characteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG_UUID)
        if (descriptor == null) {
            Log.w(TAG, "subscribeToCharacteristic: no CCCD descriptor — resolving immediately")
            return Promise.resolved(Unit)
        }

        descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        val promise = Promise<Unit>()
        val key = "${deviceId}|${characteristic.uuid}"
        pendingDescriptorWrites[key] = promise

        val result = gatt.writeDescriptor(descriptor)
        Log.d(TAG, "subscribeToCharacteristic: writeDescriptor returned $result for $characteristicUUID")
        if (!result) {
            pendingDescriptorWrites.remove(key)
            return Promise.rejected(IllegalStateException("Failed to write CCCD descriptor"))
        }
        return promise
    }

    override fun unsubscribeFromCharacteristic(
        deviceId: String,
        serviceUUID: String,
        characteristicUUID: String
    ): Promise<Unit> {
        val gatt = connectedDevices[deviceId]
            ?: return Promise.rejected(IllegalStateException("Device not connected: $deviceId"))
        val characteristic = findCharacteristic(gatt, serviceUUID, characteristicUUID)
            ?: return Promise.rejected(IllegalArgumentException("Characteristic not found"))

        gatt.setCharacteristicNotification(characteristic, false)

        val descriptor = characteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG_UUID)
        if (descriptor == null) {
            return Promise.resolved(Unit)
        }

        descriptor.value = BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
        val promise = Promise<Unit>()
        val key = "${deviceId}|${characteristic.uuid}"
        pendingDescriptorWrites[key] = promise

        val result = gatt.writeDescriptor(descriptor)
        if (!result) {
            pendingDescriptorWrites.remove(key)
            return Promise.rejected(IllegalStateException("Failed to write CCCD descriptor"))
        }
        return promise
    }

    override fun startBackgroundSession(options: BackgroundSessionOptions) {
        val context = NitroModules.applicationContext ?: run {
            Log.w(TAG, "Unable to start background BLE session: application context unavailable")
            return
        }

        if (!ensureBluetoothPermissions("start background BLE session")) {
            return
        }

        val intent = Intent(context, MunimBluetoothBackgroundService::class.java).apply {
            action = MunimBluetoothBackgroundService.ACTION_START
            putExtra(
                MunimBluetoothBackgroundService.EXTRA_SERVICE_UUIDS,
                options.serviceUUIDs
            )
            putExtra(
                MunimBluetoothBackgroundService.EXTRA_LOCAL_NAME,
                options.localName
            )
            putExtra(
                MunimBluetoothBackgroundService.EXTRA_ALLOW_DUPLICATES,
                options.allowDuplicates ?: false
            )
            putExtra(
                MunimBluetoothBackgroundService.EXTRA_SCAN_MODE,
                options.scanMode?.name ?: ScanMode.LOWPOWER.name
            )
            putExtra(
                MunimBluetoothBackgroundService.EXTRA_NOTIFICATION_CHANNEL_ID,
                options.androidNotificationChannelId
                    ?: MunimBluetoothBackgroundService.DEFAULT_CHANNEL_ID
            )
            putExtra(
                MunimBluetoothBackgroundService.EXTRA_NOTIFICATION_CHANNEL_NAME,
                options.androidNotificationChannelName
                    ?: MunimBluetoothBackgroundService.DEFAULT_CHANNEL_NAME
            )
            putExtra(
                MunimBluetoothBackgroundService.EXTRA_NOTIFICATION_TITLE,
                options.androidNotificationTitle
                    ?: MunimBluetoothBackgroundService.DEFAULT_NOTIFICATION_TITLE
            )
            putExtra(
                MunimBluetoothBackgroundService.EXTRA_NOTIFICATION_TEXT,
                options.androidNotificationText
                    ?: MunimBluetoothBackgroundService.DEFAULT_NOTIFICATION_TEXT
            )
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    override fun stopBackgroundSession() {
        val context = NitroModules.applicationContext ?: return
        val intent = Intent(context, MunimBluetoothBackgroundService::class.java).apply {
            action = MunimBluetoothBackgroundService.ACTION_STOP
        }
        context.startService(intent)
    }

    override fun onDeviceConnected(callback: (deviceId: String) -> Unit): () -> Unit {
        onDeviceConnectedCallback = callback
        return { onDeviceConnectedCallback = null }
    }

    override fun onDeviceDisconnected(callback: (deviceId: String) -> Unit): () -> Unit {
        onDeviceDisconnectedCallback = callback
        return { onDeviceDisconnectedCallback = null }
    }

    override fun onCharacteristicValueChanged(
        callback: (deviceId: String, serviceUUID: String, characteristicUUID: String, value: String) -> Unit
    ): () -> Unit {
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

    override fun notifyCharacteristic(
        serviceUUID: String,
        characteristicUUID: String,
        value: String
    ): Promise<Unit> {
        val server = gattServer
            ?: return Promise.rejected(IllegalStateException("GATT server not initialized"))

        val targetService = server.services
            ?.firstOrNull { it.uuid.toString().equals(serviceUUID, ignoreCase = true) }
            ?: return Promise.rejected(IllegalArgumentException("Service not found: $serviceUUID"))

        val characteristic = targetService.characteristics
            ?.firstOrNull { it.uuid.toString().equals(characteristicUUID, ignoreCase = true) }
            ?: return Promise.rejected(IllegalArgumentException("Characteristic not found: $characteristicUUID"))

        val data = value.toByteArray(Charsets.UTF_8)
        characteristic.value = data

        val connectedCentrals = bluetoothManager
            ?.getConnectedDevices(android.bluetooth.BluetoothProfile.GATT)
            ?: emptyList()

        if (connectedCentrals.isEmpty()) {
            return Promise.rejected(IllegalStateException("No connected centrals to notify"))
        }

        connectedCentrals.forEach { device ->
            server.notifyCharacteristicChanged(device, characteristic, false)
        }

        return Promise.resolved(Unit)
    }

    private fun restartAdvertising(delayMs: Long) {
        if (!ensureBluetoothPermissions("restart advertising")) {
            return
        }

        ensureBluetoothManager()
        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            Log.e(TAG, "Bluetooth is not enabled or not available")
            return
        }

        advertiseJob?.cancel()
        advertiseCallback?.let { callback ->
            advertiser?.stopAdvertising(callback)
        }

        advertiseJob = bluetoothScope.launch {
            if (delayMs > 0) {
                delay(delayMs)
            }

            advertiser = adapter.bluetoothLeAdvertiser
            val activeAdvertiser = advertiser
            if (activeAdvertiser == null) {
                Log.e(TAG, "Bluetooth LE advertiser is not available")
                return@launch
            }

            val dataBuilder = AdvertiseData.Builder()
            currentAdvertisingData?.let { processAdvertisingData(it, dataBuilder) }
            currentServiceUUIDs.forEach { uuid ->
                dataBuilder.addServiceUuid(ParcelUuid.fromString(uuid))
            }

            val settings = AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setConnectable(true)
                .setTimeout(0)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .build()

            advertiseCallback = object : AdvertiseCallback() {
                override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                    Log.i(TAG, "Advertising started successfully")
                }

                override fun onStartFailure(errorCode: Int) {
                    Log.e(TAG, "Advertising failed: $errorCode")
                }
            }

            activeAdvertiser.startAdvertising(settings, dataBuilder.build(), advertiseCallback)
        }
    }

    private fun buildGattServerCallback(): BluetoothGattServerCallback {
        return object : BluetoothGattServerCallback() {
            override fun onCharacteristicReadRequest(
                device: BluetoothDevice,
                requestId: Int,
                offset: Int,
                characteristic: BluetoothGattCharacteristic
            ) {
                gattServer?.sendResponse(
                    device,
                    requestId,
                    BluetoothGatt.GATT_SUCCESS,
                    offset,
                    characteristic.value
                )
            }

            override fun onCharacteristicWriteRequest(
                device: BluetoothDevice,
                requestId: Int,
                characteristic: BluetoothGattCharacteristic,
                preparedWrite: Boolean,
                responseNeeded: Boolean,
                offset: Int,
                value: ByteArray?
            ) {
                val data = value ?: byteArrayOf()
                characteristic.value = data

                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
                }

                // Fire callback so JS layer receives the write
                val text = android.util.Base64.encodeToString(data, android.util.Base64.NO_WRAP)
                onCharacteristicValueChangedCallback?.invoke(
                    device.address,
                    characteristic.service?.uuid?.toString() ?: "",
                    characteristic.uuid.toString(),
                    text
                )
                Log.d(TAG, "Peripheral received write from ${device.address} on ${characteristic.uuid}")
            }
        }
    }

    private fun createGattCallback(deviceId: String): BluetoothGattCallback {
        return object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS && newState != BluetoothProfile.STATE_CONNECTED) {
                    pendingConnections.remove(deviceId)?.reject(
                        IllegalStateException("Failed to connect to $deviceId (status=$status)")
                    )
                    connectedDevices.remove(deviceId)?.close()
                    return
                }

                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        connectedDevices[deviceId] = gatt
                        // No peripheral.delegate line here — that's Swift only
                        Log.d(TAG, "Connected to $deviceId — requesting MTU 512")
                        gatt.requestMtu(512)
                        // Don't resolve promise here — wait for onMtuChanged
                    }

                    BluetoothProfile.STATE_DISCONNECTED -> {
                        pendingConnections.remove(deviceId)?.reject(
                            IllegalStateException("Disconnected from $deviceId")
                        )
                        val staleGatt = connectedDevices.remove(deviceId)
                        bluetoothScope.launch {
                            delay(500)
                            staleGatt?.close()
                            Log.d(TAG, "GATT closed after disconnect for $deviceId")
                        }
                        rejectPendingOperationsForDevice(
                            deviceId,
                            IllegalStateException("Disconnected from $deviceId")
                        )
                        onDeviceDisconnectedCallback?.invoke(deviceId)
                    }
                }
            }

            override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
                Log.d(TAG, "MTU changed to $mtu for $deviceId (status=$status)")
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    // MTU negotiated — now safe to resolve connection
                    pendingConnections.remove(deviceId)?.resolve(Unit)
                    onDeviceConnectedCallback?.invoke(deviceId)
                } else {
                    // MTU negotiation failed — resolve anyway with default MTU
                    Log.w(TAG, "MTU negotiation failed for $deviceId, using default")
                    pendingConnections.remove(deviceId)?.resolve(Unit)
                    onDeviceConnectedCallback?.invoke(deviceId)
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                Log.d(TAG, "onServicesDiscovered: deviceId=$deviceId status=$status services=${gatt.services.size}")
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    val services = buildGattServices(gatt)
                    pendingServiceDiscoveries.remove(deviceId)?.resolve(services)
                } else {
                    pendingServiceDiscoveries.remove(deviceId)?.reject(
                        IllegalStateException("Failed to discover services for $deviceId (status=$status)")
                    )
                }
            }

            override fun onDescriptorWrite(
                gatt: BluetoothGatt,
                descriptor: BluetoothGattDescriptor,
                status: Int
            ) {
                val key = "${deviceId}|${descriptor.characteristic.uuid}"
                Log.d(TAG, "onDescriptorWrite: key=$key status=$status")
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    pendingDescriptorWrites.remove(key)?.resolve(Unit)
                } else {
                    pendingDescriptorWrites.remove(key)?.reject(
                        IllegalStateException("Descriptor write failed status=$status")
                    )
                }
            }

            override fun onCharacteristicRead(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int
            ) {
                val key = characteristicKey(
                    deviceId,
                    characteristic.service.uuid.toString(),
                    characteristic.uuid.toString()
                )
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    val value = buildCharacteristicValue(characteristic)
                    lastCharacteristicValues[key] = value
                    pendingReads.remove(key)?.resolve(value)
                    onCharacteristicValueChangedCallback?.invoke(
                        deviceId,
                        value.serviceUUID,
                        value.characteristicUUID,
                        value.value
                    )
                    // eventEmitter.emit(
                    //     "characteristicValueChanged",
                    //     mapOf(
                    //         "deviceId" to deviceId,
                    //         "serviceUUID" to value.serviceUUID,
                    //         "characteristicUUID" to value.characteristicUUID,
                    //         "value" to value.value
                    //     )
                    // )
                } else {
                    pendingReads.remove(key)?.reject(
                        IllegalStateException("Failed to read characteristic $key (status=$status)")
                    )
                }
            }

            override fun onCharacteristicWrite(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int
            ) {
                val key = characteristicKey(
                    deviceId,
                    characteristic.service.uuid.toString(),
                    characteristic.uuid.toString()
                )
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    pendingWrites.remove(key)?.resolve(Unit)
                } else {
                    pendingWrites.remove(key)?.reject(
                        IllegalStateException("Failed to write characteristic $key (status=$status)")
                    )
                }
            }

            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic
            ) {
                val value = buildCharacteristicValue(characteristic)
                val key = characteristicKey(deviceId, value.serviceUUID, value.characteristicUUID)
                lastCharacteristicValues[key] = value
                onCharacteristicValueChangedCallback?.invoke(
                    deviceId,
                    value.serviceUUID,
                    value.characteristicUUID,
                    value.value
                )
                // eventEmitter.emit(
                //     "characteristicValueChanged",
                //     mapOf(
                //         "deviceId" to deviceId,
                //         "serviceUUID" to value.serviceUUID,
                //         "characteristicUUID" to value.characteristicUUID,
                //         "value" to value.value
                //     )
                // )
            }

            override fun onReadRemoteRssi(gatt: BluetoothGatt, rssi: Int, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    val rssiValue = rssi.toDouble()
                    lastRssiValues[deviceId] = rssiValue
                    pendingRssiReads.remove(deviceId)?.resolve(rssiValue)
                } else {
                    pendingRssiReads.remove(deviceId)?.reject(
                        IllegalStateException("Failed to read RSSI for $deviceId (status=$status)")
                    )
                }
            }
        }
    }

    private fun rejectPendingOperationsForDevice(deviceId: String, error: Throwable) {
        pendingReads.keys
            .filter { it.startsWith("$deviceId|") }
            .forEach { key -> pendingReads.remove(key)?.reject(error) }
        pendingWrites.keys
            .filter { it.startsWith("$deviceId|") }
            .forEach { key -> pendingWrites.remove(key)?.reject(error) }
        pendingServiceDiscoveries.remove(deviceId)?.reject(error)
        pendingRssiReads.remove(deviceId)?.reject(error)
    }

    private fun buildGattServices(gatt: BluetoothGatt): Array<GATTService> {
        return gatt.services.map { service ->
            GATTService(
                uuid = service.uuid.toString(),
                characteristics = service.characteristics.map { characteristic ->
                    GATTCharacteristic(
                        uuid = characteristic.uuid.toString(),
                        properties = propertiesToArray(characteristic.properties),
                        value = characteristic.value?.let { android.util.Base64.encodeToString(it, android.util.Base64.NO_WRAP)}
                    )
                }.toTypedArray()
            )
        }.toTypedArray()
    }

    private fun buildCharacteristicValue(characteristic: BluetoothGattCharacteristic): CharacteristicValue {
        return CharacteristicValue(
            value = characteristic.value?.let { 
                android.util.Base64.encodeToString(it, android.util.Base64.NO_WRAP) 
            } ?: "",
            serviceUUID = characteristic.service.uuid.toString(),
            characteristicUUID = characteristic.uuid.toString()
        )
    }

    private fun findCharacteristic(
        gatt: BluetoothGatt,
        serviceUUID: String,
        characteristicUUID: String
    ): BluetoothGattCharacteristic? {
        val service = gatt.services.firstOrNull { it.uuid.toString().equals(serviceUUID, ignoreCase = true) }
            ?: return null
        return service.characteristics.firstOrNull {
            it.uuid.toString().equals(characteristicUUID, ignoreCase = true)
        }
    }

    private fun characteristicKey(
        deviceId: String,
        serviceUUID: String,
        characteristicUUID: String
    ): String {
        return "$deviceId|${serviceUUID.lowercase()}|${characteristicUUID.lowercase()}"
    }

    private fun extractManufacturerData(record: ScanRecord?): String? {
        val data = record?.manufacturerSpecificData ?: return null
        if (data.size() == 0) return null
        return data.valueAt(0)?.toHexString()
    }

    private fun extractServiceData(record: ScanRecord?): List<ServiceDataEntry>? {
        val data = record?.serviceData ?: return null
        return data.entries.mapNotNull { entry ->
            val value = entry.value ?: return@mapNotNull null
            ServiceDataEntry(entry.key.uuid.toString(), value.toHexString())
        }.takeIf { it.isNotEmpty() }
    }

    private fun processAdvertisingData(
        data: AdvertisingDataTypes,
        dataBuilder: AdvertiseData.Builder
    ) {
        addServiceUUIDs(data.incompleteServiceUUIDs16, dataBuilder)
        addServiceUUIDs(data.completeServiceUUIDs16, dataBuilder)
        addServiceUUIDs(data.incompleteServiceUUIDs32, dataBuilder)
        addServiceUUIDs(data.completeServiceUUIDs32, dataBuilder)
        addServiceUUIDs(data.incompleteServiceUUIDs128, dataBuilder)
        addServiceUUIDs(data.completeServiceUUIDs128, dataBuilder)

        if (data.shortenedLocalName != null || data.completeLocalName != null) {
            dataBuilder.setIncludeDeviceName(true)
        }
        if (data.txPowerLevel != null) {
            dataBuilder.setIncludeTxPowerLevel(true)
        }

        addServiceUUIDs(data.serviceSolicitationUUIDs16, dataBuilder)
        addServiceUUIDs(data.serviceSolicitationUUIDs32, dataBuilder)
        addServiceUUIDs(data.serviceSolicitationUUIDs128, dataBuilder)
        addServiceData(data.serviceData16, dataBuilder)
        addServiceData(data.serviceData32, dataBuilder)
        addServiceData(data.serviceData128, dataBuilder)

        data.appearance?.toInt()?.let { appearance ->
            val appearanceData = byteArrayOf(
                (appearance and 0xFF).toByte(),
                ((appearance shr 8) and 0xFF).toByte()
            )
            dataBuilder.addServiceData(
                ParcelUuid.fromString("00001800-0000-1000-8000-00805F9B34FB"),
                appearanceData
            )
        }

        data.manufacturerData?.let { manufacturerData ->
            hexStringToByteArray(manufacturerData)?.let { bytes ->
                dataBuilder.addManufacturerData(0x0000, bytes)
            }
        }
    }

    private fun normalizeAdvertisingData(
        advertisingData: AdvertisingDataTypes?,
        localName: String?,
        manufacturerData: String?
    ): AdvertisingDataTypes {
        val base = advertisingData ?: emptyAdvertisingData()
        return base.copy(
            completeLocalName = base.completeLocalName ?: localName,
            manufacturerData = base.manufacturerData ?: manufacturerData
        )
    }

    private fun emptyAdvertisingData(): AdvertisingDataTypes {
        return AdvertisingDataTypes(
            flags = null,
            incompleteServiceUUIDs16 = null,
            completeServiceUUIDs16 = null,
            incompleteServiceUUIDs32 = null,
            completeServiceUUIDs32 = null,
            incompleteServiceUUIDs128 = null,
            completeServiceUUIDs128 = null,
            shortenedLocalName = null,
            completeLocalName = null,
            txPowerLevel = null,
            serviceSolicitationUUIDs16 = null,
            serviceSolicitationUUIDs128 = null,
            serviceData16 = null,
            serviceData32 = null,
            serviceData128 = null,
            appearance = null,
            serviceSolicitationUUIDs32 = null,
            manufacturerData = null
        )
    }

    private fun propertiesFromArray(properties: Array<String>): Int {
        var result = 0
        properties.forEach { property ->
            when (property) {
                "read" -> result = result or BluetoothGattCharacteristic.PROPERTY_READ
                "write" -> result = result or BluetoothGattCharacteristic.PROPERTY_WRITE
                "notify" -> result = result or BluetoothGattCharacteristic.PROPERTY_NOTIFY
                "indicate" -> result = result or BluetoothGattCharacteristic.PROPERTY_INDICATE
                "writeWithoutResponse" -> {
                    result = result or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE
                }
            }
        }
        return result
    }

    private fun propertiesToArray(properties: Int): Array<String> {
        val result = mutableListOf<String>()
        if (properties and BluetoothGattCharacteristic.PROPERTY_READ != 0) result += "read"
        if (properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) result += "write"
        if (properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) result += "notify"
        if (properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) result += "indicate"
        if (properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) {
            result += "writeWithoutResponse"
        }
        return result.toTypedArray()
    }

    private fun addServiceUUIDs(uuids: Array<String>?, dataBuilder: AdvertiseData.Builder) {
        uuids?.forEach { uuid ->
            dataBuilder.addServiceUuid(ParcelUuid.fromString(uuid))
        }
    }

    private fun addServiceData(
        serviceDataEntries: Array<ServiceDataEntry>?,
        dataBuilder: AdvertiseData.Builder
    ) {
        serviceDataEntries?.forEach { entry ->
            hexStringToByteArray(entry.data)?.let { dataBytes ->
                dataBuilder.addServiceData(ParcelUuid.fromString(entry.uuid), dataBytes)
            }
        }
    }

    private fun hexStringToByteArray(hexString: String?): ByteArray? {
        if (hexString == null) return null

        val cleanHex = hexString.replace(" ", "")
        if (cleanHex.length % 2 != 0) return null

        return try {
            ByteArray(cleanHex.length / 2).also { bytes ->
                bytes.indices.forEach { index ->
                    val offset = index * 2
                    bytes[index] = cleanHex.substring(offset, offset + 2).toInt(16).toByte()
                }
            }
        } catch (_: NumberFormatException) {
            null
        }
    }

    private fun ByteArray.toHexString(): String {
        return joinToString("") { "%02x".format(it) }
    }

    private fun setServicesFromOptions(serviceUUIDs: Array<String>) {
        ensureBluetoothManager()
        gattServerReady = false

        val manager = bluetoothManager ?: return
        val context = NitroModules.applicationContext ?: return

        gattServer?.close()
        gattServer = manager.openGattServer(context, object : BluetoothGattServerCallback() {})
        gattServer?.clearServices()

        serviceUUIDs.forEach { uuid ->
            val service = BluetoothGattService(
                UUID.fromString(uuid),
                BluetoothGattService.SERVICE_TYPE_PRIMARY
            )
            gattServer?.addService(service)
        }

        gattServerReady = true
    }

    private fun restoreAdapterName() {
        val adapter = bluetoothAdapter ?: return
        val originalName = previousAdapterName ?: return
        try {
            adapter.name = originalName
        } catch (error: SecurityException) {
            Log.w(TAG, "Unable to restore Bluetooth adapter name", error)
        }
        previousAdapterName = null
    }

    private fun buildBLEDevice(result: ScanResult): BLEDevice {
        val record = result.scanRecord
        val manufacturerData = extractManufacturerData(record)
        val serviceUUIDs = record?.serviceUuids?.map { it.uuid.toString() }?.toTypedArray()
        val txPower = record?.txPowerLevel?.takeIf { it != Int.MIN_VALUE }?.toDouble()

        val advertisingData = AdvertisingDataTypes(
            flags = null,
            incompleteServiceUUIDs16 = null,
            completeServiceUUIDs16 = serviceUUIDs,
            incompleteServiceUUIDs32 = null,
            completeServiceUUIDs32 = null,
            incompleteServiceUUIDs128 = null,
            completeServiceUUIDs128 = null,
            shortenedLocalName = null,
            completeLocalName = record?.deviceName,
            txPowerLevel = txPower,
            serviceSolicitationUUIDs16 = null,
            serviceSolicitationUUIDs128 = null,
            serviceData16 = extractServiceData(record)?.toTypedArray(),
            serviceData32 = null,
            serviceData128 = null,
            appearance = null,
            serviceSolicitationUUIDs32 = null,
            manufacturerData = manufacturerData
        )

        return BLEDevice(
            id = result.device.address,
            name = result.device.name,
            rssi = result.rssi.toDouble(),
            advertisingData = advertisingData,
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

// private class NitroEventEmitter(private val tag: String) {
//     fun emit(eventName: String, payload: Map<String, Any?>) {
//         val context = NitroModules.applicationContext
//         if (context == null) {
//             Log.w(tag, "Unable to emit $eventName: React context unavailable")
//             return
//         }
        
//         val writable = Arguments.createMap()
//         payload.forEach { (key, value) ->
//             writeValue(writable, key, value)
//         }
        
//         context
//             .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
//             .emit(eventName, writable)
//     }
    
//     private fun writeValue(map: WritableMap, key: String, value: Any?) {
//         when (value) {
//             null -> map.putNull(key)
//             is String -> map.putString(key, value)
//             is Boolean -> map.putBoolean(key, value)
//             is Int -> map.putInt(key, value)
//             is Double -> map.putDouble(key, value)
//             is Float -> map.putDouble(key, value.toDouble())
//             is Long -> map.putDouble(key, value.toDouble())
//             is Map<*, *> -> map.putMap(key, convertMap(value))
//             is List<*> -> map.putArray(key, convertArray(value))
//             else -> map.putString(key, value.toString())
//         }
//     }
    
//     private fun convertMap(map: Map<*, *>): WritableMap {
//         val writable = Arguments.createMap()
//         map.forEach { (key, value) ->
//             if (key is String) {
//                 writeValue(writable, key, value)
//             }
//         }
//         return writable
//     }
    
//     private fun convertArray(list: List<*>): WritableArray {
//         val writable = Arguments.createArray()
//         list.forEach { value ->
//             when (value) {
//                 null -> writable.pushNull()
//                 is String -> writable.pushString(value)
//                 is Boolean -> writable.pushBoolean(value)
//                 is Int -> writable.pushInt(value)
//                 is Double -> writable.pushDouble(value)
//                 is Float -> writable.pushDouble(value.toDouble())
//                 is Long -> writable.pushDouble(value.toDouble())
//                 is Map<*, *> -> writable.pushMap(convertMap(value))
//                 is List<*> -> writable.pushArray(convertArray(value))
//                 else -> writable.pushString(value.toString())
//             }
//         }
//         return writable
//     }
// }
