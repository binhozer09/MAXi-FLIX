package com.maxiflix.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A per-app DNS tunnel. Only MAXi-FLIX is allowed through this VPN.
 * Normal app traffic is not routed through the tunnel; only packets addressed
 * to Cloudflare's DNS resolvers (UDP/53) are intercepted and forwarded.
 */
class CloudflareDnsVpnService : VpnService() {

    companion object {
        const val ACTION_DNS_READY = "com.maxiflix.app.ACTION_DNS_READY"
        const val ACTION_DNS_FAILED = "com.maxiflix.app.ACTION_DNS_FAILED"

        private const val CHANNEL_ID = "maxiflix_cloudflare_dns"
        private const val NOTIFICATION_ID = 1101
        private const val PRIMARY_DNS = "1.1.1.1"
        private const val SECONDARY_DNS = "1.0.0.1"
        // Lista local de domínios publicitários e hosts maliciosos observados no MAXi-FLIX.
        // O bloqueio ocorre somente no DNS do próprio aplicativo.
        private val BLOCKED_DNS_DOMAINS = setOf(
            "doubleclick.net", "googlesyndication.com", "googleadservices.com",
            "googletagservices.com", "adservice.google.com", "adnxs.com",
            "adform.net", "taboola.com", "outbrain.com", "criteo.com",
            "pubmatic.com", "openx.net", "rubiconproject.com", "casalemedia.com",
            "smartadserver.com", "adroll.com", "amazon-adsystem.com",
            "popads.net", "popcash.net", "propellerads.com", "adsterra.com",
            "exoclick.com", "trafficjunky.net", "juicyads.com", "hilltopads.net",
            "onclickads.net", "trafficstars.com", "monetag.com", "push.house",
            "pushwelcome.com", "richpush.co", "media.net", "sharethrough.com",
            "bidswitch.net", "bidvertiser.com", "yieldmo.com", "smaato.com",
            "inmobi.com", "applovin.com", "ironsrc.com", "vungle.com",
            "tapjoy.com", "adcolony.com", "adpushup.com", "mgid.com",
            "revcontent.com", "connatix.com", "pmbmonetize.live",
            "ribsiuds.qpon", "slubplenism.com", "subduepaler.cyou"
        )
    }

    private val running = AtomicBoolean(false)

    @Volatile
    private var vpnInterface: ParcelFileDescriptor? = null

    @Volatile
    private var packetThread: Thread? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (vpnInterface == null) establishDnsTunnel()
        if (vpnInterface != null) {
            // Establish the VPN first so Android recognizes this as an active VPN app
            // when validating the system-exempted foreground-service type.
            startForeground(NOTIFICATION_ID, buildNotification())
        }
        return START_STICKY
    }

    private fun establishDnsTunnel() {
        try {
            val builder = Builder()
                .setSession("MAXi-FLIX — DNS Cloudflare")
                .setMtu(1500)
                .addAddress("10.111.222.1", 32)
                .addDnsServer(PRIMARY_DNS)
                .addDnsServer(SECONDARY_DNS)
                .addRoute(PRIMARY_DNS, 32)
                .addRoute(SECONDARY_DNS, 32)
                .addAllowedApplication(packageName)

            val established = builder.establish()
                ?: throw IllegalStateException("O Android não permitiu criar a conexão DNS.")

            vpnInterface = established
            running.set(true)

            sendBroadcast(
                Intent(ACTION_DNS_READY).setPackage(packageName)
            )

            packetThread = Thread(
                { processPackets(established) },
                "MAXi-FLIX-DNS"
            ).apply {
                isDaemon = true
                start()
            }
        } catch (error: Exception) {
            sendBroadcast(
                Intent(ACTION_DNS_FAILED)
                    .setPackage(packageName)
                    .putExtra("reason", error.message ?: "Não foi possível ativar o DNS.")
            )
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun processPackets(descriptor: ParcelFileDescriptor) {
        val input = FileInputStream(descriptor.fileDescriptor)
        val output = FileOutputStream(descriptor.fileDescriptor)
        val packet = ByteArray(32767)

        try {
            while (running.get() && !Thread.currentThread().isInterrupted) {
                val length = input.read(packet)
                if (length <= 0) continue
                handleDnsPacket(packet, length, output)
            }
        } catch (_: Exception) {
            // Closing the VPN descriptor during shutdown ends this loop.
        }
    }

    private fun handleDnsPacket(
        packet: ByteArray,
        packetLength: Int,
        output: FileOutputStream
    ) {
        if (packetLength < 28) return
        if (((packet[0].toInt() ushr 4) and 0x0f) != 4) return

        val ipHeaderLength = (packet[0].toInt() and 0x0f) * 4
        if (ipHeaderLength < 20 || packetLength < ipHeaderLength + 8) return
        if ((packet[9].toInt() and 0xff) != 17) return // UDP only

        val fragmentField = readU16(packet, 6)
        if ((fragmentField and 0x1fff) != 0) return

        val sourcePort = readU16(packet, ipHeaderLength)
        val destinationPort = readU16(packet, ipHeaderLength + 2)
        if (destinationPort != 53) return

        val udpLength = readU16(packet, ipHeaderLength + 4)
        if (udpLength < 8 || ipHeaderLength + udpLength > packetLength) return

        val destinationIp = packet.copyOfRange(16, 20)
        if (!isCloudflareAddress(destinationIp)) return

        val dnsLength = udpLength - 8
        if (dnsLength <= 0) return

        val dnsQuery = packet.copyOfRange(
            ipHeaderLength + 8,
            ipHeaderLength + 8 + dnsLength
        )

        val queriedHost = extractDnsQueryHost(dnsQuery)
        val dnsResponse = if (queriedHost != null && isBlockedDnsHost(queriedHost)) {
            android.util.Log.d("MAXI_FLIX_DNS", "Blocked ad domain: $queriedHost")
            buildNxDomainResponse(dnsQuery)
        } else {
            queryCloudflare(destinationIp, dnsQuery) ?: return
        }
        if (dnsResponse.isEmpty() || dnsResponse.size > 1472) return

        val sourceIp = packet.copyOfRange(12, 16)
        val responsePacket = buildUdpResponse(
            sourceIp = destinationIp,
            destinationIp = sourceIp,
            sourcePort = 53,
            destinationPort = sourcePort,
            dnsPayload = dnsResponse,
            requestPacket = packet
        )

        synchronized(output) {
            output.write(responsePacket)
            output.flush()
        }
    }

    private fun extractDnsQueryHost(query: ByteArray): String? {
        if (query.size < 17) return null
        var offset = 12
        val labels = ArrayList<String>()
        while (offset < query.size) {
            val length = query[offset].toInt() and 0xff
            offset++
            if (length == 0) {
                return if (labels.isEmpty()) null
                else labels.joinToString(".").lowercase()
            }
            if (length > 63 || offset + length > query.size) return null
            val label = query.copyOfRange(offset, offset + length)
                .toString(Charsets.US_ASCII)
            if (label.isBlank() || label.any { it.code < 33 || it.code > 126 }) return null
            labels.add(label)
            if (labels.size > 127) return null
            offset += length
        }
        return null
    }

    private fun isBlockedDnsHost(host: String): Boolean {
        val normalized = host.trimEnd('.').lowercase()
        return BLOCKED_DNS_DOMAINS.any { domain ->
            normalized == domain || normalized.endsWith(".$domain")
        }
    }

    private fun buildNxDomainResponse(query: ByteArray): ByteArray {
        // Responde NXDOMAIN localmente, sem consultar o resolvedor externo.
        val response = query.copyOf()
        response[2] = (response[2].toInt() or 0x80).toByte() // QR = response
        response[3] = ((response[3].toInt() and 0x70) or 0x83).toByte() // RA + NXDOMAIN
        response[6] = 0
        response[7] = 0
        response[8] = 0
        response[9] = 0
        response[10] = 0
        response[11] = 0
        return response
    }

    private fun queryCloudflare(
        requestedServer: ByteArray,
        query: ByteArray
    ): ByteArray? {
        val requestedAddress = InetAddress.getByAddress(requestedServer)
        val fallbackAddress = InetAddress.getByName(
            if (requestedAddress.hostAddress == PRIMARY_DNS) SECONDARY_DNS else PRIMARY_DNS
        )
        val servers = listOf(requestedAddress, fallbackAddress)

        for (server in servers) {
            val response = runCatching {
                DatagramSocket().use { socket ->
                    if (!protect(socket)) {
                        throw IllegalStateException("Não foi possível proteger a conexão DNS.")
                    }
                    socket.soTimeout = 3500
                    socket.send(
                        DatagramPacket(query, query.size, server, 53)
                    )
                    val buffer = ByteArray(4096)
                    val responsePacket = DatagramPacket(buffer, buffer.size)
                    socket.receive(responsePacket)
                    buffer.copyOf(responsePacket.length)
                }
            }.getOrNull()

            if (response != null && response.isNotEmpty()) return response
        }

        return null
    }

    private fun buildUdpResponse(
        sourceIp: ByteArray,
        destinationIp: ByteArray,
        sourcePort: Int,
        destinationPort: Int,
        dnsPayload: ByteArray,
        requestPacket: ByteArray
    ): ByteArray {
        val ipHeaderLength = 20
        val udpHeaderLength = 8
        val totalLength = ipHeaderLength + udpHeaderLength + dnsPayload.size
        val response = ByteArray(totalLength)

        response[0] = 0x45
        response[1] = 0
        writeU16(response, 2, totalLength)
        response[4] = requestPacket[4]
        response[5] = requestPacket[5]
        writeU16(response, 6, 0x4000)
        response[8] = 64
        response[9] = 17
        sourceIp.copyInto(response, 12)
        destinationIp.copyInto(response, 16)
        writeU16(response, 10, internetChecksum(response, 0, ipHeaderLength))

        writeU16(response, 20, sourcePort)
        writeU16(response, 22, destinationPort)
        writeU16(response, 24, udpHeaderLength + dnsPayload.size)
        writeU16(response, 26, 0) // UDP checksum is optional for IPv4.

        dnsPayload.copyInto(response, ipHeaderLength + udpHeaderLength)
        return response
    }

    private fun isCloudflareAddress(address: ByteArray): Boolean {
        val host = address.joinToString(".") { (it.toInt() and 0xff).toString() }
        return host == PRIMARY_DNS || host == SECONDARY_DNS
    }

    private fun readU16(bytes: ByteArray, offset: Int): Int {
        return ((bytes[offset].toInt() and 0xff) shl 8) or
            (bytes[offset + 1].toInt() and 0xff)
    }

    private fun writeU16(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = ((value ushr 8) and 0xff).toByte()
        bytes[offset + 1] = (value and 0xff).toByte()
    }

    private fun internetChecksum(bytes: ByteArray, offset: Int, length: Int): Int {
        var sum = 0L
        var index = offset
        val end = offset + length

        while (index + 1 < end) {
            sum += (((bytes[index].toInt() and 0xff) shl 8) or
                (bytes[index + 1].toInt() and 0xff)).toLong()
            index += 2
        }
        if (index < end) {
            sum += ((bytes[index].toInt() and 0xff) shl 8).toLong()
        }

        while ((sum ushr 16) != 0L) {
            sum = (sum and 0xffffL) + (sum ushr 16)
        }
        return sum.inv().toInt() and 0xffff
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "DNS automático do MAXi-FLIX",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Mostra quando o DNS Cloudflare está ativo no MAXi-FLIX."
            }
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle("MAXi-FLIX — DNS Cloudflare ativo")
            .setContentText("Usando 1.1.1.1 somente neste aplicativo")
            .setContentIntent(openApp)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onRevoke() {
        stopSelf()
        super.onRevoke()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        running.set(false)
        packetThread?.interrupt()
        runCatching { vpnInterface?.close() }
        vpnInterface = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}
