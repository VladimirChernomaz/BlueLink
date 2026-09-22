package com.bluelink.app

import android.bluetooth.BluetoothDevice

data class DeviceItem(
    val device: BluetoothDevice,
    val name: String,
    val address: String,
    val paired: Boolean
)
