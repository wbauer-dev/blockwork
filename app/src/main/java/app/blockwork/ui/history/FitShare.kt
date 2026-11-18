package app.blockwork.ui.history

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object FitShare {
    private fun exportDir(context: Context) = File(context.cacheDir, "exports")

    /** Deletes the cached copy of the last exported FIT file. */
    fun clearCache(context: Context) {
        exportDir(context).listFiles()?.forEach { it.delete() }
    }

    /** Writes the FIT file to the cache and opens the share sheet. */
    fun share(context: Context, bytes: ByteArray, startedAt: Long, workoutName: String) {
        val dir = exportDir(context).apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() } // keep only the latest export
        val stamp = SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.ROOT).format(Date(startedAt))
        val safeName = workoutName.replace(Regex("[^A-Za-z0-9._-]+"), "_").take(40)
        val file = File(dir, "${stamp}_$safeName.fit").apply { writeBytes(bytes) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("application/octet-stream")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, "Export FIT file"))
    }
}
