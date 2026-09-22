package com.bluelink.app

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.bluelink.app.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity(), BluetoothChatService.Listener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: DeviceAdapter

    private val foundDevices = LinkedHashMap<String, DeviceItem>()
    private var isDiscovering = false

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        (getSystemService(Context.BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager).adapter
    }

    private val enableBtLauncher =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) {
            refreshPairedDevices()
        }

    private val permissionLauncher =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()) { results ->
            if (results.values.all { it }) {
                onPermissionsGranted()
            } else {
                android.widget.Toast.makeText(
                    this,
                    getString(R.string.permissions_required),
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
        }

    private val discoveryReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device: BluetoothDevice? =
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    device ?: return
                    if (device.bondState == BluetoothDevice.BOND_BONDED) return
                    val name = device.name ?: getString(R.string.unknown_device)
                    foundDevices[device.address] = DeviceItem(device, name, device.address, false)
                    refreshList()
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    isDiscovering = false
                    binding.buttonScan.text = getString(R.string.scan)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        adapter = DeviceAdapter { item -> onDeviceClicked(item) }
        binding.recyclerDevices.layoutManager = LinearLayoutManager(this)
        binding.recyclerDevices.adapter = adapter

        binding.buttonScan.setOnClickListener { toggleDiscovery() }
        binding.buttonClear.setOnClickListener { clearList() }

        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        registerReceiver(discoveryReceiver, filter)

        ensurePermissions()
    }

    override fun onResume() {
        super.onResume()
        BluetoothChatService.listener = this
        if (hasAllPermissions()) {
            if (BluetoothChatService.isConnected) {
                openChatScreen()
            } else {
                refreshPairedDevices()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(discoveryReceiver)
        } catch (_: IllegalArgumentException) {
        }
    }

    // ---------- Permissions ----------

    private fun requiredPermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_ADVERTISE
            )
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    private fun hasAllPermissions(): Boolean =
        requiredPermissions().all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

    private fun ensurePermissions() {
        if (hasAllPermissions()) {
            onPermissionsGranted()
        } else {
            permissionLauncher.launch(requiredPermissions())
        }
    }

    @SuppressLint("MissingPermission")
    private fun onPermissionsGranted() {
        if (bluetoothAdapter == null) {
            android.widget.Toast.makeText(this, getString(R.string.no_bluetooth), android.widget.Toast.LENGTH_LONG).show()
            return
        }
        if (bluetoothAdapter?.isEnabled == false) {
            enableBtLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
        }
        BluetoothChatService.startListening()
        refreshPairedDevices()
    }

    // ---------- Device list ----------

    @SuppressLint("MissingPermission")
    private fun refreshPairedDevices() {
        if (!hasAllPermissions()) return
        val paired = bluetoothAdapter?.bondedDevices ?: emptySet()
        paired.forEach { device ->
            foundDevices[device.address] =
                DeviceItem(device, device.name ?: device.address, device.address, true)
        }
        refreshList()
    }

    private fun refreshList() {
        adapter.submit(foundDevices.values.toList())
    }

    private fun clearList() {
        foundDevices.clear()
        refreshList()
        refreshPairedDevices()
    }

    @SuppressLint("MissingPermission")
    private fun toggleDiscovery() {
        if (!hasAllPermissions()) {
            ensurePermissions()
            return
        }
        if (isDiscovering) {
            bluetoothAdapter?.cancelDiscovery()
            isDiscovering = false
            binding.buttonScan.text = getString(R.string.scan)
        } else {
            bluetoothAdapter?.startDiscovery()
            isDiscovering = true
            binding.buttonScan.text = getString(R.string.scanning)
        }
    }

    @SuppressLint("MissingPermission")
    private fun onDeviceClicked(item: DeviceItem) {
        bluetoothAdapter?.cancelDiscovery()
        binding.textStatus.text = getString(R.string.connecting_to, item.name)
        BluetoothChatService.connectTo(item.device)
    }

    // ---------- BluetoothChatService.Listener ----------

    @SuppressLint("MissingPermission")
    override fun onIncomingRequest(device: BluetoothDevice) {
        val name = device.name ?: device.address
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.incoming_title))
            .setMessage(getString(R.string.incoming_message, name))
            .setPositiveButton("✅") { _, _ -> BluetoothChatService.acceptIncoming() }
            .setNegativeButton("❌") { _, _ -> BluetoothChatService.rejectIncoming() }
            .setCancelable(false)
            .show()
    }

    override fun onConnected(deviceName: String) {
        openChatScreen()
    }

    override fun onConnectionFailed() {
        binding.textStatus.text = getString(R.string.connection_failed)
    }

    override fun onDisconnected() {
        binding.textStatus.text = getString(R.string.disconnected)
    }

    private fun openChatScreen() {
        startActivity(Intent(this, ChatActivity::class.java))
    }
}
