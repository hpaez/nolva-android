package app.nolva.ui.share

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/**
 * Opens the system share sheet for [file], which must be inside the app's
 * export folder (the only one the FileProvider exposes). The receiving app
 * gets a temporary read grant for this file only.
 */
fun shareFile(context: Context, file: File, mimeType: String, subject: String) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = mimeType
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_TITLE, file.name)
        putExtra(Intent.EXTRA_SUBJECT, subject)
        clipData = ClipData.newRawUri(file.name, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, null))
}
