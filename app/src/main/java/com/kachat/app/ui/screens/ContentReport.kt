package com.kachat.app.ui.screens

import android.content.Context
import android.content.Intent
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import com.kachat.app.R
import com.kachat.app.ui.theme.KaspaTeal
import com.kachat.app.ui.theme.LocalAppColors

/**
 * Reporting a post or room message (iOS 148a5331 `ContentReport`, subject "K report"): the report goes to support by
 * email, where a person reads it, in whatever mail app takes it (iOS e5a9b11). If nothing can open
 * it, the report is copied and [ContentReportFallback] says where to send it.
 */
class ContentReport(val body: String) {
    /** Opens the email, or copies the report and returns false, for the caller to show
     *  [ContentReportFallback]. */
    fun send(context: Context): Boolean {
        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = android.net.Uri.parse("mailto:")
            putExtra(Intent.EXTRA_EMAIL, arrayOf(SUPPORT_EMAIL))
            putExtra(Intent.EXTRA_SUBJECT, "K report")
            putExtra(Intent.EXTRA_TEXT, body)
        }
        return try {
            context.startActivity(intent)
            true
        } catch (_: android.content.ActivityNotFoundException) {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("K report", body))
            false
        }
    }

    companion object {
        const val SUPPORT_EMAIL = "kaspasilver@gmail.com"
    }
}

/** The alert for a report [ContentReport.send] couldn't hand to a mail app. */
@Composable
fun ContentReportFallback(onDismiss: () -> Unit) {
    val colors = LocalAppColors.current
    com.kachat.app.ui.theme.IosAlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surface,
        // Translated (iOS 518fdc8c: the report fallback was among the ~95 texts with no key).
        title = { Text(stringResource(R.string.shell54_report_copied), color = colors.textPrimary) },
        text = {
            Text(
                stringResource(R.string.shell54_report_no_mail_app, ContentReport.SUPPORT_EMAIL),
                color = colors.textSecondary,
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.ok), color = KaspaTeal, fontWeight = FontWeight.Bold)
            }
        },
    )
}
