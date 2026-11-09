package app.datavarge.util

import android.content.Context
import android.util.Log
import app.datavarge.Config
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Rotating local debug log (debug.log + debug.1.log, about 1 MB each). Never leaves the phone unless exported. */
object DebugLog {
    private const val TAG = "Datavarge"
    private var dir: File? = null
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    fun init(context: Context) {
        dir = File(context.filesDir, "logs").apply { mkdirs() }
    }

    fun file(): File? = dir?.let { File(it, "debug.log") }
    fun files(): List<File> = listOfNotNull(dir?.let { File(it, "debug.1.log") }, file()).filter { it.exists() }

    fun i(msg: String) = write("I", msg, null)
    fun w(msg: String, t: Throwable? = null) = write("W", msg, t)
    fun e(msg: String, t: Throwable? = null) = write("E", msg, t)

    @Synchronized
    private fun write(level: String, msg: String, t: Throwable?) {
        when (level) {
            "E" -> Log.e(TAG, msg, t)
            "W" -> Log.w(TAG, msg, t)
            else -> Log.i(TAG, msg)
        }
        val f = file() ?: return
        try {
            if (f.length() > Config.DEBUG_LOG_MAX_BYTES) {
                val old = File(f.parentFile, "debug.1.log")
                old.delete()
                f.renameTo(old)
            }
            val line = buildString {
                append(fmt.format(Date())).append(' ').append(level).append(' ').append(msg)
                if (t != null) append(" | ").append(t.javaClass.simpleName).append(": ").append(t.message)
                append('\n')
            }
            f.appendText(line)
        } catch (_: Exception) {
        }
    }
}
