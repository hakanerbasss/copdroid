package com.wizaicorp.copdroid

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
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
import java.io.OutputStream
import java.util.UUID
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private val SPP: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private val adapter: BluetoothAdapter? by lazy { BluetoothAdapter.getDefaultAdapter() }

    private var socket: BluetoothSocket? = null
    private var out: OutputStream? = null
    @Volatile private var connected = false

    @Volatile private var throttle = 0   // -100..100
    @Volatile private var steer = 0      // -100..100
    private var maxPct = 40
    private var minPwm = 25
    private var cruiseOn = false
    private var cruiseLevel = 20

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var sender: Thread

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
        val title = TextView(this).apply { text = "CopDroid — Çöp Aracı Kumanda"; textSize = 20f }
        status = TextView(this).apply { text = "Bağlı değil"; textSize = 14f }

        val btnConnect = Button(this).apply {
            text = "Bağlan"
            setOnClickListener { if (connected) disconnect() else pickAndConnect() }
        }
        val btnStop = Button(this).apply {
            text = "ACİL DUR"
            setBackgroundColor(0xFFD32F2F.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            setOnClickListener { throttle = 0; steer = 0; cruiseOn = false; sendLine("S") }
        }

        val cruiseLabel = TextView(this).apply { text = "Yürüyüş hızı: %$cruiseLevel" }
        val cruiseBar = SeekBar(this).apply {
            max = 100; progress = cruiseLevel
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, u: Boolean) { cruiseLevel = p; cruiseLabel.text = "Yürüyüş hızı: %$p" }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) { if (cruiseOn) sendLine("W,1,$cruiseLevel") }
            })
        }
        val btnCruise = Button(this).apply {
            text = "Yürüyüş modu: KAPALI"
            setOnClickListener {
                cruiseOn = !cruiseOn
                text = if (cruiseOn) "Yürüyüş modu: AÇIK (sabit $cruiseLevel)" else "Yürüyüş modu: KAPALI"
                sendLine(if (cruiseOn) "W,1,$cruiseLevel" else "W,0")
            }
        }

        val joy = JoystickView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(260), dp(260)).apply {
                gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(16); bottomMargin = dp(16)
            }
            listener = { x, y ->
                throttle = (y * 100).roundToInt().coerceIn(-100, 100)
                steer = (x * 100).roundToInt().coerceIn(-100, 100)
            }
        }

        val maxLabel = TextView(this).apply { text = "Azami hız: %$maxPct" }
        val maxBar = SeekBar(this).apply {
            max = 100; progress = maxPct
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, u: Boolean) { maxPct = p; maxLabel.text = "Azami hız: %$p" }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) { sendConfig() }
            })
        }
        val minLabel = TextView(this).apply { text = "Başlangıç gücü: $minPwm" }
        val minBar = SeekBar(this).apply {
            max = 120; progress = minPwm
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, u: Boolean) { minPwm = p; minLabel.text = "Başlangıç gücü: $p" }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) { sendConfig() }
            })
        }

        for (v in listOf(title, status, btnConnect, joy, maxLabel, maxBar, minLabel, minBar, cruiseLabel, cruiseBar, btnCruise, btnStop)) root.addView(v)
        return root
    }

    private fun ensurePermission() {
        val needed = if (Build.VERSION.SDK_INT >= 31)
            arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        else arrayOf()
        val miss = needed.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (miss.isNotEmpty()) ActivityCompat.requestPermissions(this, miss.toTypedArray(), 1)
    }

    @SuppressLint("MissingPermission")
    private fun pickAndConnect() {
        val a = adapter ?: run { toast("Bluetooth yok"); return }
        if (!a.isEnabled) { toast("Bluetooth'u aç"); return }
        val bonded = try { a.bondedDevices.toList() } catch (e: SecurityException) { toast("İzin gerekli"); return }
        if (bonded.isEmpty()) { toast("Önce HC-05'i telefon ayarlarından eşleştir (PIN 1234)"); return }
        val names = bonded.map { "${deviceName(it)}  (${it.address})" }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Cihaz seç")
            .setItems(names) { _, i -> connect(bonded[i]) }
            .show()
    }

    @SuppressLint("MissingPermission")
    private fun deviceName(d: BluetoothDevice): String = try { d.name ?: "?" } catch (e: SecurityException) { "?" }

    @SuppressLint("MissingPermission")
    private fun connect(dev: BluetoothDevice) {
        Thread {
            try {
                adapter?.cancelDiscovery()
                val s = dev.createRfcommSocketToServiceRecord(SPP)
                s.connect()
                socket = s; out = s.outputStream; connected = true
                ui.post { status.text = "Bağlı: ${deviceName(dev)}" }
                sendConfig()
            } catch (e: Exception) {
                connected = false
                ui.post { status.text = "Bağlanamadı: ${e.message}" }
            }
        }.start()
    }

    private fun disconnect() {
        connected = false
        try { out?.close(); socket?.close() } catch (_: Exception) {}
        out = null; socket = null
        status.text = "Bağlı değil"
    }

    private fun startSender() {
        sender = Thread {
            while (true) {
                if (connected) sendLine("D,$throttle,$steer")
                try { Thread.sleep(50) } catch (_: InterruptedException) { break } // 20 Hz
            }
        }.apply { isDaemon = true; start() }
    }

    private fun sendConfig() = sendLine("C,$maxPct,$minPwm")

    @Synchronized
    private fun sendLine(s: String) {
        val o = out ?: return
        try { o.write((s + "\n").toByteArray()) } catch (e: Exception) {
            connected = false; ui.post { status.text = "Bağlantı koptu" }
        }
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()

    override fun onStop() { super.onStop(); throttle = 0; steer = 0; sendLine("S") }
    override fun onDestroy() { super.onDestroy(); disconnect() }
}
