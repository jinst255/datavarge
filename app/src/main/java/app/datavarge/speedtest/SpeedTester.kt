package app.datavarge.speedtest

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import app.datavarge.Config
import app.datavarge.util.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

data class SpeedResult(
    val downMbps: Double?,
    val upMbps: Double?,
    val latencyMs: Double?,
    val bytesUsed: Long,
    val ranOnCellular: Boolean,
    val error: String?,
)

enum class SpeedPhase { CONNECTING, LATENCY, DOWNLOAD, UPLOAD }

/**
 * Speed test forced over the cellular network even when Wi-Fi is connected: request a
 * TRANSPORT_CELLULAR + INTERNET network, then bind OkHttp's sockets and DNS to it.
 */
object SpeedTester {
    const val MSG_NO_CELLULAR = "Couldn't reach the cellular network. Speed test skipped."
    const val MSG_TIMEOUT = "Speed test timed out. Signal was saved."

    suspend fun run(context: Context, onPhase: (SpeedPhase) -> Unit = {}): SpeedResult = withContext(Dispatchers.IO) {
        onPhase(SpeedPhase.CONNECTING)
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val holder = arrayOfNulls<ConnectivityManager.NetworkCallback>(1)
        val network = withTimeoutOrNull(Config.SPEED_NETWORK_TIMEOUT_MS) { requestCellular(cm, holder) }
        try {
            if (network == null) {
                DebugLog.w("Speed test: no cellular network")
                return@withContext SpeedResult(null, null, null, 0, false, MSG_NO_CELLULAR)
            }
            val caps = cm.getNetworkCapabilities(network)
            val cellular = caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
            if (!cellular) return@withContext SpeedResult(null, null, null, 0, false, MSG_NO_CELLULAR)

            val client = OkHttpClient.Builder()
                .socketFactory(network.socketFactory)
                .dns { host -> network.getAllByName(host).toList() }
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(Config.SPEED_PHASE_LIMIT_MS, TimeUnit.MILLISECONDS)
                .writeTimeout(Config.SPEED_PHASE_LIMIT_MS, TimeUnit.MILLISECONDS)
                .callTimeout(Config.SPEED_PHASE_LIMIT_MS + 3_000, TimeUnit.MILLISECONDS)
                .build()
            var bytes = 0L
            var error: String? = null

            onPhase(SpeedPhase.LATENCY)
            val latency = runCatching { latency(client) }.onFailure { DebugLog.w("latency failed", it) }.getOrNull()
            bytes += 5_000

            onPhase(SpeedPhase.DOWNLOAD)
            val down = runCatching { download(client) }.onFailure { DebugLog.w("download failed", it) }.getOrNull()
            bytes += down?.second ?: 0

            onPhase(SpeedPhase.UPLOAD)
            val up = runCatching { upload(client) }.onFailure { DebugLog.w("upload failed", it) }.getOrNull()
            bytes += up?.second ?: 0

            if (latency == null && down == null && up == null) error = MSG_TIMEOUT
            client.connectionPool.evictAll()
            SpeedResult(down?.first, up?.first, latency, bytes, true, error)
        } finally {
            holder[0]?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        }
    }

    private suspend fun requestCellular(
        cm: ConnectivityManager,
        holder: Array<ConnectivityManager.NetworkCallback?>,
    ): Network = suspendCancellableCoroutine { cont ->
        val req = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { if (cont.isActive) cont.resume(network) }
        }
        holder[0] = cb
        cm.requestNetwork(req, cb)
    }

    private fun req(path: String) = Request.Builder()
        .url(Config.SPEED_BASE_URL + path)
        .header("User-Agent", Config.USER_AGENT)
        .header("Cache-Control", "no-cache")

    /** Median of 5 tiny requests after one warm-up (the warm-up pays for DNS + TLS). */
    private fun latency(client: OkHttpClient): Double? {
        client.newCall(req("/__down?bytes=0").build()).execute().use { it.body.bytes() }
        val times = (1..Config.SPEED_LATENCY_PINGS).mapNotNull {
            val t0 = System.nanoTime()
            runCatching {
                client.newCall(req("/__down?bytes=0").build()).execute().use { r -> r.body.bytes(); if (!r.isSuccessful) error("HTTP ${r.code}") }
                (System.nanoTime() - t0) / 1e6
            }.getOrNull()
        }.sorted()
        return if (times.isEmpty()) null else times[times.size / 2]
    }

    /** Returns (Mbps, bytes). Stops at the byte target or the 12 s phase limit, whichever is first. */
    private fun download(client: OkHttpClient): Pair<Double, Long> {
        val t0 = System.nanoTime()
        var total = 0L
        client.newCall(req("/__down?bytes=${Config.SPEED_DOWN_BYTES}").build()).execute().use { r ->
            if (!r.isSuccessful) error("HTTP ${r.code}")
            val src = r.body.source()
            val buf = ByteArray(64 * 1024)
            val stream = src.inputStream()
            while (true) {
                val n = stream.read(buf)
                if (n < 0) break
                total += n
                if ((System.nanoTime() - t0) / 1_000_000 > Config.SPEED_PHASE_LIMIT_MS) break
            }
        }
        val secs = (System.nanoTime() - t0) / 1e9
        return mbps(total, secs) to total
    }

    private fun upload(client: OkHttpClient): Pair<Double, Long> {
        val t0 = System.nanoTime()
        var sent = 0L
        val body = object : RequestBody() {
            override fun contentType() = "application/octet-stream".toMediaType()
            override fun contentLength() = Config.SPEED_UP_BYTES
            override fun writeTo(sink: BufferedSink) {
                val chunk = ByteArray(64 * 1024) { (it * 31).toByte() }
                while (sent < Config.SPEED_UP_BYTES) {
                    val n = minOf(chunk.size.toLong(), Config.SPEED_UP_BYTES - sent).toInt()
                    sink.write(chunk, 0, n)
                    sent += n
                    if ((System.nanoTime() - t0) / 1_000_000 > Config.SPEED_PHASE_LIMIT_MS) throw java.io.IOException("phase limit")
                }
            }
        }
        val ok = runCatching {
            client.newCall(req("/__up").post(body).build()).execute().use { it.body.bytes() }
        }
        val secs = (System.nanoTime() - t0) / 1e9
        if (ok.isFailure && sent < 256 * 1024) throw ok.exceptionOrNull()!!
        return mbps(sent, secs) to sent
    }

    private fun mbps(bytes: Long, secs: Double) = if (secs <= 0) 0.0 else bytes * 8 / secs / 1_000_000
}
