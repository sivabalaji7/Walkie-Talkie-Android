package com.example.walkietalkieapp

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager as SystemBluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.walkietalkieapp.audio.AudioPlayer
import com.example.walkietalkieapp.audio.AudioRecorder
import com.example.walkietalkieapp.bluetooth.BluetoothManager

class MainActivity : AppCompatActivity() {
    companion object {
        private const val VOICE_GATE_THRESHOLD = 1400.0
        private const val REMOTE_AUDIO_SUPPRESSION_MS = 300L
        private const val TRANSMIT_HANGOVER_MS = 220L
    }

    private val recorder = AudioRecorder()
    private val player = AudioPlayer()
    private lateinit var bluetoothAdapter: BluetoothAdapter
    private lateinit var btManager: BluetoothManager
    private lateinit var statusText: TextView
    private lateinit var enableBluetoothButton: Button
    private lateinit var discoverableButton: Button
    private lateinit var scanButton: Button
    private lateinit var pttButton: Button
    private lateinit var devicesListView: ListView
    private lateinit var devicesAdapter: ArrayAdapter<String>

    private val devices = linkedMapOf<String, BluetoothDevice>()
    private var pendingConnectDevice: BluetoothDevice? = null
    private var receiverRegistered = false
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile
    private var isLocallyTransmitting = false
    @Volatile
    private var lastRemoteAudioAt = 0L
    @Volatile
    private var localTransmitHoldUntil = 0L

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            if (results.values.all { it }) {
                initializeBluetooth()
            } else {
                updateStatus("Permissions are required for Bluetooth voice and scanning.")
                showToast("Grant microphone and Bluetooth permissions to continue.")
            }
        }

    private val enableBluetoothLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (bluetoothAdapter.isEnabled) {
                initializeBluetooth()
            } else {
                updateStatus("Bluetooth is off. Turn it on to connect devices.")
            }
        }

    private val discoverableLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (bluetoothAdapter.scanMode == BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE) {
                updateStatus("This phone is visible. Use the other phone to scan and pair.")
            }
        }

    private val discoveryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device = extractBluetoothDevice(intent)
                    if (device != null) {
                        addOrUpdateDevice(device)

                    }
                }

                BluetoothAdapter.ACTION_DISCOVERY_STARTED -> {
                    updateStatus("Scanning for nearby Bluetooth devices...")
                }

                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    if (!btManager.isConnected()) {
                        updateStatus("Scan finished. Tap a device to pair or connect.")
                    }
                }

                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                    val device = extractBluetoothDevice(intent)
                    val state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)
                    if (device != null) {
                        addOrUpdateDevice(device)
                        if (state == BluetoothDevice.BOND_BONDED && device.address == pendingConnectDevice?.address) {
                            bluetoothAdapter.cancelDiscovery()
                            mainHandler.postDelayed({
                                if (pendingConnectDevice?.address == device.address) {
                                    pendingConnectDevice = null
                                    connectToDevice(device)
                                }
                            }, 1000)
                        }
                    }
                }
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        enableBluetoothButton = findViewById(R.id.enableBluetoothButton)
        discoverableButton = findViewById(R.id.discoverableButton)
        scanButton = findViewById(R.id.scanButton)
        pttButton = findViewById(R.id.pttButton)
        devicesListView = findViewById(R.id.devicesListView)

        bluetoothAdapter = getSystemService(SystemBluetoothManager::class.java)?.adapter
            ?: run {
                updateStatus("Bluetooth is not supported on this device.")
                showToast("Bluetooth is not supported on this phone.")
                return
            }

        btManager = BluetoothManager(
            onConnected = { device, incoming ->
                runOnUiThread {
                    bluetoothAdapter.cancelDiscovery()
                    pendingConnectDevice = null
                    player.start()
                    updatePttEnabled(true)
                    addOrUpdateDevice(device)
                    val role = if (incoming) "Host" else "Client"
                    updateStatus("$role channel active with ${deviceLabel(device)}.")
                    showToast("Connected to ${deviceLabel(device)}")
                }
            },
            onDisconnected = {
                runOnUiThread {
                    updatePttEnabled(false)
                    recorder.stop()
                    updateStatus("Connection closed. The phone is listening for a new device.")
                }
            },
            onAudioReceived = { data ->
                lastRemoteAudioAt = android.os.SystemClock.elapsedRealtime()
                player.play(data)
            },
            onError = { message ->
                runOnUiThread {
                    updateStatus(message)
                    showToast(message)
                }
            }
        )

        devicesAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, mutableListOf())
        devicesListView.adapter = devicesAdapter
        registerDiscoveryReceiver()

        enableBluetoothButton.setOnClickListener {
            requestBluetoothEnable()
        }

        discoverableButton.setOnClickListener {
            if (ensurePermissions()) {
                requestDiscoverableMode()
            }
        }

        scanButton.setOnClickListener {
            if (ensurePermissions()) {
                startDiscovery()
            }
        }

        devicesListView.setOnItemClickListener { _, _, position, _ ->
            val device = devices.values.elementAtOrNull(position) ?: return@setOnItemClickListener
            if (!ensurePermissions()) {
                return@setOnItemClickListener
            }

            if (device.bondState == BluetoothDevice.BOND_BONDED) {
                bluetoothAdapter.cancelDiscovery()
                connectToDevice(device)
            } else {
                pendingConnectDevice = device
                bluetoothAdapter.cancelDiscovery()
                updateStatus("Pairing with ${deviceLabel(device)}...")
                device.createBond()
            }
        }

        pttButton.setOnTouchListener { _, event ->
            if (!btManager.isConnected()) {
                showToast("Connect to another phone first.")
                return@setOnTouchListener false
            }

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    pttButton.text = "Talking..."
                    recorder.start { data ->
                        val shouldTransmit = shouldTransmitFrame(data)
                        setLocalTransmitState(shouldTransmit)
                        if (shouldTransmit) {
                            btManager.send(data)
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    pttButton.text = "Hold To Talk"
                    recorder.stop()
                    setLocalTransmitState(false)
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    pttButton.text = "Hold To Talk"
                    recorder.stop()
                    setLocalTransmitState(false)
                    true
                }
                else -> false
            }
        }

        updatePttEnabled(false)

        if (ensurePermissions()) {
            initializeBluetooth()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacksAndMessages(null)
        if (receiverRegistered) {
            unregisterReceiver(discoveryReceiver)
        }
        if (::bluetoothAdapter.isInitialized) {
            bluetoothAdapter.cancelDiscovery()
        }
        if (::btManager.isInitialized) {
            btManager.stop()
        }
        recorder.release()
        player.release()
    }

    private fun ensurePermissions(): Boolean {
        val missingPermissions = requiredPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        return if (missingPermissions.isEmpty()) {
            true
        } else {
            permissionLauncher.launch(missingPermissions.toTypedArray())
            false
        }
    }

    private fun requiredPermissions(): List<String> {
        val permissions = mutableListOf(android.Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions += listOf(
                android.Manifest.permission.BLUETOOTH_CONNECT,
                android.Manifest.permission.BLUETOOTH_SCAN
            )
        } else {
            permissions += listOf(
                android.Manifest.permission.BLUETOOTH,
                android.Manifest.permission.BLUETOOTH_ADMIN,
                android.Manifest.permission.ACCESS_FINE_LOCATION
            )
        }
        return permissions
    }

    private fun initializeBluetooth() {
        if (!bluetoothAdapter.isEnabled) {
            updateStatus("Bluetooth is off. Tap Enable Bluetooth.")
            return
        }

        loadBondedDevices()
        btManager.startServer(bluetoothAdapter)
        updateStatus("Ready. Make one phone visible, scan from the other phone, then tap a device to pair.")
    }

    private fun loadBondedDevices() {
        devices.clear()
        bluetoothAdapter.bondedDevices.orEmpty().forEach { addOrUpdateDevice(it) }
        rebuildDeviceList()
    }

    private fun requestBluetoothEnable() {
        enableBluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
    }

    private fun requestDiscoverableMode() {
        val discoverableIntent = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).apply {
            putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300)
        }
        discoverableLauncher.launch(discoverableIntent)
    }

    @SuppressLint("MissingPermission")
    private fun startDiscovery() {
        if (!bluetoothAdapter.isEnabled) {
            requestBluetoothEnable()
            return
        }

        loadBondedDevices()
        bluetoothAdapter.cancelDiscovery()
        val started = bluetoothAdapter.startDiscovery()
        if (!started) {
            updateStatus("Bluetooth scan could not start on this phone.")
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectToDevice(device: BluetoothDevice) {
        updatePttEnabled(false)
        updateStatus("Connecting to ${deviceLabel(device)}...")
        btManager.connect(bluetoothAdapter, device)
    }

    @SuppressLint("MissingPermission")
    private fun addOrUpdateDevice(device: BluetoothDevice) {
        devices[device.address] = device
        rebuildDeviceList()
    }

    @SuppressLint("MissingPermission")
    private fun rebuildDeviceList() {
        val labels = devices.values.map { device ->
            val name = deviceLabel(device)
            val state = when (device.bondState) {
                BluetoothDevice.BOND_BONDED -> "paired"
                BluetoothDevice.BOND_BONDING -> "pairing"
                else -> "not paired"
            }
            "$name\n${device.address} • $state"
        }

        devicesAdapter.clear()
        devicesAdapter.addAll(labels)
        devicesAdapter.notifyDataSetChanged()
    }

    private fun updatePttEnabled(enabled: Boolean) {
        pttButton.isEnabled = enabled
        pttButton.alpha = if (enabled) 1f else 0.5f
        if (!enabled) {
            pttButton.text = "Hold To Talk"
        }
    }

    @SuppressLint("MissingPermission")
    private fun deviceLabel(device: BluetoothDevice): String {
        return device.name?.takeIf { it.isNotBlank() } ?: "Unknown device"
    }

    private fun registerDiscoveryReceiver() {
        if (receiverRegistered) {
            return
        }

        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(discoveryReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(discoveryReceiver, filter)
        }
        receiverRegistered = true
    }

    private fun extractBluetoothDevice(intent: Intent): BluetoothDevice? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }
    }

    private fun updateStatus(message: String) {
        statusText.text = message
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun shouldTransmitFrame(data: ByteArray): Boolean {
        if (data.size < 2) {
            return false
        }

        var sumSquares = 0.0
        var samples = 0
        var index = 0
        while (index + 1 < data.size) {
            val sample = ((data[index + 1].toInt() shl 8) or (data[index].toInt() and 0xFF)).toShort()
            sumSquares += sample.toDouble() * sample.toDouble()
            samples++
            index += 2
        }

        if (samples == 0) {
            return false
        }

        val rms = kotlin.math.sqrt(sumSquares / samples)
        val now = android.os.SystemClock.elapsedRealtime()

        if (isLocallyTransmitting) {
            if (rms >= VOICE_GATE_THRESHOLD * 0.55) {
                localTransmitHoldUntil = now + TRANSMIT_HANGOVER_MS
                return true
            }
            return now <= localTransmitHoldUntil
        }

        val remoteAudioIsActive = now - lastRemoteAudioAt <= REMOTE_AUDIO_SUPPRESSION_MS
        if (remoteAudioIsActive) {
            return false
        }

        val shouldStartTransmit = rms >= VOICE_GATE_THRESHOLD
        if (shouldStartTransmit) {
            localTransmitHoldUntil = now + TRANSMIT_HANGOVER_MS
        }
        return shouldStartTransmit
    }

    private fun setLocalTransmitState(active: Boolean) {
        if (isLocallyTransmitting == active) {
            return
        }

        isLocallyTransmitting = active
        if (!active) {
            localTransmitHoldUntil = 0L
        }
        player.setMuted(active)
    }
}
