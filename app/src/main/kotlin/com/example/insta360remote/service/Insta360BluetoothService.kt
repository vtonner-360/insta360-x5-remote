package com.example.insta360remote.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.example.insta360remote.R
import com.example.insta360remote.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import timber.log.Timber
import java.util.UUID

/**
 * Foreground service that owns the BLE connection to the camera so it
 * survives the Activity being backgrounded on the Karoo.
 *
 * IMPORTANT — read this before relying on the "send command" buttons:
 * The BLE service/characteristic UUIDs and command byte sequences below
 * are PLACEHOLDERS carried over from an earlier community project and
 * are NOT confirmed to match the Insta360 X5's real protocol. Insta360
 * does not publish a public BLE spec for third-party remote control.
 *
 * What IS expected to work out of the box:
 *  - Scanning for the camera and connecting to it (standard Android BLE,
 *    protocol-independent)
 *  - Discovering whatever GATT services/characteristics the camera
 *    actually exposes (useful for reverse-engineering the real protocol)
 *
 * What is NOT guaranteed to work until the real protocol is confirmed:
 *  - REC / PHOTO / HIGHLIGHT actually triggering the camera
 *
 * See the project README for how to capture the real protocol via an
 * Android Bluetooth HCI snoop log while using the official Insta360 app.
 */
class Insta360BluetoothService : Service() {

    private val binder = LocalBinder()
    private var bluetoothGatt: BluetoothGatt? = null
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothLeScanner: BluetoothLeScanner? = null
    private var isScanning = false
    private var currentNameFilter: String = DEFAULT_NAME_FILTER
    private val scanHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.IO + Job())

    private var connectionCallback: ((Boolean) -> Unit)? = null
    private var stateCallback: ((CameraState) -> Unit)? = null
    private var batteryCallback: ((Int) -> Unit)? = null

    companion object {
        private const val CHANNEL_ID = "insta360_ble_service"
        private const val NOTIFICATION_ID = 1001
        private const val SCAN_TIMEOUT_MS = 15000L
        const val DEFAULT_NAME_FILTER = "Insta360"

        // PLACEHOLDER UUIDs — replace once the real protocol is confirmed.
        private val SERVICE_UUID = UUID.fromString("000000ff-0000-1000-8000-00805f9b34fb")
        private val COMMAND_CHAR_UUID = UUID.fromString("00000002-0000-1000-8000-00805f9b34fb")
        private val STATE_CHAR_UUID = UUID.fromString("00000003-0000-1000-8000-00805f9b34fb")
        private val BATTERY_CHAR_UUID = UUID.fromString("00000004-0000-1000-8000-00805f9b34fb")
        private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        // PLACEHOLDER commands — replace once the real protocol is confirmed.
        val CMD_START_RECORDING = byteArrayOf(0xFF.toByte(), 0x01, 0x05, 0x00, 0x01)
        val CMD_STOP_RECORDING = byteArrayOf(0xFF.toByte(), 0x01, 0x05, 0x00, 0x00)
        val CMD_TAKE_PHOTO = byteArrayOf(0xFF.toByte(), 0x01, 0x03, 0x00, 0x01)
        val CMD_MARK_HIGHLIGHT = byteArrayOf(0xFF.toByte(), 0x01, 0x08, 0x00, 0x01)
    }

    enum class CameraState {
        DISCONNECTED, CONNECTING, CONNECTED, RECORDING, STANDBY, ERROR
    }

    override fun onCreate() {
        super.onCreate()
        Timber.d("BLE service created")
        createNotificationChannel()
        initializeBluetooth()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, createNotification())
        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Insta360 Camera Control",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps the BLE connection to the camera alive"
                setShowBadge(false)
            }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Insta360 Remote active")
            .setContentText("BLE connection running")
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun initializeBluetooth() {
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        bluetoothAdapter = bluetoothManager?.adapter
        if (bluetoothAdapter == null) {
            Timber.e("Bluetooth is not available on this device")
            stateCallback?.invoke(CameraState.ERROR)
        }
    }

    /**
     * Scan for a nearby BLE device whose advertised name contains
     * [nameFilter] (default "Insta360") and connect to the first match.
     * No MAC address needed.
     */
    fun connectToCameraByName(nameFilter: String = DEFAULT_NAME_FILTER) {
        if (isScanning) {
            Timber.d("Scan already in progress")
            return
        }

        Timber.d("Scanning for BLE device matching name: $nameFilter")
        stateCallback?.invoke(CameraState.CONNECTING)
        currentNameFilter = nameFilter

        val scanner = bluetoothAdapter?.bluetoothLeScanner
        if (scanner == null) {
            Timber.e("Could not get BLE scanner (is Bluetooth on?)")
            stateCallback?.invoke(CameraState.ERROR)
            return
        }
        bluetoothLeScanner = scanner

        try {
            isScanning = true
            val scanSettings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()

            scanner.startScan(null, scanSettings, scanCallback)

            scanHandler.postDelayed({
                if (isScanning) {
                    stopScan()
                    Timber.w("Scan timed out: no camera matching '$nameFilter' found")
                    stateCallback?.invoke(CameraState.ERROR)
                }
            }, SCAN_TIMEOUT_MS)
        } catch (e: SecurityException) {
            Timber.e(e, "Missing permission to start BLE scan")
            isScanning = false
            stateCallback?.invoke(CameraState.ERROR)
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val deviceName = try {
                result.device.name
            } catch (e: SecurityException) {
                null
            }

            if (!deviceName.isNullOrBlank() && deviceName.contains(currentNameFilter, ignoreCase = true)) {
                Timber.d("Found camera: $deviceName (${result.device.address})")
                stopScan()
                connectToCamera(result.device.address)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Timber.e("BLE scan failed, code: $errorCode")
            isScanning = false
            stateCallback?.invoke(CameraState.ERROR)
        }
    }

    private fun stopScan() {
        if (isScanning) {
            try {
                bluetoothLeScanner?.stopScan(scanCallback)
            } catch (e: SecurityException) {
                Timber.e(e, "Error stopping scan")
            }
            isScanning = false
            scanHandler.removeCallbacksAndMessages(null)
        }
    }

    /** Connect directly by MAC address, if you already know it. */
    fun connectToCamera(macAddress: String) {
        Timber.d("Connecting to camera: $macAddress")
        stateCallback?.invoke(CameraState.CONNECTING)

        try {
            val device = bluetoothAdapter?.getRemoteDevice(macAddress)
            if (device != null) {
                bluetoothGatt = device.connectGatt(
                    this, false, gattCallback,
                    android.bluetooth.BluetoothDevice.TRANSPORT_LE
                )
            } else {
                Timber.e("Could not resolve remote device")
                stateCallback?.invoke(CameraState.ERROR)
            }
        } catch (e: SecurityException) {
            Timber.e(e, "Missing permission for connectGatt")
            stateCallback?.invoke(CameraState.ERROR)
        }
    }

    fun disconnect() {
        stopScan()
        try {
            bluetoothGatt?.disconnect()
            bluetoothGatt?.close()
            bluetoothGatt = null
            stateCallback?.invoke(CameraState.DISCONNECTED)
        } catch (e: SecurityException) {
            Timber.e(e, "Error disconnecting")
        }
    }

    fun sendCommand(command: ByteArray) {
        val gatt = bluetoothGatt ?: run {
            Timber.w("GATT not available")
            return
        }
        try {
            val characteristic = gatt.getService(SERVICE_UUID)?.getCharacteristic(COMMAND_CHAR_UUID)
            if (characteristic != null) {
                characteristic.value = command
                characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                gatt.writeCharacteristic(characteristic)
                Timber.d("Command sent: ${command.joinToString(",") { "%02X".format(it) }}")
            } else {
                Timber.e("Command characteristic not found on this device")
            }
        } catch (e: SecurityException) {
            Timber.e(e, "Missing permission to write characteristic")
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                android.bluetooth.BluetoothProfile.STATE_CONNECTED -> {
                    try {
                        gatt.discoverServices()
                    } catch (e: SecurityException) {
                        Timber.e(e, "Error discovering services")
                    }
                }
                android.bluetooth.BluetoothProfile.STATE_DISCONNECTED -> {
                    stateCallback?.invoke(CameraState.DISCONNECTED)
                    connectionCallback?.invoke(false)
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                // Log every discovered service/characteristic UUID — this is the
                // key data needed to work out the real protocol.
                for (service in gatt.services) {
                    Timber.d("Discovered service: ${service.uuid}")
                    for (char in service.characteristics) {
                        Timber.d("  characteristic: ${char.uuid} (props=${char.properties})")
                    }
                }

                try {
                    enableNotifications(gatt, STATE_CHAR_UUID)
                    enableNotifications(gatt, BATTERY_CHAR_UUID)
                } catch (e: SecurityException) {
                    Timber.e(e, "Error enabling notifications")
                }

                stateCallback?.invoke(CameraState.CONNECTED)
                connectionCallback?.invoke(true)
            } else {
                Timber.e("Service discovery failed: $status")
                stateCallback?.invoke(CameraState.ERROR)
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            when (characteristic.uuid) {
                BATTERY_CHAR_UUID -> batteryCallback?.invoke(characteristic.value?.getOrNull(0)?.toInt() ?: 0)
                STATE_CHAR_UUID -> stateCallback?.invoke(parseStateCharacteristic(characteristic.value))
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Timber.e("Error writing characteristic: $status")
            }
        }
    }

    private fun enableNotifications(gatt: BluetoothGatt, characteristicUuid: UUID) {
        val characteristic = gatt.getService(SERVICE_UUID)?.getCharacteristic(characteristicUuid) ?: return
        gatt.setCharacteristicNotification(characteristic, true)
        val descriptor = characteristic.getDescriptor(CCCD_UUID) ?: return
        descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        gatt.writeDescriptor(descriptor)
    }

    private fun parseStateCharacteristic(data: ByteArray?): CameraState {
        if (data == null || data.isEmpty()) return CameraState.DISCONNECTED
        return when (data[0].toInt()) {
            0x01 -> CameraState.RECORDING
            else -> CameraState.STANDBY
        }
    }

    fun setConnectionCallback(callback: (Boolean) -> Unit) { connectionCallback = callback }
    fun setStateCallback(callback: (CameraState) -> Unit) { stateCallback = callback }
    fun setBatteryCallback(callback: (Int) -> Unit) { batteryCallback = callback }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onDestroy() {
        stopScan()
        disconnect()
        scope.cancel()
        super.onDestroy()
    }

    inner class LocalBinder : Binder() {
        fun getService(): Insta360BluetoothService = this@Insta360BluetoothService
    }
}
