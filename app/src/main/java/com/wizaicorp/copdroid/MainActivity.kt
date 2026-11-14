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
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
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
    private var cruiseLevel = 40
    private var btnCruiseRef: Button? = null

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var readout: TextView

    // renkler
    private val BG = 0xFF0E1116.toInt()
    private val SURFACE = 0xFF1B2029.toInt()
    private val ACCENT = 0xFF3D5AFE.toInt()
    private val GREEN = 0xFF00C853.toInt()
    private val RED = 0xFFE53935.toInt()
    private val TXT = 0xFFECEFF1.toInt()
    private val TXT2 = 0xFF90A4AE.toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        ensurePermission()
        startSender()
        startReadout()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()

    private fun roundBg(color: Int, r: Int = 14) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(r).toFloat()
    }
    private fun styledBtn(label: String, bg: Int, fg: Int = Color.WHITE, size: Float = 15f, vPad: Int = 8): Button = Button(this).apply {
        text = label; setTextColor(fg); isAllCaps = false; textSize = size
        minHeight = 0; minimumHeight = 0; minWidth = 0; minimumWidth = 0
        background = roundBg(bg); stateListAnimator = null
        setPadding(dp(14), dp(vPad), dp(14), dp(vPad))
        setOnTouchListener { v, e ->
            when (e.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    v.animate().scaleX(0.94f).scaleY(0.94f).alpha(0.8f).setDuration(70).start()
                    v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL ->
                    v.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(110).start()
            }
            false
        }
    }
    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = roundBg(SURFACE, 16); setPadding(dp(12), dp(8), dp(12), dp(8))
    }
    private fun lbl(t: String, size: Float = 14f, color: Int = TXT2, bold: Boolean = false) = TextView(this).apply {
        text = t; textSize = size; setTextColor(color); if (bold) setTypeface(typeface, Typeface.BOLD)
    }
    private fun lp(top: Int = 0) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(top) }

    private fun setStatus(msg: String) = ui.post {
        status.text = msg
        val c = when { msg.contains("✓") -> GREEN; msg.contains("Bağlanıyor") || msg.contains("servis") -> 0xFFFFB300.toInt(); else -> RED }
        status.setTextColor(c)
        status.background = roundBg(SURFACE, 20)
        status.setPadding(dp(12), dp(8), dp(12), dp(8))
    }

    private fun startReadout() {
        val r = object : Runnable {
            override fun run() {
                val t = throttle; val s = steer
                readout.text = if (!connected) "—" else "İleri %$t   ·   Dönüş %$s"
                readout.setTextColor(if (connected) TXT else TXT2)
                ui.postDelayed(this, 120)
            }
        }
        ui.postDelayed(r, 200)
    }

    private fun buildUi(): View {
        // Yatay gamepad: SOL stick = ileri/geri, ORTA = ayarlar, SAĞ stick = sağ/sol
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(BG); setPadding(dp(8), dp(6), dp(8), dp(6))
        }
        // Sistem çubukları / çentik / yan navigasyon çubuğu altında kalmasın
        ViewCompat.setOnApplyWindowInsetsListener(row) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(b.left + dp(8), b.top + dp(6), b.right + dp(8), b.bottom + dp(6))
            insets
        }

        // ---- SOL: İLERİ / GERİ stick ----
        val leftCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
        }
        leftCol.addView(lbl("İLERİ / GERİ", 12f, TXT2, true).apply { gravity = Gravity.CENTER })
        val throttlePanel = LinearLayout(this).apply {
            gravity = Gravity.CENTER; background = roundBg(SURFACE, 24); setPadding(dp(8), dp(8), dp(8), dp(8))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(4) }
        }
        val throttleStick = JoystickView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT)
            listener = { _, y -> throttle = (y * 100).roundToInt().coerceIn(-100, 100) }
        }
        throttlePanel.addView(throttleStick)
        leftCol.addView(throttlePanel)
        row.addView(leftCol)

        // ---- ORTA: ayarlar ----
        val centerCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(8), 0, dp(8), 0)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1.3f)
        }
        val centerScroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        val mid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val topRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        status = TextView(this).apply { textSize = 12f; gravity = Gravity.CENTER_VERTICAL; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        val btnConnect = styledBtn("Bağlan", ACCENT, size = 14f, vPad = 8).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(6) }
            setOnClickListener { if (connected) disconnect() else pickAndConnect() }
        }
        topRow.addView(status, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        topRow.addView(btnConnect)
        centerCol.addView(topRow)
        setStatus("Bağlı değil")

        readout = TextView(this).apply {
            text = "—"; textSize = 15f; setTextColor(TXT2); gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD); layoutParams = lp(4)
        }
        centerCol.addView(readout)

        val speedCard = card().apply { layoutParams = lp(6) }
        val maxLabel = lbl("Azami hız: %$maxPct", 13f, TXT)
        val maxBar = SeekBar(this).apply {
            max = 100; progress = maxPct
            setOnSeekBarChangeListener(simpleBar({ maxPct = it; maxLabel.text = "Azami hız: %$it" }, { sendConfig() }))
        }
        val minLabel = lbl("Başlangıç gücü: $minPwm", 13f, TXT).apply { layoutParams = lp(4) }
        val minBar = SeekBar(this).apply {
            max = 160; progress = minPwm
            setOnSeekBarChangeListener(simpleBar({ minPwm = it; minLabel.text = "Başlangıç gücü: $it" }, { sendConfig() }))
        }
        speedCard.addView(maxLabel); speedCard.addView(maxBar)
        speedCard.addView(minLabel); speedCard.addView(minBar)
        mid.addView(speedCard)

        val walkCard = card().apply { layoutParams = lp(6) }
        val cruiseLabel = lbl("Yürüyüş hızı: %$cruiseLevel", 13f, TXT)
        val cruiseBar = SeekBar(this).apply {
            max = 100; progress = cruiseLevel
            setOnSeekBarChangeListener(simpleBar({ cruiseLevel = it; cruiseLabel.text = "Yürüyüş hızı: %$it" }, { refreshCruiseBtn() }))
        }
        val btnCruise = styledBtn("Yürüyüş: KAPALI", 0xFF37474F.toInt()).apply {
            layoutParams = lp(4)
            setOnClickListener { cruiseOn = !cruiseOn; refreshCruiseBtn() }
        }
        btnCruiseRef = btnCruise
        walkCard.addView(cruiseLabel); walkCard.addView(cruiseBar); walkCard.addView(btnCruise)
        mid.addView(walkCard)

        val btnStop = styledBtn("■  ACİL DUR", RED, size = 17f, vPad = 12).apply {
            layoutParams = lp(6)
            setOnClickListener { throttle = 0; steer = 0; cruiseOn = false; refreshCruiseBtn(); writeCmd("S") }
        }
        centerScroll.addView(mid)
        centerCol.addView(centerScroll)
        centerCol.addView(btnStop)   // her zaman görünür, kaydırılmaz
        row.addView(centerCol)

        // ---- SAĞ: SOL / SAĞ stick ----
        val rightCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
        }
        rightCol.addView(lbl("SOL / SAĞ", 12f, TXT2, true).apply { gravity = Gravity.CENTER })
        val steerPanel = LinearLayout(this).apply {
            gravity = Gravity.CENTER; background = roundBg(SURFACE, 24); setPadding(dp(8), dp(8), dp(8), dp(8))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(4) }
        }
        val steerStick = JoystickView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT)
            listener = { x, _ -> steer = (x * 100).roundToInt().coerceIn(-100, 100) }
        }
        steerPanel.addView(steerStick)
        rightCol.addView(steerPanel)
        row.addView(rightCol)

        return row
    }

    private fun refreshCruiseBtn() = ui.post {
        val b = btnCruiseRef ?: return@post
        b.text = if (cruiseOn) "Yürüyüş: AÇIK (%$cruiseLevel)" else "Yürüyüş: KAPALI"
        b.background = roundBg(if (cruiseOn) GREEN else 0xFF37474F.toInt())
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
        val scanner = a.bluetoothLeScanner ?: run { toast("BLE tarayıcı yok"); return }
        val found = LinkedHashMap<String, BluetoothDevice>()
        try { a.bondedDevices.forEach { found[it.address] = it } } catch (_: SecurityException) {}
        ui.post { status.text = "Taranıyor (6 sn)..." }
        val cb = object : android.bluetooth.le.ScanCallback() {
            override fun onScanResult(type: Int, r: android.bluetooth.le.ScanResult) { found[r.device.address] = r.device }
            override fun onScanFailed(code: Int) { ui.post { toast("Tarama hatası: $code") } }
        }
        try { scanner.startScan(cb) } catch (e: SecurityException) { toast("İzin gerekli"); return }
        ui.postDelayed({
            try { scanner.stopScan(cb) } catch (_: Exception) {}
            ui.post { status.text = "Bağlı değil" }
            if (found.isEmpty()) { toast("Cihaz bulunamadı"); return@postDelayed }
            val list = found.values.sortedByDescending { dname(it) != "?" }
            val names = list.map { "${dname(it)}  (${it.address})" }.toTypedArray()
            AlertDialog.Builder(this).setTitle("Cihaz seç")
                .setItems(names) { _, i -> connect(list[i]) }.show()
        }, 6000)
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

        private fun onRx(b: ByteArray) {
            val t = String(b).trim()
            if (t.startsWith("CR,0")) { cruiseOn = false; refreshCruiseBtn() }
        }
        @Deprecated("old API")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION") onRx(c.value ?: return)
        }
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) { onRx(value) }

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

    private var tick = 0
    private fun startSender() {
        Thread {
            while (true) {
                if (connected) {
                    if (cruiseOn && throttle < -50) { cruiseOn = false; refreshCruiseBtn() } // stick'i geri çek = iptal
                    writeCmd("D,$throttle,$steer,${if (cruiseOn) 1 else 0},$cruiseLevel")
                    tick++
                    if (tick % 20 == 0) sendConfig() // ayar kaybolmasın
                }
                try { Thread.sleep(60) } catch (_: InterruptedException) { break }
            }
        }.apply { isDaemon = true; start() }
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()

    override fun onStop() { super.onStop(); throttle = 0; steer = 0; writeCmd("S") }
    override fun onDestroy() { super.onDestroy(); disconnect() }
}
