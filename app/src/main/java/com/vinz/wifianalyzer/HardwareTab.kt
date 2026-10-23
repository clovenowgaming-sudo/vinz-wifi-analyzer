package com.vinz.wifianalyzer

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import android.widget.LinearLayout
import java.io.File
import java.util.Locale

class HardwareTab(private val act: MainActivity) {

    private fun gb(b: Long): String = String.format(Locale.US, "%.1f GB", b / 1073741824.0)

    private fun readLine(path: String): String? = try { File(path).readText().trim() } catch (e: Exception) { null }

    private fun uptime(): String {
        val s = SystemClock.elapsedRealtime() / 1000
        return "${s / 3600} jam ${(s % 3600) / 60} menit"
    }

    @Suppress("DEPRECATION")
    private fun refreshRate(): String = try {
        String.format(Locale.US, "%.0f Hz", act.windowManager.defaultDisplay.refreshRate)
    } catch (e: Exception) { "-" }

    fun render(c: LinearLayout) {
        val soc = if (Build.VERSION.SDK_INT >= 31) (Build.SOC_MANUFACTURER + " " + Build.SOC_MODEL) else "-"
        c.addView(act.card("Perangkat", listOf(
            "Model" to "${Build.MANUFACTURER} ${Build.MODEL}",
            "Brand / Device" to "${Build.BRAND} / ${Build.DEVICE}",
            "Board / Hardware" to "${Build.BOARD} / ${Build.HARDWARE}",
            "SoC" to soc,
            "Android" to "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            "Security patch" to (if (Build.VERSION.SDK_INT >= 23) Build.VERSION.SECURITY_PATCH else "-"),
            "ABI" to Build.SUPPORTED_ABIS.joinToString(", "),
            "Uptime" to uptime()
        )))

        // CPU
        val cores = Runtime.getRuntime().availableProcessors()
        val freqs = (0 until cores).map { i ->
            val v = readLine("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq")?.toLongOrNull()
            if (v != null) "${v / 1000}" else "?"
        }
        c.addView(act.card("CPU", listOf(
            "Jumlah core" to "$cores",
            "Max freq (MHz)" to (if (freqs.all { it == "?" }) "Tidak bisa dibaca (diblokir Android)" else freqs.joinToString(", "))
        )))

        // RAM & storage
        val am = act.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        val st = StatFs(Environment.getDataDirectory().path)
        val total = st.totalBytes
        val free = st.availableBytes
        c.addView(act.card("Memori & Penyimpanan", listOf(
            "RAM total" to gb(mi.totalMem),
            "RAM tersedia" to gb(mi.availMem),
            "Storage total" to gb(total),
            "Storage kosong" to gb(free),
            "Storage terpakai" to "${((total - free) * 100 / total)}%"
        )))

        // Layar
        val dm = act.resources.displayMetrics
        c.addView(act.card("Layar", listOf(
            "Resolusi" to "${dm.widthPixels} x ${dm.heightPixels}",
            "Density" to "${dm.densityDpi} dpi",
            "Refresh rate" to refreshRate()
        )))

        // Baterai
        val b: Intent? = act.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val lvl = b?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = b?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val temp = (b?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0
        val volt = b?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0
        val status = when (b?.getIntExtra(BatteryManager.EXTRA_STATUS, 0)) {
            2 -> "Mengisi"
            3 -> "Dipakai"
            4 -> "Tidak mengisi"
            5 -> "Penuh"
            else -> "-"
        }
        val health = when (b?.getIntExtra(BatteryManager.EXTRA_HEALTH, 0)) {
            2 -> "Bagus"
            3 -> "Overheat"
            4 -> "Rusak"
            5 -> "Over voltage"
            7 -> "Dingin"
            else -> "-"
        }
        val plugged = when (b?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)) {
            1 -> "Charger AC"
            2 -> "USB"
            4 -> "Wireless"
            else -> "Tidak dicolok"
        }
        c.addView(act.card("Baterai", listOf(
            "Level" to (if (lvl >= 0) "${lvl * 100 / scale}%" else "-"),
            "Status" to status,
            "Kesehatan" to health,
            "Sumber daya" to plugged,
            "Suhu" to String.format(Locale.US, "%.1f °C", temp),
            "Voltase" to "$volt mV",
            "Teknologi" to (b?.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY) ?: "-")
        )))

        // Radio WiFi
        val w = act.wifi
        val wrows = ArrayList<Pair<String, String>>()
        wrows.add("Band 5 GHz" to yn(w.is5GHzBandSupported))
        if (Build.VERSION.SDK_INT >= 30) {
            wrows.add("Band 6 GHz" to yn(w.is6GHzBandSupported))
            wrows.add("Wi-Fi 5 (ac)" to yn(w.isWifiStandardSupported(5)))
            wrows.add("Wi-Fi 6 (ax)" to yn(w.isWifiStandardSupported(6)))
            wrows.add("Wi-Fi 7 (be)" to yn(w.isWifiStandardSupported(8)))
        }
        c.addView(act.card("Radio WiFi", wrows))

        // Fitur hardware
        val pm = act.packageManager
        val feats = listOf(
            "Bluetooth" to "android.hardware.bluetooth",
            "Bluetooth LE" to "android.hardware.bluetooth_le",
            "NFC" to "android.hardware.nfc",
            "WiFi Direct" to "android.hardware.wifi.direct",
            "WiFi RTT" to "android.hardware.wifi.rtt",
            "GPS" to "android.hardware.location.gps",
            "Telepon/SIM" to "android.hardware.telephony",
            "Sidik jari" to "android.hardware.fingerprint",
            "Kamera belakang" to "android.hardware.camera",
            "Kamera depan" to "android.hardware.camera.front",
            "Gyroscope" to "android.hardware.sensor.gyroscope",
            "Barometer" to "android.hardware.sensor.barometer",
            "USB Host (OTG)" to "android.hardware.usb.host",
            "IR blaster" to "android.hardware.consumerir"
        )
        c.addView(act.card("Fitur hardware", feats.map { Pair(it.first, yn(pm.hasSystemFeature(it.second))) }))

        // Sensor
        val sm = act.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensors = sm.getSensorList(Sensor.TYPE_ALL)
        c.addView(act.card("Sensor (${sensors.size})", listOf(
            "Daftar" to sensors.take(30).joinToString("\n") { it.name }
        )))
    }

    private fun yn(v: Boolean): String = if (v) "✔ Ya" else "✖ Tidak"
}
