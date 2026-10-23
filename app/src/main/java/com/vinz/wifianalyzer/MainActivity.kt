package com.vinz.wifianalyzer

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

private class Probe(val ip: String, val ms: Long, val ports: List<Int>)

class MainActivity : Activity() {

    data class Dev(
        val ip: String, val mac: String, val host: String, val tag: String,
        val ports: String, val ms: Long, val kind: String, val upnp: String
    )

    lateinit var wifi: WifiManager
    lateinit var cm: ConnectivityManager
    private lateinit var content: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var tabViews: List<TextView>
    private lateinit var speedTab: SpeedTab
    private lateinit var secTab: SecurityTab
    private lateinit var hwTab: HardwareTab
    val ui = Handler(Looper.getMainLooper())

    var tab = 0
    private var scanning = false
    private var lastScan = 0L
    private var devices: List<Dev> = emptyList()
    private var devStatus = "Tekan tombol untuk scan perangkat yang terhubung di WiFi ini."

    private val PORTS = intArrayOf(80, 443, 53, 22, 23, 139, 445, 554, 7000, 8008, 8009, 8080, 9100, 5555, 62078)

    private val OUI = mapOf(
        "B8:27:EB" to "Raspberry Pi",
        "DC:A6:32" to "Raspberry Pi",
        "E4:5F:01" to "Raspberry Pi",
        "00:50:56" to "VMware",
        "08:00:27" to "VirtualBox",
        "3C:5A:B4" to "Google"
    )

    private val ticker = object : Runnable {
        override fun run() {
            if (tab == 0) {
                val y = scroll.scrollY
                showTab(0)
                scroll.post { scroll.scrollTo(0, y) }
            }
            ui.postDelayed(this, 3000)
        }
    }

    private val scanReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            if (tab == 1) showTab(1)
        }
    }

    // ---------- lifecycle ----------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        cm = applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        speedTab = SpeedTab(this)
        secTab = SecurityTab(this)
        hwTab = HardwareTab(this)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(cBg)
        root.setPadding(dp(14), dp(34), dp(14), dp(8))
        root.addView(tv("WiFi Analyzer", 24f, Color.WHITE, true))
        root.addView(tv("Mod By Vinz :3", 12f, cSub))

        val hs = HorizontalScrollView(this)
        hs.isHorizontalScrollBarEnabled = false
        hs.layoutParams = lp(12, 10)
        val tabs = LinearLayout(this)
        tabs.orientation = LinearLayout.HORIZONTAL
        val names = listOf("Info", "Jaringan", "Perangkat", "Speed", "Keamanan", "Hardware")
        tabViews = names.mapIndexed { i, n ->
            val t = tv(n, 14f, Color.WHITE, true)
            t.gravity = Gravity.CENTER
            t.setPadding(dp(16), dp(10), dp(16), dp(10))
            val p = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            p.setMargins(dp(3), 0, dp(3), 0)
            t.layoutParams = p
            t.setOnClickListener { showTab(i) }
            t
        }
        tabViews.forEach { tabs.addView(it) }
        hs.addView(tabs)
        root.addView(hs)

        scroll = ScrollView(this)
        content = LinearLayout(this)
        content.orientation = LinearLayout.VERTICAL
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        requestPerms()
        showTab(0)
    }

    override fun onResume() {
        super.onResume()
        val f = IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(scanReceiver, f, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(scanReceiver, f)
        }
        ui.postDelayed(ticker, 3000)
    }

    override fun onPause() {
        super.onPause()
        try { unregisterReceiver(scanReceiver) } catch (e: Exception) { }
        ui.removeCallbacks(ticker)
    }

    // ---------- permission / lokasi ----------

    fun hasLoc(): Boolean = Build.VERSION.SDK_INT < 23 ||
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun locEnabled(): Boolean {
        return try {
            val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            if (Build.VERSION.SDK_INT >= 28) lm.isLocationEnabled
            else lm.isProviderEnabled(LocationManager.GPS_PROVIDER) || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        } catch (e: Exception) { true }
    }

    fun requestPerms() {
        if (Build.VERSION.SDK_INT >= 23 && !hasLoc()) {
            val p = if (Build.VERSION.SDK_INT >= 33)
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION, "android.permission.NEARBY_WIFI_DEVICES")
            else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            requestPermissions(p, 1)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        showTab(tab)
    }

    private fun openLocationSettings() {
        try { startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) } catch (e: Exception) { }
    }

    // ---------- tabs ----------

    fun refresh(i: Int) {
        if (tab == i) showTab(i)
    }

    fun showTab(i: Int) {
        tab = i
        tabViews.forEachIndexed { idx, t ->
            t.background = rounded(if (idx == i) cAccent else cCard, 12)
        }
        content.removeAllViews()
        devStatusView = null
        when (i) {
            0 -> renderInfo()
            1 -> renderScan()
            2 -> renderDevices()
            3 -> speedTab.render(content)
            4 -> secTab.render(content)
            5 -> hwTab.render(content)
        }
    }

    private fun safeScan(): List<ScanResult> =
        try { wifi.scanResults ?: emptyList() } catch (e: SecurityException) { emptyList() }

    private fun permCards() {
        if (!hasLoc()) {
            content.addView(card("Izin Lokasi belum diberikan", listOf(
                "Info" to "Android butuh izin Lokasi (pilih \"Presisi/Precise\") supaya nama WiFi dan hasil scan terbaca."
            ), cWarn))
            content.addView(button("Izinkan Lokasi") { requestPerms() })
        } else if (!locEnabled()) {
            content.addView(card("Layanan Lokasi (GPS) mati", listOf(
                "Info" to "Android menyembunyikan hasil scan WiFi kalau Lokasi mati. Nyalakan Lokasi lalu kembali ke app."
            ), cWarn))
            content.addView(button("Buka Pengaturan Lokasi") { openLocationSettings() })
        }
    }

    // ---------- tab 0: info ----------

    private fun renderInfo() {
        permCards()
        if (!wifi.isWifiEnabled) {
            content.addView(card("WiFi mati", listOf("Status" to "Nyalakan WiFi dulu")))
            return
        }
        val info = wifi.connectionInfo
        val d = wifi.dhcpInfo
        if (info == null || d == null || d.ipAddress == 0) {
            content.addView(card("Tidak terhubung", listOf("Status" to "Hubungkan HP ke jaringan WiFi")))
            return
        }

        val ssid = info.ssid?.trim('"') ?: "?"
        val rssi = info.rssi
        val freq = info.frequency
        val q = quality(rssi)
        val pct = ((rssi + 100) * 2).coerceIn(0, 100)

        val hero = LinearLayout(this)
        hero.orientation = LinearLayout.VERTICAL
        hero.setPadding(dp(16), dp(16), dp(16), dp(16))
        hero.background = rounded(cCard, 18)
        hero.layoutParams = lp()
        hero.addView(tv(if (ssid.contains("unknown")) "(SSID disembunyikan - nyalakan Lokasi)" else ssid, 20f, Color.WHITE, true))
        hero.addView(tv("${bars(rssi)}  $rssi dBm  •  $pct%", 16f, q.second, true))
        hero.addView(tv(q.first, 13f, q.second))
        content.addView(hero)

        var std = "-"
        if (Build.VERSION.SDK_INT >= 30) {
            std = when (info.wifiStandard) {
                4 -> "Wi-Fi 4 (802.11n)"
                5 -> "Wi-Fi 5 (802.11ac)"
                6 -> "Wi-Fi 6 (802.11ax)"
                8 -> "Wi-Fi 7 (802.11be)"
                1 -> "Legacy (a/b/g)"
                else -> "-"
            }
        }
        val match = safeScan().firstOrNull { it.BSSID == info.bssid }
        val mac = info.macAddress ?: "-"

        content.addView(card("Koneksi", listOf(
            "BSSID" to (info.bssid ?: "-"),
            "Band" to band(freq),
            "Channel" to "${chan(freq)}  ($freq MHz)",
            "Lebar channel" to (if (match != null) width(match.channelWidth) else "-"),
            "Link speed" to "${info.linkSpeed} Mbps",
            "Standar" to std,
            "Keamanan" to (if (match != null) sec(match.capabilities) else "-"),
            "MAC HP ini" to (if (mac.startsWith("02:00:00")) "Disembunyikan Android (privasi)" else mac)
        )))

        val cidr = Integer.bitCount(d.netmask)
        content.addView(card("Alamat IP", listOf(
            "IP HP" to dhcpIp(d.ipAddress),
            "Subnet" to "${dhcpIp(d.netmask)}  (/$cidr)",
            "Gateway/Router" to dhcpIp(d.gateway),
            "DNS 1" to dhcpIp(d.dns1),
            "DNS 2" to dhcpIp(d.dns2),
            "DHCP server" to dhcpIp(d.serverAddress),
            "Lease" to (if (d.leaseDuration > 0) "${d.leaseDuration / 60} menit" else "-")
        )))
    }

    // ---------- tab 1: scan jaringan ----------

    private fun startScan(silent: Boolean) {
        lastScan = System.currentTimeMillis()
        val ok = try { wifi.startScan() } catch (e: SecurityException) { false }
        if (!silent) {
            Toast.makeText(
                this,
                if (ok) "Scanning..." else "Scan dibatasi Android (maks 4x / 2 menit). Menampilkan hasil terakhir.",
                Toast.LENGTH_SHORT
            ).show()
        }
        ui.postDelayed({ if (tab == 1) showTab(1) }, 3500)
    }

    private fun renderScan() {
        content.addView(button("⟳  Scan Ulang") { startScan(false) })
        permCards()
        if (!hasLoc()) return

        if (System.currentTimeMillis() - lastScan > 30000) startScan(true)

        val res = safeScan().sortedByDescending { it.level }
        val connected = try { wifi.connectionInfo?.bssid } catch (e: Exception) { null }

        if (res.isEmpty()) {
            content.addView(card("Belum ada hasil scan", listOf(
                "Cek 1" to "Layanan Lokasi/GPS harus ON",
                "Cek 2" to "Izin Lokasi harus \"Presisi\", bukan \"Perkiraan\"",
                "Cek 3" to "Tunggu 3-5 detik lalu tekan Scan Ulang",
                "Cek 4" to "Matikan mode hemat daya / hemat baterai ekstrem"
            )))
            return
        }

        val g24 = res.filter { it.frequency in 2400..2500 }
        if (g24.isNotEmpty()) {
            val counts = listOf(1, 6, 11).associateWith { c -> g24.count { Math.abs(chan(it.frequency) - c) <= 2 } }
            val best = counts.minByOrNull { it.value }
            content.addView(card("Saran channel 2.4 GHz", listOf(
                "Paling sepi" to "Channel ${best?.key}  (${best?.value} jaringan bentrok)",
                "Kepadatan" to counts.entries.joinToString("  ") { "Ch${it.key}: ${it.value}" }
            )))
        }

        content.addView(tv("${res.size} jaringan ditemukan", 13f, cSub).apply { setPadding(dp(4), 0, 0, dp(8)) })

        for (r in res) {
            val name = if (r.SSID.isNullOrEmpty()) "(Tersembunyi)" else r.SSID
            val qq = quality(r.level)
            val isMe = r.BSSID == connected
            val c = card(
                (if (isMe) "● " else "") + name,
                listOf(
                    "Sinyal" to "${bars(r.level)}  ${r.level} dBm  (${qq.first})",
                    "Band / Ch" to "${band(r.frequency)}  •  Ch ${chan(r.frequency)}  •  ${width(r.channelWidth)}",
                    "Keamanan" to sec(r.capabilities),
                    "BSSID" to r.BSSID
                ),
                if (isMe) cGood else qq.second
            )
            content.addView(c)
        }
    }

    // ---------- tab 2: perangkat ----------

    private var devStatusView: TextView? = null

    private fun renderDevices() {
        content.addView(button(if (scanning) "Scanning..." else "🔍  Scan Perangkat") { scanDevices() })
        val st = tv(devStatus, 13f, cSub)
        st.setPadding(dp(4), 0, dp(4), dp(10))
        devStatusView = st
        content.addView(st)

        for (d in devices) {
            val rows = ArrayList<Pair<String, String>>()
            rows.add("Jenis" to d.kind)
            rows.add("Hostname" to (if (d.host.isEmpty()) "-" else d.host))
            if (d.upnp.isNotEmpty()) rows.add("UPnP" to d.upnp)
            rows.add("MAC" to (if (d.mac == "-") "Diblokir Android 10+" else d.mac))
            if (d.mac != "-") rows.add("Tipe MAC" to macInfo(d.mac))
            rows.add("Port terbuka" to d.ports)
            rows.add("Respon" to (if (d.ms < 0) "-" else "${d.ms} ms"))
            content.addView(card(d.ip + (if (d.tag.isNotEmpty()) "   ${d.tag}" else ""), rows))
        }
        content.addView(tv(
            "Catatan: Android 10+ memblokir pembacaan MAC/ARP, jadi jenis perangkat ditebak dari port & UPnP. Perangkat yang memblokir semua koneksi bisa tidak terdeteksi.",
            11f, cSub
        ).apply { setPadding(dp(4), dp(6), dp(4), dp(20)) })
    }

    private fun macInfo(mac: String): String {
        if (mac.length < 17) return "-"
        val first = mac.substring(0, 2).toIntOrNull(16) ?: return "-"
        if ((first and 2) != 0) return "MAC acak/privat (HP modern)"
        return OUI[mac.substring(0, 8)] ?: "Vendor tidak dikenal"
    }

    private fun readArp(): Map<String, String> {
        val m = HashMap<String, String>()
        try {
            File("/proc/net/arp").forEachLine { l ->
                val p = l.trim().split(Regex("\\s+"))
                if (p.size >= 4 && p[0] != "IP" && p[3] != "00:00:00:00:00:00" && p[2] != "0x0") {
                    m[p[0]] = p[3].uppercase()
                }
            }
        } catch (e: Exception) { }
        return m
    }

    private fun probe(ip: String): Probe? {
        var up = false
        var ms = -1L
        val open = ArrayList<Int>()
        var t = System.currentTimeMillis()
        try {
            if (InetAddress.getByName(ip).isReachable(300)) {
                up = true
                ms = System.currentTimeMillis() - t
            }
        } catch (e: Exception) { }
        for (p in PORTS) {
            t = System.currentTimeMillis()
            try {
                Socket().use { s ->
                    s.connect(InetSocketAddress(ip, p), 120)
                    open.add(p)
                    up = true
                    if (ms < 0) ms = System.currentTimeMillis() - t
                }
            } catch (e: Exception) {
                val m = e.message ?: ""
                if (m.contains("ECONNREFUSED") || m.contains("refused")) {
                    up = true
                    if (ms < 0) ms = System.currentTimeMillis() - t
                }
            }
        }
        return if (up) Probe(ip, ms, open) else null
    }

    private fun ssdp(): Map<String, String> {
        val out = HashMap<String, String>()
        val s = DatagramSocket()
        try {
            s.soTimeout = 400
            val msg = "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 1\r\nST: ssdp:all\r\n\r\n".toByteArray()
            s.send(DatagramPacket(msg, msg.size, InetAddress.getByName("239.255.255.250"), 1900))
            val end = System.currentTimeMillis() + 2500
            val buf = ByteArray(2048)
            val rx = Regex("(?im)^SERVER:\\s*(.+)$")
            while (System.currentTimeMillis() < end) {
                try {
                    val p = DatagramPacket(buf, buf.size)
                    s.receive(p)
                    val txt = String(p.data, 0, p.length)
                    val ip = p.address.hostAddress
                    val server = rx.find(txt)?.groupValues?.get(1)?.trim() ?: ""
                    if (ip != null && server.isNotEmpty() && !out.containsKey(ip)) out[ip] = server
                } catch (e: Exception) { }
            }
        } catch (e: Exception) {
        } finally {
            s.close()
        }
        return out
    }

    private fun guessKind(ports: List<Int>, gw: Boolean, me: Boolean): String = when {
        me -> Build.MANUFACTURER + " " + Build.MODEL + " (HP ini)"
        gw -> "Router / Gateway"
        62078 in ports -> "iPhone / iPad (Apple)"
        7000 in ports -> "Apple TV / AirPlay"
        8009 in ports || 8008 in ports -> "Chromecast / Google TV"
        554 in ports -> "Kamera IP / CCTV"
        9100 in ports -> "Printer"
        5555 in ports -> "Android (ADB terbuka!)"
        445 in ports || 139 in ports -> "PC Windows / NAS"
        22 in ports -> "Linux / perangkat SSH"
        80 in ports || 443 in ports || 8080 in ports -> "Perangkat dengan web UI (IoT/AP/repeater)"
        else -> "Tidak diketahui (kemungkinan HP/laptop, semua port tertutup)"
    }

    private fun scanDevices() {
        if (scanning) return
        val d = wifi.dhcpInfo
        if (d == null || d.ipAddress == 0) {
            devStatus = "Belum terhubung ke WiFi."
            showTab(2)
            return
        }
        scanning = true
        devices = emptyList()
        devStatus = "Mulai scan..."
        showTab(2)

        val myIp = Integer.reverseBytes(d.ipAddress)
        val gwIp = Integer.reverseBytes(d.gateway)
        val base = myIp and -256
        val myStr = ipStr(myIp)
        val gwStr = ipStr(gwIp)
        val prefix = myStr.substringBeforeLast(".") + "."

        Thread {
            val pool = Executors.newFixedThreadPool(64)
            val done = AtomicInteger(0)
            try {
                val tasks = (1..254).map { i ->
                    Callable<Probe?> {
                        val ip = ipStr(base + i)
                        val r: Probe? = if (ip == myStr) Probe(ip, 0L, emptyList())
                        else probe(ip) ?: (if (ip == gwStr) Probe(ip, -1L, emptyList()) else null)
                        val n = done.incrementAndGet()
                        if (n % 6 == 0 || n == 254) {
                            ui.post {
                                devStatus = "Scanning... $n/254"
                                devStatusView?.text = devStatus
                            }
                        }
                        r
                    }
                }
                val found = HashMap<String, Probe>()
                for (f in pool.invokeAll(tasks)) {
                    try { val p = f.get(); if (p != null) found[p.ip] = p } catch (e: Exception) { }
                }

                val arp = readArp()
                for ((ip, _) in arp) {
                    if (ip.startsWith(prefix) && !found.containsKey(ip)) found[ip] = Probe(ip, -1L, emptyList())
                }

                ui.post { devStatus = "Mencari nama perangkat..."; devStatusView?.text = devStatus }

                val nameTasks = found.keys.map { ip ->
                    Callable<Pair<String, String>> {
                        val h = try { InetAddress.getByName(ip).canonicalHostName } catch (e: Exception) { ip }
                        Pair(ip, if (h == ip) "" else h)
                    }
                }
                val names = HashMap<String, String>()
                for (f in pool.invokeAll(nameTasks, 8, TimeUnit.SECONDS)) {
                    try { val r = f.get(); names[r.first] = r.second } catch (e: Exception) { }
                }

                val upnp = ssdp()

                val list = found.values
                    .sortedBy { it.ip.substringAfterLast(".").toIntOrNull() ?: 0 }
                    .map { p ->
                        val isMe = p.ip == myStr
                        val isGw = p.ip == gwStr
                        val tag = if (isMe) "(HP ini)" else if (isGw) "(Router)" else ""
                        Dev(
                            p.ip,
                            if (isMe) "-" else (arp[p.ip] ?: "-"),
                            if (isMe) Build.MODEL else (names[p.ip] ?: ""),
                            tag,
                            if (p.ports.isEmpty()) "-" else p.ports.joinToString(", "),
                            p.ms,
                            guessKind(p.ports, isGw, isMe),
                            upnp[p.ip] ?: ""
                        )
                    }
                ui.post {
                    scanning = false
                    devices = list
                    devStatus = "Selesai: ${list.size} perangkat ditemukan."
                    if (tab == 2) showTab(2)
                }
            } catch (e: Exception) {
                ui.post {
                    scanning = false
                    devStatus = "Scan gagal: ${e.message}"
                    if (tab == 2) showTab(2)
                }
            } finally {
                pool.shutdownNow()
            }
        }.start()
    }
}
