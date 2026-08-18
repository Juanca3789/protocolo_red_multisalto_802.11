package co.uan.pct.lib.core.internal.ctrl

import android.net.Network
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Escucha HELLO en [port] (SoftAP/GO) y envía HELLO por la red STA del padre.
 */
class HelloHub(
    private val scope: CoroutineScope,
    private val port: Int,
) {
    private val _hellos = MutableSharedFlow<HelloPayload>(extraBufferCapacity = 16)
    val hellos: SharedFlow<HelloPayload> = _hellos.asSharedFlow()

    private var listenJob: Job? = null
    private var sendJob: Job? = null
    private var listenSocket: DatagramSocket? = null
    private val listening = AtomicBoolean(false)

    fun startListening() {
        if (!listening.compareAndSet(false, true)) return
        listenJob = scope.launch(Dispatchers.IO) {
            val socket = runCatching {
                DatagramSocket(null).apply {
                    reuseAddress = true
                    bind(InetSocketAddress(port))
                    soTimeout = 1_000
                }
            }.getOrElse {
                listening.set(false)
                return@launch
            }
            listenSocket = socket
            val buf = ByteArray(512)
            try {
                while (isActive && listening.get()) {
                    val packet = DatagramPacket(buf, buf.size)
                    val ok = runCatching {
                        socket.receive(packet)
                        true
                    }.getOrDefault(false)
                    if (!ok) continue
                    val payload = HelloCodec.decode(packet.data, packet.length) ?: continue
                    _hellos.tryEmit(payload)
                }
            } catch (_: SocketException) {
                // cerrado
            } finally {
                runCatching { socket.close() }
                listenSocket = null
                listening.set(false)
            }
        }
    }

    fun stopListening() {
        listening.set(false)
        listenJob?.cancel()
        listenJob = null
        runCatching { listenSocket?.close() }
        listenSocket = null
    }

    /**
     * Envío periódico de HELLO hacia el SoftAP del padre (broadcast + gateway típico).
     * [network] = red STA (WifiNetworkSpecifier); si es null, usa la ruta por defecto.
     */
    fun startSending(
        network: Network?,
        payload: HelloPayload,
        intervalMs: Long = 2_000L,
    ) {
        stopSending()
        sendJob = scope.launch(Dispatchers.IO) {
            // Ráfaga inicial + keep-alive mientras STA siga conectado
            var burst = 0
            while (isActive) {
                sendOnce(network, payload)
                burst++
                delay(if (burst < 5) 500L else intervalMs)
            }
        }
    }

    fun stopSending() {
        sendJob?.cancel()
        sendJob = null
    }

    fun close() {
        stopSending()
        stopListening()
    }

    private suspend fun sendOnce(network: Network?, payload: HelloPayload) {
        withContext(Dispatchers.IO) {
            val data = HelloCodec.encode(payload)
            val targets = listOf(
                "255.255.255.255",
                "192.168.49.1", // gateway SoftAP GO típico Android
            )
            for (host in targets) {
                runCatching {
                    DatagramSocket().use { socket ->
                        socket.broadcast = true
                        network?.bindSocket(socket)
                        val addr = InetAddress.getByName(host)
                        socket.send(DatagramPacket(data, data.size, addr, port))
                    }
                }
            }
        }
    }
}
