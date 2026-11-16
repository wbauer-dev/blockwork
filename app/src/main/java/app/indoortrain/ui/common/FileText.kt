package app.indoortrain.ui.common

import android.content.Context
import android.net.Uri
import java.io.IOException

/** Reads a picked file as text, at most [maxChars] + 1 characters (callers reject longer files). */
@Throws(IOException::class)
fun readTextLimited(context: Context, uri: Uri, maxChars: Int): String {
    val stream = context.contentResolver.openInputStream(uri) ?: throw IOException("no data")
    return stream.bufferedReader().use { reader ->
        val buffer = CharArray(maxChars + 1)
        var total = 0
        while (total < buffer.size) {
            val n = reader.read(buffer, total, buffer.size - total)
            if (n < 0) break
            total += n
        }
        String(buffer, 0, total)
    }
}
