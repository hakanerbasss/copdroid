package com.wizaicorp.copdroid

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.util.UUID
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    // Yaygın BLE seri servisleri
    private val FFE0 = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb")
    private val FFE1 = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb")
    private val NUS  = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
    private val NUS_W = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")
    private val NUS_N = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")
    private val CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private val adapter: BluetoothAdapter? by lazy { BluetoothAdapter.getDefaultAdapter() }
    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    @Volatile private var connected = false

    @Volatile private var throttle = 0
    @Volatile private var steer = 0
    private var maxPct = 40
    private var minPwm = 100
    private var cruiseOn = false
    private var cruiseLevel = 20

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        ensurePermission()
        startSender()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        val title = TextView(this).apply { text = "CopDroid — Çöp Aracı Kumanda (BLE)"; textSize = 18f }
        status = TextView(this).apply { text = "Bağlı değil"; textSize = 14f }

        val btnConnect = Button(this).apply {
            text = "Bağlan"
            setOnClickListener { if (connected) disconnect() else pickAndConnect() }
        }
        val btnStop = Button(this).apply {
            text = "ACİL DUR"; setBackgroundColor(0xFFD32F2F.toInt()); setTextColor(0xFFFFFFFF.toInt())
            setOnClickListener { throttle = 0; steer = 0; cruiseOn = false; writeCmd("S") }
        }

        val joy = JoystickView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(260), dp(260)).apply {
                gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(12); bottomMargin = dp(12)
            }
            listener = { x, y ->
                throttle = (y * 100).roundToInt().coerceIn(-100, 100)
                steer = (x * 100).roundToInt().coerceIn(-100, 100)
            }
        }

        val maxLabel = TextView(this).apply { text = "Azami hız: %$maxPct" }
        val maxBar = SeekBar(this).apply {
            max = 100; progress = maxPct
            setOnSeekBarChangeListener(simpleBar({ maxPct = it; maxLabel.text = "Azami hız: %$it" }, { sendConfig() }))
        }
        val minLabel = TextView(this).apply { text = "Başlangıç gücü: $minPwm" }
        val minBar = SeekBar(this).apply {
            max = 160; progress = minPwm
            setOnSeekBarChangeListener(simpleBar({ minPwm = it; minLabel.text = "Başlangıç gücü: $it" }, { sendConfig() }))
        }
        val cruiseLabel = TextView(this).apply { text = "Yürüyüş hızı: %$cruiseLevel" }
        val cruiseBar = SeekBar(this).apply {
            max = 100; progress = cruiseLevel
            setOnSeekBarChangeListener(simpleBar({ cruiseLevel = it; cruiseLabel.text = "Yürüyüş hızı: %$it" }, { if (cruiseOn) writeCmd("W,1,$cruiseLevel") }))
        }
        val btnCruise = Button(this).apply {
            text = "Yürüyüş modu: KAPALI"
            setOnClickListener {
                cruiseOn = !cruiseOn
                text = if (cruiseOn) "Yürüyüş modu: AÇIK (sabit $cruiseLevel)" else "Yürüyüş modu: KAPALI"
                writeCmd(if (cruiseOn) "W,1,$cruiseLevel" else "W,0")
            }
        }

        for (v in listOf(title, status, btnConnect, joy, maxLabel, maxBar, minLabel, minBar, cruiseLabel, cruiseBar, btnCruise, btnStop)) root.addView(v)
        return root
    }

    private fun simpleBar(onChange: (Int) -> Unit, onStop: () -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(sb: SeekBar, p: Int, u: Boolean) = onChange(p)
        override fun onStartTrackingTouch(sb: SeekBar) {}
        override fun onStopTrackingTouch(sb: SeekBar) = onStop()
    }

    private fun ensurePermission() {
        val needed = if (Build.VERSION.SDK_INT >= 31)
            arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        val miss = needed.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (miss.isNotEmpty()) ActivityCompat.requestPermissions(this, miss.toTypedArray(), 1)
    }

    @SuppressLint("MissingPermission")
    private fun pickAndConnect() {
        val a = adapter ?: run { toast("Bluetooth yok"); return }
        if (!a.isEnabled) { toast("Bluetooth'u aç"); return }
        val bonded = try { a.bondedDevices.toList() } catch (e: SecurityException) { toast("İzin gerekli"); return }
        if (bonded.isEmpty()) { toast("Önce modülü telefon ayarlarından eşleştir"); return }
        val names = bonded.map { "${dname(it)}  (${it.address})" }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Cihaz seç")
            .setItems(names) { _, i -> connect(bonded[i]) }.show()
    }

    @SuppressLint("MissingPermission")
    private fun dname(d: BluetoothDevice) = try { d.name ?: "?" } catch (e: SecurityException) { "?" }

    @SuppressLint("MissingPermission")
    private fun connect(dev: BluetoothDevice) {
        ui.post { status.text = "Bağlanıyor (BLE)..." }
        gatt?.close()
        gatt = dev.connectGatt(this, false, gattCb, BluetoothDevice.TRANSPORT_LE)
    }

    private fun disconnect() {
        connected = false
        try { gatt?.disconnect(); gatt?.close() } catch (_: Exception) {}
        gatt = null; writeChar = null
        ui.post { status.text = "Bağlı değil" }
    }

    private var writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
    @Volatile private var busy = false

    private val gattCb = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, st: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                ui.post { status.text = "Bağlandı, servis aranıyor..." }
                g.discoverServices()
            } else {
                connected = false
                ui.post { status.text = "Bağlantı koptu" }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, st: Int) {
            var w: BluetoothGattCharacteristic? = null
            var n: BluetoothGattCharacteristic? = null
            g.getService(FFE0)?.getCharacteristic(FFE1)?.let { w = it; n = it }
            if (w == null) g.getService(NUS)?.let { s ->
                w = s.getCharacteristic(NUS_W); n = s.getCharacteristic(NUS_N)
            }
            if (w == null) {
                outer@ for (s in g.services) for (c in s.characteristics) {
                    val p = c.properties
                    if (p and (BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) {
                        w = c
                        if (p and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) n = c
                        break@outer
                    }
                }
            }
            val wc = w
            writeChar = wc
            if (wc == null) { ui.post { status.text = "Seri karakteristik bulunamadı" }; return }
            // yazma tipini karakteristiğin özelliğinden seç
            writeType = if (wc.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0)
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT

            val nc = n
            val cccd = nc?.getDescriptor(CCCD)
            if (nc != null && cccd != null) {
                g.setCharacteristicNotification(nc, true)
                // bildirim ayarlanınca (onDescriptorWrite) bağlı say ve yazmaya başla
                if (Build.VERSION.SDK_INT >= 33) g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                else { @Suppress("DEPRECATION") run { cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE; g.writeDescriptor(cccd) } }
            } else {
                goReady()
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, st: Int) { goReady() }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, st: Int) { busy = false }
    }

    private fun goReady() {
        if (connected) return
        busy = false
        connected = true
        ui.post { status.text = "Bağlı ✓" }
        sendConfig()
    }

    @SuppressLint("MissingPermission")
    @Synchronized
    private fun writeCmd(s: String) {
        val g = gatt ?: return
        val c = writeChar ?: return
        if (writeType == BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT && busy) return // cevaplı yazmada çakışmayı önle
        val bytes = (s + "\n").toByteArray()
        try {
            if (writeType == BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) busy = true
            if (Build.VERSION.SDK_INT >= 33) {
                g.writeCharacteristic(c, bytes, writeType)
            } else {
                @Suppress("DEPRECATION") run {
                    c.writeType = writeType
                    c.value = bytes
                    g.writeCharacteristic(c)
                }
            }
        } catch (_: Exception) { busy = false }
    }

    private fun sendConfig() = writeCmd("C,$maxPct,$minPwm")

    private fun startSender() {
        Thread {
            while (true) {
                if (connected) writeCmd("D,$throttle,$steer")
                try { Thread.sleep(60) } catch (_: InterruptedException) { break }
            }
        }.apply { isDaemon = true; start() }
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()

    override fun onStop() { super.onStop(); throttle = 0; steer = 0; writeCmd("S") }
    override fun onDestroy() { super.onDestroy(); disconnect() }
}
