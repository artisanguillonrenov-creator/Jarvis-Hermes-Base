package com.cortana.proofofarchitecture.diagnostics

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

object DiagnosticExporter {
    /** Opens Intent.ACTION_SEND for the given report file (spec §4.4 point 12). */
    fun share(ctx: Context, reportFile: File) {
        val uri = FileProvider.getUriForFile(
            ctx, "com.cortana.proofofarchitecture.fileprovider", reportFile,
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(Intent.createChooser(intent, "Export diagnostic report"))
    }
}
