package app.datavarge.export

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import app.datavarge.data.AppDatabase
import app.datavarge.data.Sample
import app.datavarge.data.ServiceStates
import app.datavarge.data.SpeedTest
import app.datavarge.util.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class ExportResult(val fileName: String, val shareUri: Uri, val savedToDownloads: Boolean)

object Exporter {
    private val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }

    suspend fun export(c: Context): ExportResult = withContext(Dispatchers.IO) {
        val db = AppDatabase.get(c)
        val samples = db.samples().allNow()
        val tests = db.speedTests().allNow()
        val name = "datavarge_export_" + SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date()) + ".zip"

        // 1) A copy in app cache for the share sheet.
        val dir = File(c.cacheDir, "exports").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        val local = File(dir, name)
        local.outputStream().use { writeZip(c, it, samples, tests) }

        // 2) The same zip in Downloads (MediaStore: no storage permission needed).
        val saved = runCatching {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "application/zip")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = c.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("insert failed")
            c.contentResolver.openOutputStream(uri)!!.use { out -> local.inputStream().use { it.copyTo(out) } }
        }.onFailure { DebugLog.e("Export to Downloads failed", it) }.isSuccess

        DebugLog.i("Exported ${samples.size} samples, ${tests.size} speed tests -> $name (downloads=$saved)")
        ExportResult(name, FileProvider.getUriForFile(c, c.packageName + ".files", local), saved)
    }

    fun shareIntent(uri: Uri, mime: String = "application/zip"): Intent =
        Intent.createChooser(
            Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
            "Share",
        )

    private fun writeZip(c: Context, out: OutputStream, samples: List<Sample>, tests: List<SpeedTest>) {
        ZipOutputStream(out).use { z ->
            z.putNextEntry(ZipEntry("samples.csv")); z.write(samplesCsv(samples).toByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry("speed_tests.csv")); z.write(speedCsv(tests).toByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry("heatmap.html")); z.write(heatmap(c, samples, tests).toByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry("README.txt")); z.write(README.toByteArray()); z.closeEntry()
        }
    }

    private fun csv(v: Any?): String {
        val s = v?.toString() ?: return ""
        return if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
    }

    fun samplesCsv(samples: List<Sample>): String = buildString {
        appendLine("id,ts_utc_ms,ts_utc,lat,lon,loc_accuracy_m,altitude_m,altitude_accuracy_m,pressure_hpa,speed_mps,carrier_label,sim_key,sim_operator_name,mcc_mnc,network_type,signal_dbm,rsrq,snr,level_0_4,band,pci,cell_id,service_state,is_roaming,wifi_connected,trigger,note")
        samples.forEach { s ->
            appendLine(listOf(
                s.id, s.tsUtcMs, iso.format(Date(s.tsUtcMs)), s.lat, s.lon, s.locAccuracyM, s.altitudeM, s.altitudeAccuracyM,
                s.pressureHpa, s.speedMps, s.carrierLabel, s.simKey, s.simOperatorName, s.mccMnc, s.networkType, s.signalDbm,
                s.rsrq, s.snr, s.level, s.band, s.pci, s.cellId, s.serviceState, s.isRoaming, s.wifiConnected, s.trigger, s.note,
            ).joinToString(",") { csv(it) })
        }
    }

    fun speedCsv(tests: List<SpeedTest>): String = buildString {
        appendLine("id,sample_id,ts_utc_ms,ts_utc,down_mbps,up_mbps,latency_ms,bytes_used,ran_on_cellular,error")
        tests.forEach { t ->
            appendLine(listOf(t.id, t.sampleId, t.tsUtcMs, iso.format(Date(t.tsUtcMs)), t.downMbps, t.upMbps, t.latencyMs, t.bytesUsed, t.ranOnCellular, t.error)
                .joinToString(",") { csv(it) })
        }
    }

    private fun heatmap(c: Context, samples: List<Sample>, tests: List<SpeedTest>): String {
        val bySample = tests.associateBy { it.sampleId }
        val json = buildJsonObject {
            put("exported", iso.format(Date()))
            put("samples", buildJsonArray {
                samples.forEach { s ->
                    val t = bySample[s.id]
                    add(buildJsonObject {
                        put("lat", s.lat); put("lon", s.lon); put("ts", s.tsUtcMs)
                        put("acc", s.locAccuracyM?.let { JsonPrimitive(it) } ?: JsonNull)
                        put("label", s.carrierLabel); put("net", s.networkType)
                        put("dbm", s.signalDbm?.let { JsonPrimitive(it) } ?: JsonNull)
                        put("no_service", ServiceStates.isNoService(s.serviceState))
                        put("trigger", s.trigger)
                        put("note", s.note?.let { JsonPrimitive(it) } ?: JsonNull)
                        put("down", t?.downMbps?.let { JsonPrimitive(it) } ?: JsonNull)
                        put("cell", t?.ranOnCellular?.let { JsonPrimitive(it) } ?: JsonNull)
                    })
                }
            })
        }.toString().replace("</", "<\\/") // never let a note close the <script> tag
        val template = c.assets.open("heatmap_template.html").bufferedReader().use { it.readText() }
        return template.replace("/*__DATA__*/null", json)
    }

    private val README = """
        Datavarge export
        ================

        heatmap.html     Open in a desktop browser (needs internet for the map tiles and Leaflet).
                         Pick a carrier; pick a second one for difference mode. Click a cell for details.
        samples.csv      One row per logged point.
        speed_tests.csv  One row per Test now speed test (joined to samples by sample_id).

        samples.csv fields
          ts_utc_ms / ts_utc     When the sample was taken (UTC).
          lat, lon               Location (WGS84).
          loc_accuracy_m         Horizontal accuracy radius. Samples worse than 50 m are kept but not mapped.
          altitude_m             Mean-sea-level altitude when the phone provides it, else GPS ellipsoid height.
                                 GPS altitude is typically off by 10-30 m: not good enough to tell floors apart.
          altitude_accuracy_m    Vertical accuracy, if known.
          pressure_hpa           Barometer reading (empty on phones without one).
          speed_mps              Ground speed from the location fix.
          carrier_label          Your label for the SIM (Boost, Tello, ...). "Unlabeled" until you name it.
          sim_key                Subscription ID + MCC/MNC, identifies the physical SIM.
          sim_operator_name      Name the SIM reports (MVNOs often report the host network).
          mcc_mnc                Mobile country/network code of the SIM.
          network_type           LTE, NR_NSA (5G non-standalone), NR_SA (5G standalone), UMTS, GSM, none.
          signal_dbm             RSRP for LTE, SS-RSRP for 5G, RSSI for 3G/2G. Empty when there is no service.
          rsrq, snr              Signal quality (RSRQ dB; RSSNR or SS-SINR dB) when available.
          level_0_4              Android's own signal bars.
          band, pci, cell_id     Serving cell details when readable.
          service_state          IN_SERVICE, OUT_OF_SERVICE, EMERGENCY_ONLY (both count as "no service").
          is_roaming, wifi_connected   Flags. Signal is always the cellular signal, even on Wi-Fi.
          trigger                MOVE (moved 20 m), HEARTBEAT (every 10 min while still), MANUAL (Test now).
          note                   Your note from Test now.

        speed_tests.csv fields
          down_mbps, up_mbps, latency_ms   Results (latency = median of 5 small requests).
          bytes_used             Approximate data used.
          ran_on_cellular        true = forced over cellular. Wi-Fi results are never used in carrier stats.
          error                  Why a test failed, if it did.

        Signal bins (dBm): >= -85 Excellent, -85..-95 Good, -95..-105 Fair, -105..-115 Poor, < -115 Very poor.
        Heat map cells are a fixed 20 m grid colored by the median of samples in the cell.

        Copy from the phone with adb:
          adb pull /sdcard/Download/<this zip's name> .
    """.trimIndent()
}
