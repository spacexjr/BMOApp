package br.com.bmoapp.net

import android.util.Log
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface

/** Descoberta local por UDP broadcast (porta 8766). */
object ServerDiscovery {
    const val DISCOVERY_PORT = 8766
    private const val REQUEST = "BMO_DISCOVER_V1"
    private const val TAG = "BmoDiscovery"

    data class FoundServer(
        val name: String,
        val host: String,
        val port: Int,
        val protocol: String,
        val requiresToken: Boolean,
    )

    fun discover(timeoutMs: Long = 2500): List<FoundServer> {
        val found = linkedMapOf<String, FoundServer>()
        try {
            DatagramSocket(null).use { socket ->
                socket.reuseAddress = true
                socket.broadcast = true
                socket.soTimeout = 300
                val payload = REQUEST.toByteArray(Charsets.UTF_8)
                val targets = broadcastAddresses()
                for (address in targets) {
                    try {
                        socket.send(DatagramPacket(payload, payload.size, address, DISCOVERY_PORT))
                    } catch (exc: Exception) {
                        Log.d(TAG, "Falha ao enviar para $address: ${exc.message}")
                    }
                }
                val deadline = System.currentTimeMillis() + timeoutMs
                val buffer = ByteArray(4096)
                while (System.currentTimeMillis() < deadline) {
                    try {
                        val packet = DatagramPacket(buffer, buffer.size)
                        socket.receive(packet)
                        val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
                        val json = JSONObject(text)
                        if (json.optString("service") != "bmo-backend") continue
                        val host = packet.address.hostAddress ?: continue
                        val port = json.optInt("port", 8765)
                        val server = FoundServer(
                            name = json.optString("name", "BMO no PC"),
                            host = host.removePrefix("/"),
                            port = port,
                            protocol = json.optString("protocol", "bmo/1"),
                            requiresToken = json.optBoolean("requires_token", true),
                        )
                        found["${server.host}:${server.port}"] = server
                    } catch (_: java.net.SocketTimeoutException) {
                        // continua até o prazo final
                    } catch (exc: Exception) {
                        Log.d(TAG, "Resposta de descoberta inválida: ${exc.message}")
                    }
                }
            }
        } catch (exc: Exception) {
            Log.w(TAG, "Descoberta indisponível: ${exc.message}")
        }
        return found.values.toList()
    }

    private fun broadcastAddresses(): List<InetAddress> {
        val result = linkedSetOf<InetAddress>()
        try {
            result += InetAddress.getByName("255.255.255.255")
        } catch (_: Exception) {
        }
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val network = interfaces.nextElement()
                if (!network.isUp || network.isLoopback) continue
                for (address in network.interfaceAddresses) {
                    address.broadcast?.let { result += it }
                }
            }
        } catch (exc: Exception) {
            Log.d(TAG, "Não foi possível listar broadcasts: ${exc.message}")
        }
        return result.toList()
    }
}
