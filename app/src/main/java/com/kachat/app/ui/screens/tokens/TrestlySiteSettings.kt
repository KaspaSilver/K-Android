package com.kachat.app.ui.screens.tokens

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kachat.app.R
import com.kachat.app.services.tokens.TrestlySite
import com.kachat.app.ui.screens.SettingsFormSection
import com.kachat.app.ui.theme.KaspaTeal
import com.kachat.app.ui.theme.LocalAppColors
import com.kachat.app.util.AppText
import com.kachat.app.util.KaspaNetwork
import com.kachat.app.util.tr

/**
 * Settings > Connection > Trestly's second section (iOS 148a5331 `trestlySections`, "Trestly
 * Site"): the site Swap Stablecoins opens inside KaChat, per network, blank for that network's
 * default. Drawn like the Trestly API URL field above it; the page keeps [value] until Save, which
 * calls [saveTrestlySiteUrl]. Start the field from [TrestlySite.storedSiteUrl].
 */
@Composable
fun TrestlySiteUrlSection(value: String, onValueChange: (String) -> Unit, network: KaspaNetwork.Type) {
    val context = LocalContext.current
    remember { AppText.init(context) }
    val colors = LocalAppColors.current
    SettingsFormSection(
        title = tr(R.string.kp_trestly_site_kc),
        footer = tr(R.string.kp_trestly_site_footer_kc),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(tr(R.string.kp_trestly_site_url_kc), color = colors.textSecondary, fontSize = 12.sp)
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = TextStyle(color = colors.textPrimary, fontSize = 17.sp, fontFamily = FontFamily.Monospace),
                cursorBrush = SolidColor(KaspaTeal),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    autoCorrect = false,
                    capitalization = KeyboardCapitalization.None,
                ),
                modifier = Modifier.fillMaxWidth(),
                decorationBox = { inner ->
                    if (value.isEmpty()) {
                        Text(TrestlySite.defaultSiteUrl(network), color = colors.textTertiary, fontSize = 17.sp, fontFamily = FontFamily.Monospace)
                    }
                    inner()
                },
            )
            // iOS `httpsInlineError`: red while it holds an http:// value
            if (value.trim().lowercase().startsWith("http://")) {
                Text(stringResource(R.string.nc_use_https), color = colors.danger, fontSize = 12.sp)
            }
        }
    }
}

/**
 * The Trestly page's Save for the site (iOS `saveSettings`: `normalizedOrDefault`): an http://
 * value is refused (false, nothing saved), anything else is saved for [network] with https:// in
 * front when it had no scheme, blank going back to the default.
 */
fun saveTrestlySiteUrl(context: Context, network: KaspaNetwork.Type, value: String): Boolean {
    val trimmed = value.trim()
    if (trimmed.lowercase().startsWith("http://")) return false
    val normalized = when {
        trimmed.isEmpty() -> ""
        trimmed.lowercase().startsWith("https://") -> trimmed
        else -> "https://$trimmed"
    }
    TrestlySite.setSiteUrl(context, network, normalized)
    return true
}
