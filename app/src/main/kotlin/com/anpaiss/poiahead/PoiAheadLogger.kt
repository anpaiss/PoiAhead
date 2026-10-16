package com.anpaiss.poiahead

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Logger su file (stesso schema di BetterEta, con mirror su Logcat).
 *
 * Recupero: adb pull /sdcard/Android/data/com.anpaiss.poiahead/files/poi_ahead.log
 * Live:     adb logcat -s PoiAhead
 */
class PoiAheadLogger(context: Context) {

    private val file: File
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private var writeFailLogged = false

    companion object {
        /** Logger dell'estensione, per i receiver (manifest) che vivono nello stesso processo. */
        @Volatile var shared: PoiAheadLogger? = null

        private const val MAX_SIZE_BYTES = 1024 * 1024L  // 1 MB
        private const val LOGCAT_TAG     = "PoiAhead"
    }

    init {
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        file = File(dir, "poi_ahead.log")
        log("SYS", "=== PoiAhead avviato ===")
        log("SYS", "Log: ${file.absolutePath}")
    }

    /** Scrive una riga timestampata. Thread-safe. */
    @Synchronized
    fun log(tag: String, message: String) {
        Log.d(LOGCAT_TAG, "[${tag.trim()}] $message")
        runCatching {
            if (file.length() > MAX_SIZE_BYTES) rotate()
            file.appendText("${fmt.format(Date())} [${tag.padEnd(5)}] $message\n")
            writeFailLogged = false
        }.onFailure { e ->
            if (!writeFailLogged) {
                writeFailLogged = true
                Log.e(LOGCAT_TAG, "Scrittura log su file fallita: ${file.absolutePath}", e)
            }
        }
    }

    /** Come [log], ma accoda anche lo stacktrace dell'eccezione. */
    fun logError(tag: String, message: String, t: Throwable) {
        log(tag, "$message: ${t::class.simpleName}: ${t.message}")
        t.stackTraceToString().lineSequence()
            .filter { it.isNotBlank() }
            .take(15)
            .forEach { log(tag, "    ${it.trim()}") }
    }

    private fun rotate() {
        val old = File(file.parent, "poi_ahead.old.log")
        old.delete()
        file.renameTo(old)
        file.createNewFile()
        file.appendText("${fmt.format(Date())} [SYS  ] === Log ruotato ===\n")
    }
}
