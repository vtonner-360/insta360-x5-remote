package com.example.insta360remote.ui

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.insta360remote.R
import com.example.insta360remote.service.Insta360BluetoothService
import timber.log.Timber

/**
 * Main screen: connect to the camera and send basic remote commands.
 */
class MainActivity : AppCompatActivity() {

    private var bluetoothService: Insta360BluetoothService? = null
    private var isBound = false
    private var isRecording = false

    private lateinit var statusText: TextView
    private lateinit var batteryText: TextView

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            Timber.d("BLE service connected")
            val binder = service as Insta360BluetoothService.LocalBinder
            bluetoothService = binder.getService()
            isBound = true

            bluetoothService?.setConnectionCallback { connected -> updateConnectionStatus(connected) }
            bluetoothService?.setStateCallback { state -> updateCameraState(state) }
            bluetoothService?.setBatteryCallback { battery -> updateBatteryLevel(battery) }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            bluetoothService = null
            isBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        Timber.plant(Timber.DebugTree())

        initializeViews()
        requestRequiredPermissions()
        bindBluetoothService()
        startBluetoothService()
    }

    private fun initializeViews() {
        statusText = findViewById(R.id.tv_status)
        batteryText = findViewById(R.id.tv_battery)

        findViewById<Button>(R.id.btn_connect).setOnClickListener { connectToCamera() }
        findViewById<Button>(R.id.btn_record).setOnClickListener { toggleRecording() }
        findViewById<Button>(R.id.btn_photo).setOnClickListener { takePhoto() }
        findViewById<Button>(R.id.btn_highlight).setOnClickListener { markHighlight() }
    }

    private fun requestRequiredPermissions() {
        val required = mutableListOf(
            Manifest.permission.BLUETOOTH,
            Manifest.permission.BLUETOOTH_ADMIN
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            required.add(Manifest.permission.BLUETOOTH_SCAN)
            required.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            required.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val missing = required.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), PERMISSION_REQUEST_CODE)
        }
    }

    private fun bindBluetoothService() {
        bindService(Intent(this, Insta360BluetoothService::class.java), serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun startBluetoothService() {
        val intent = Intent(this, Insta360BluetoothService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
    }

    private fun connectToCamera() {
        bluetoothService?.connectToCameraByName("X5")
        Toast.makeText(this, R.string.toast_scanning, Toast.LENGTH_SHORT).show()
    }

    private fun toggleRecording() {
        bluetoothService?.apply {
            if (isRecording) {
                sendCommand(Insta360BluetoothService.CMD_STOP_RECORDING)
            } else {
                sendCommand(Insta360BluetoothService.CMD_START_RECORDING)
            }
            isRecording = !isRecording
        }
    }

    private fun takePhoto() {
        bluetoothService?.sendCommand(Insta360BluetoothService.CMD_TAKE_PHOTO)
        Toast.makeText(this, R.string.toast_photo_taken, Toast.LENGTH_SHORT).show()
    }

    private fun markHighlight() {
        bluetoothService?.sendCommand(Insta360BluetoothService.CMD_MARK_HIGHLIGHT)
        Toast.makeText(this, R.string.toast_highlight_marked, Toast.LENGTH_SHORT).show()
    }

    private fun updateConnectionStatus(isConnected: Boolean) {
        runOnUiThread {
            if (isConnected) {
                statusText.text = getString(R.string.status_connected)
                statusText.setTextColor(ContextCompat.getColor(this, R.color.green))
            } else {
                statusText.text = getString(R.string.status_disconnected)
                statusText.setTextColor(ContextCompat.getColor(this, R.color.red))
            }
        }
    }

    private fun updateCameraState(state: Insta360BluetoothService.CameraState) {
        runOnUiThread {
            statusText.text = when (state) {
                Insta360BluetoothService.CameraState.RECORDING -> "Status: Recording"
                Insta360BluetoothService.CameraState.STANDBY -> "Status: Standby"
                Insta360BluetoothService.CameraState.CONNECTED -> getString(R.string.status_connected)
                Insta360BluetoothService.CameraState.CONNECTING -> getString(R.string.status_connecting)
                Insta360BluetoothService.CameraState.DISCONNECTED -> getString(R.string.status_disconnected)
                Insta360BluetoothService.CameraState.ERROR -> getString(R.string.status_error)
            }
        }
    }

    private fun updateBatteryLevel(battery: Int) {
        runOnUiThread { batteryText.text = "Battery: $battery%" }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
    }

    companion object {
        private const val PERMISSION_REQUEST_CODE = 1001
    }
}
