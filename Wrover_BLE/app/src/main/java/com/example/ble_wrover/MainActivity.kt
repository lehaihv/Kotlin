package com.example.ble_wrover

import android.Manifest
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.activity.enableEdgeToEdge
import androidx.annotation.RequiresPermission
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import com.example.ble_wrover.ui.theme.BLE_WroverTheme
import kotlinx.coroutines.*
import java.util.*

class MainActivity : ComponentActivity() {
    private lateinit var bluetoothAdapter: BluetoothAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val bluetoothManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter

        setContent {
            BLE_WroverTheme {
                BleScannerScreen(bluetoothAdapter)
            }
        }
    }
}

fun runOnMainThread(action: () -> Unit) {
    Handler(Looper.getMainLooper()).post {
        action()
    }
}

@Composable
fun BleScannerScreen(bluetoothAdapter: BluetoothAdapter) {
    val context = LocalContext.current
    val devices = remember { mutableStateListOf<String>() }
    var selectedDevice by remember { mutableStateOf<String?>(null) }
    var logText by remember { mutableStateOf(TextFieldValue("")) }
    var scanJob by remember { mutableStateOf<Job?>(null) }
    var bluetoothGatt by remember { mutableStateOf<BluetoothGatt?>(null) }

    fun appendLog(text: String) {
        logText = TextFieldValue(logText.text + "\n" + text)
    }

    val permissions = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        } else {
            listOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = RequestMultiplePermissions()
    ) { perms ->
        val granted = perms.entries.all { it.value }
        if (!granted) {
            appendLog("Permissions denied. BLE scanning and connecting will not work.")
        }
    }

    val enableBluetoothLauncher = rememberLauncherForActivityResult(
        contract = StartActivityForResult()
    ) {
        if (!bluetoothAdapter.isEnabled) {
            appendLog("Bluetooth is still disabled.")
        }
    }

    val scanCallback = remember(bluetoothAdapter) {
        object : ScanCallback() {
            @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = result.device.name ?: "Unnamed"
                val address = result.device.address
                val deviceInfo = "$name ($address)"
                if (deviceInfo !in devices) {
                    devices.add(deviceInfo)
                }
            }
        }
    }

    fun startScan() {
        val missing = permissions.filter {
            ActivityCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
            return
        }

        if (!bluetoothAdapter.isEnabled) {
            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            enableBluetoothLauncher.launch(enableBtIntent)
            return
        }

        try {
            devices.clear()
            selectedDevice = null
            bluetoothAdapter.bluetoothLeScanner?.startScan(scanCallback)
            scanJob?.cancel()

            scanJob = CoroutineScope(Dispatchers.Main).launch {
                delay(15 * 60 * 1000L)
                bluetoothAdapter.bluetoothLeScanner?.stopScan(scanCallback)
                appendLog("Scan stopped after 15 minutes.")
            }
            appendLog("Scan started...")
        } catch (e: SecurityException) {
            appendLog("Failed to start scan: ${e.message}")
        }
    }

    fun stopScan() {
        scanJob?.cancel()
        bluetoothAdapter.bluetoothLeScanner?.stopScan(scanCallback)
        appendLog("Scan stopped.")
    }

    fun connectToDevice(deviceInfo: String) {
        val required = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                required.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
        }

        if (required.isNotEmpty()) {
            permissionLauncher.launch(required.toTypedArray())
            appendLog("Missing BLUETOOTH_CONNECT permission.")
            return
        }

        stopScan()
        appendLog("Connecting to $deviceInfo ...")
        val macAddress = deviceInfo.substringAfterLast('(').removeSuffix(")").trim()
        val device = bluetoothAdapter.getRemoteDevice(macAddress)
        bluetoothGatt?.close()
        bluetoothGatt = device.connectGatt(context, false, MyGattCallback(context, deviceInfo, ::appendLog))
    }

    BleScannerUI(
        devices = devices,
        selectedDevice = selectedDevice,
        onDeviceSelected = { selectedDevice = it },
        logText = logText,
        onLogTextChange = { logText = it },
        onScanRequest = { startScan() },
        onConnectClick = { selectedDevice?.let { connectToDevice(it) } }
    )
}

class MyGattCallback(
    private val context: Context,
    private val deviceInfo: String,
    private val appendLog: (String) -> Unit
) : BluetoothGattCallback() {

    override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
        runOnMainThread {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                appendLog("Connected to $deviceInfo")
                if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                    gatt.discoverServices()
                } else {
                    appendLog("Missing BLUETOOTH_CONNECT permission, cannot discover services")
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                appendLog("Disconnected from $deviceInfo")
            }
        }
    }

    override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
        runOnMainThread {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                appendLog("Services discovered:")
                gatt.services.forEach { service ->
                    appendLog("Service UUID: ${service.uuid}")
                    service.characteristics.forEach { characteristic ->
                        appendLog(" - Characteristic UUID: ${characteristic.uuid}")
                        if ((characteristic.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0) {
                            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                                gatt.setCharacteristicNotification(characteristic, true)
                                val cccdUuid = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
                                val descriptor = characteristic.getDescriptor(cccdUuid)
                                if (descriptor != null) {
                                    descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                                    try {
                                        gatt.writeDescriptor(descriptor)
                                        appendLog("Enabled notifications for ${characteristic.uuid}")
                                    } catch (e: SecurityException) {
                                        appendLog("Failed to write descriptor for ${characteristic.uuid}: ${e.message}")
                                    }
                                } else {
                                    appendLog("CCC Descriptor not found for ${characteristic.uuid}")
                                }
                            } else {
                                appendLog("Missing BLUETOOTH_CONNECT permission. Cannot enable notifications for ${characteristic.uuid}")
                            }
                        }
                    }
                }
            } else {
                appendLog("Service discovery failed with status $status")
            }
        }
    }

    @Deprecated("Deprecated in Android 13")
    override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        runOnMainThread {
            val data = characteristic.value
            val stringText = data?.toString(Charsets.UTF_8)?.filter { it.isLetterOrDigit() || it.isWhitespace() || it.isLetter() } ?: ""
            appendLog("Notification from ${characteristic.uuid}: $stringText")
        }
    }

    override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
        runOnMainThread {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val data = characteristic.value
                val stringText = data?.toString(Charsets.UTF_8)?.filter { it.isLetterOrDigit() || it.isWhitespace() || it.isLetter() } ?: ""
                appendLog("Read from ${characteristic.uuid}: $stringText")
            }
        }
    }
}

@Composable
fun BleScannerUI(
    modifier: Modifier = Modifier,
    devices: List<String>,
    selectedDevice: String?,
    onDeviceSelected: (String) -> Unit,
    logText: TextFieldValue,
    onLogTextChange: (TextFieldValue) -> Unit,
    onScanRequest: () -> Unit,
    onConnectClick: () -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Button(
            onClick = onScanRequest,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Scan for BLE Devices")
        }

        Spacer(modifier = Modifier.height(16.dp))

        LazyColumn(
            modifier = Modifier.weight(1f)
        ) {
            items(devices) { device ->
                val isSelected = device == selectedDevice
                Text(
                    text = device,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                        .background(if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.2f) else Color.Transparent)
                        .clickable { onDeviceSelected(device) }
                        .padding(8.dp)
                )
                HorizontalDivider(Modifier, DividerDefaults.Thickness, DividerDefaults.color)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = logText,
            onValueChange = onLogTextChange,
            modifier = Modifier
                .fillMaxWidth()
                .height(150.dp),
            label = { Text("Connection Log") },
            readOnly = true,
            maxLines = 10,
            singleLine = false
        )

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = onConnectClick,
            modifier = Modifier.fillMaxWidth(),
            enabled = selectedDevice != null
        ) {
            Text("Connect")
        }
    }
}

@Preview(showBackground = true)
@Composable
fun PreviewBleScannerUI() {
    BLE_WroverTheme {
        BleScannerUI(
            devices = listOf("Device A (AA:BB:CC:DD:EE:FF)", "Device B (11:22:33:44:55:66)"),
            selectedDevice = "Device A (AA:BB:CC:DD:EE:FF)",
            onDeviceSelected = {},
            logText = TextFieldValue("Connected to Device A...\nService discovered..."),
            onLogTextChange = {},
            onScanRequest = {},
            onConnectClick = {}
        )
    }
}
