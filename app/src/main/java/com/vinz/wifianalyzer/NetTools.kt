package com.vinz.wifianalyzer

import java.io.ByteArrayOutputStream
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL

object NetTools {

    data class Saved(val ssid: String, val pass: String, val sec: String)

    private var rootChecked = false
    private var rootOk = false

    // ---------- shell / root ----------

    fun run(cmd: List<String>, timeoutSec: Int = 5): String? {
        return try {
            val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
            val out = StringBuilder()
            val t = Thread {
                try {
                    p.inputStream.bufferedReader().forEachLine { out.append(it).append('\n') }
                } catch (e: Exception) { }
            }
            t.start()
            val end = System.currentTimeMillis() + timeoutSec * 1000L
            var done = false
            while (System.currentTimeMillis() < end) {
                try {
                    p.exitValue()
                    done = true
                    break
                } catch (e: IllegalThreadStateException) {
                    Thread.sleep(50)
                }
            }
            if (!done) p.destroy()
            t.join(800)
            out.toString()
        } catch (e: Exception) {
            null
        }
    }

    fun rootGranted(): Boolean = rootChecked && rootOk

    // akan memunculkan pop-up izin root (Magisk/KernelSU) kalau HP di-root
    fun hasRoot(force: Boolean = false): Boolean {
        if (rootChecked && !force) return rootOk
        val r = run(listOf("su", "-c", "id"), 20)
        rootOk = r != null && r.contains("uid=0")
        rootChecked = true
        return rootOk
    }

    private fun su(cmd: String): String? = if (hasRoot()) run(listOf("su", "-c", cmd), 8) else null

    // ---------- ARP / MAC ----------

    private fun parseNeigh(txt: String?, m: HashMap<String, String>) {
        if (txt == null) return
        val rx = Regex("^(\\d+\\.\\d+\\.\\d+\\.\\d+).*lladdr\\s+([0-9a-fA-F:]{17})")
        for (l in txt.lines()) {
            val r = rx.find(l.trim())
            if (r != null) m[r.groupValues[1]] = r.groupValues[2].uppercase()
        }
    }

    // return: (ip -> mac, sumber)
    fun arp(): Pair<Map<String, String>, String> {
        val m = HashMap<String, String>()
        try {
            File("/proc/net/arp").forEachLine { l ->
                val p = l.trim().split(Regex("\\s+"))
                if (p.size >= 4 && p[0] != "IP" && p[3] != "00:00:00:00:00:00" && p[2] != "0x0") {
                    m[p[0]] = p[3].uppercase()
                }
            }
        } catch (e: Exception) { }
        if (m.isNotEmpty()) return Pair(m, "/proc/net/arp")

        parseNeigh(run(listOf("ip", "neigh")), m)
        if (m.isNotEmpty()) return Pair(m, "ip neigh")

        if (rootGranted()) {
            parseNeigh(run(listOf("su", "-c", "ip neigh"), 8), m)
            if (m.isNotEmpty()) return Pair(m, "root")
        }
        return Pair(m, "")
    }

    fun isRandomMac(mac: String): Boolean {
        if (mac.length < 17) return false
        val f = mac.substring(0, 2).toIntOrNull(16) ?: return false
        return (f and 2) != 0
    }

    fun vendor(mac: String): String {
        try {
            val c = URL("https://api.macvendors.com/$mac").openConnection() as HttpURLConnection
            c.connectTimeout = 3000
            c.readTimeout = 3000
            if (c.responseCode == 200) {
                val t = c.inputStream.bufferedReader().use { it.readText() }.trim()
                c.disconnect()
                return t.take(40)
            }
            c.disconnect()
        } catch (e: Exception) { }
        return ""
    }

    // ---------- DNS packet helpers ----------

    private fun u16(b: ByteArray, o: Int): Int =
        if (o + 1 < b.size) ((b[o].toInt() and 255) shl 8) or (b[o + 1].toInt() and 255) else 0

    private fun readName(b: ByteArray, start: Int): Pair<String, Int> {
        val sb = StringBuilder()
        var off = start
        var end = -1
        var guard = 0
        while (off < b.size && guard++ < 50) {
            val len = b[off].toInt() and 0xFF
            if (len == 0) {
                off++
                break
            }
            if ((len and 0xC0) == 0xC0) {
                if (off + 1 >= b.size) break
                val ptr = ((len and 0x3F) shl 8) or (b[off + 1].toInt() and 0xFF)
                if (end < 0) end = off + 2
                off = ptr
                continue
            }
            off++
            if (off + len > b.size) break
            if (sb.isNotEmpty()) sb.append('.')
            sb.append(String(b, off, len))
            off += len
        }
        return Pair(sb.toString(), if (end >= 0) end else off)
    }

    // ---------- mDNS (iPhone, Mac, printer, Linux, dll) ----------

    fun mdnsName(ip: String): String {
        val parts = ip.split(".")
        if (parts.size != 4) return ""
        var s: DatagramSocket? = null
        try {
            val qname = "${parts[3]}.${parts[2]}.${parts[1]}.${parts[0]}.in-addr.arpa"
            val bo = ByteArrayOutputStream()
            bo.write(byteArrayOf(0x12, 0x34, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0))
            for (label in qname.split(".")) {
                bo.write(label.length)
                bo.write(label.toByteArray())
            }
            bo.write(0)
            bo.write(0); bo.write(12) // PTR
            bo.write(0); bo.write(1)  // IN
            val q = bo.toByteArray()

            s = DatagramSocket()
            s.soTimeout = 700
            s.send(DatagramPacket(q, q.size, InetAddress.getByName(ip), 5353))
            val buf = ByteArray(1500)
            val pkt = DatagramPacket(buf, buf.size)
            s.receive(pkt)
            val b = pkt.data
            val n = pkt.length
            if (n < 12) return ""
            val qd = u16(b, 4)
            val an = u16(b, 6)
            var off = 12
            for (i in 0 until qd) off = readName(b, off).second + 4
            for (i in 0 until an) {
                if (off >= n) return ""
                off = readName(b, off).second
                val type = u16(b, off)
                off += 8
                val rdlen = u16(b, off)
                off += 2
                if (type == 12) {
                    return readName(b, off).first.removeSuffix(".").removeSuffix(".local")
                }
                off += rdlen
            }
        } catch (e: Exception) {
        } finally {
            try { s?.close() } catch (e: Exception) { }
        }
        return ""
    }

    // ---------- NetBIOS (Windows, Samba) ----------

    fun netbiosName(ip: String): String {
        var s: DatagramSocket? = null
        try {
            val bo = ByteArrayOutputStream()
            bo.write(byteArrayOf(0x13, 0x37, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0))
            bo.write(0x20)
            bo.write(("CK" + "A".repeat(30)).toByteArray())
            bo.write(0)
            bo.write(0); bo.write(0x21)
            bo.write(0); bo.write(1)
            val q = bo.toByteArray()

            s = DatagramSocket()
            s.soTimeout = 600
            s.send(DatagramPacket(q, q.size, InetAddress.getByName(ip), 137))
            val buf = ByteArray(1024)
            val pkt = DatagramPacket(buf, buf.size)
            s.receive(pkt)
            if (pkt.length < 58) return ""
            val cnt = pkt.data[56].toInt() and 255
            if (cnt <= 0) return ""
            val name = String(pkt.data, 57, 15).trim()
            if (name.any { it.code < 32 }) return ""
            return name
        } catch (e: Exception) {
        } finally {
            try { s?.close() } catch (e: Exception) { }
        }
        return ""
    }

    // ---------- SSDP / UPnP (TV, router, Chromecast, dll) ----------

    // return: ip -> (server, location)
    fun ssdp(): Map<String, Pair<String, String>> {
        val out = HashMap<String, Pair<String, String>>()
        var s: DatagramSocket? = null
        try {
            s = DatagramSocket()
            s.soTimeout = 400
            val msg = ("M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 1\r\nST: upnp:rootdevice\r\n\r\n").toByteArray()
            val grp = InetAddress.getByName("239.255.255.250")
            s.send(DatagramPacket(msg, msg.size, grp, 1900))
            val end = System.currentTimeMillis() + 2500
            val buf = ByteArray(2048)
            val rxS = Regex("(?im)^SERVER:\\s*(.+)$")
            val rxL = Regex("(?im)^LOCATION:\\s*(.+)$")
            var resent = false
            while (System.currentTimeMillis() < end) {
                if (!resent && System.currentTimeMillis() > end - 1500) {
                    s.send(DatagramPacket(msg, msg.size, grp, 1900))
                    resent = true
                }
                try {
                    val p = DatagramPacket(buf, buf.size)
                    s.receive(p)
                    val txt = String(p.data, 0, p.length)
                    val ip = p.address.hostAddress ?: continue
                    val server = rxS.find(txt)?.groupValues?.get(1)?.trim() ?: ""
                    val loc = rxL.find(txt)?.groupValues?.get(1)?.trim() ?: ""
                    val prev = out[ip]
                    if (prev == null || prev.second.isEmpty()) out[ip] = Pair(server, loc)
                } catch (e: Exception) { }
            }
        } catch (e: Exception) {
        } finally {
            try { s?.close() } catch (e: Exception) { }
        }
        return out
    }

    private fun tag(txt: String, t: String): String =
        Regex("<$t>([^<]*)</$t>", RegexOption.IGNORE_CASE).find(txt)?.groupValues?.get(1)?.trim() ?: ""

    // return: (friendlyName, "produsen model")
    fun upnpInfo(location: String, ip: String): Pair<String, String> {
        try {
            val u = URL(location)
            if (u.host != ip) return Pair("", "")
            val c = u.openConnection() as HttpURLConnection
            c.connectTimeout = 1200
            c.readTimeout = 1500
            val txt = c.inputStream.bufferedReader().use { it.readText() }.take(30000)
            c.disconnect()
            val fn = tag(txt, "friendlyName")
            val mf = tag(txt, "manufacturer")
            val md = tag(txt, "modelName")
            return Pair(fn, listOf(mf, md).filter { it.isNotEmpty() }.joinToString(" "))
        } catch (e: Exception) { }
        return Pair("", "")
    }

    fun httpTitle(ip: String, port: Int): String {
        try {
            val c = URL("http://$ip:$port/").openConnection() as HttpURLConnection
            c.connectTimeout = 1000
            c.readTimeout = 1200
            c.instanceFollowRedirects = true
            val txt = c.inputStream.bufferedReader().use { it.readText() }.take(8000)
            c.disconnect()
            val m = Regex("<title>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).find(txt)
            return m?.groupValues?.get(1)?.trim()?.replace(Regex("\\s+"), " ")?.take(60) ?: ""
        } catch (e: Exception) { }
        return ""
    }

    // ---------- password WiFi tersimpan di HP ini (butuh root) ----------

    private fun unx(s: String): String {
        var r = s.replace("&quot;", "\"").replace("&apos;", "'")
            .replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
        if (r.length >= 2 && r.startsWith("\"") && r.endsWith("\"")) r = r.substring(1, r.length - 1)
        return r
    }

    private fun xmlStr(blk: String, name: String): String {
        val m = Regex("<string name=\"$name\">([^<]*)</string>").find(blk)
        return if (m == null) "" else unx(m.groupValues[1])
    }

    private fun parseStore(x: String): List<Saved> {
        val out = ArrayList<Saved>()
        for (blk in x.split("<Network>").drop(1)) {
            val ssid = xmlStr(blk, "SSID")
            if (ssid.isEmpty()) continue
            val psk = xmlStr(blk, "PreSharedKey")
            val ck = xmlStr(blk, "ConfigKey")
            val sec = when {
                ck.contains("SAE") -> "WPA3"
                ck.contains("WPA_PSK") -> "WPA/WPA2"
                ck.contains("WPA_EAP") -> "Enterprise"
                ck.contains("NONE") -> "Terbuka"
                else -> ck
            }
            out.add(Saved(ssid, psk, sec))
        }
        return out.sortedBy { it.ssid.lowercase() }
    }

    private fun parseWpa(x: String): List<Saved> {
        val out = ArrayList<Saved>()
        for (blk in x.split("network={").drop(1)) {
            val ssid = Regex("ssid=\"([^\"]*)\"").find(blk)?.groupValues?.get(1) ?: continue
            val psk = Regex("psk=\"([^\"]*)\"").find(blk)?.groupValues?.get(1) ?: ""
            out.add(Saved(ssid, psk, if (psk.isEmpty()) "Terbuka" else "WPA/WPA2"))
        }
        return out.sortedBy { it.ssid.lowercase() }
    }

    // return: (daftar, pesan error kalau gagal)
    fun savedWifi(): Pair<List<Saved>, String> {
        if (!hasRoot(true)) {
            return Pair(emptyList(), "Root tidak tersedia atau ditolak. Tanpa root: Pengaturan WiFi → pilih jaringan → Bagikan (QR).")
        }
        val paths = listOf(
            "/data/misc/apexdata/com.android.wifi/WifiConfigStore.xml",
            "/data/misc/wifi/WifiConfigStore.xml"
        )
        for (p in paths) {
            val x = su("cat $p")
            if (x != null && x.contains("<Network>")) return Pair(parseStore(x), "")
        }
        val w = su("cat /data/misc/wifi/wpa_supplicant.conf")
        if (w != null && w.contains("network={")) return Pair(parseWpa(w), "")
        return Pair(emptyList(), "Root aktif, tapi file konfigurasi WiFi tidak ditemukan di HP ini.")
    }
}
