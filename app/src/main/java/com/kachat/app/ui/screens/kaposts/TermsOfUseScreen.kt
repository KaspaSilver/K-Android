package com.kachat.app.ui.screens

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.kachat.app.R
import com.kachat.app.ui.theme.KaspaTeal
import com.kachat.app.ui.theme.LocalAppColors

/**
 * The Terms of Use (iOS 148a5331 `KaChatTerms`, from KaPost: KaChat has KaPost and Public Chats
 * again): everyone agrees before using the app (the first screen until agreed), and they stay
 * readable in Settings > About. Bump [CURRENT_VERSION] when the text changes, and everyone is asked
 * again. The text is KaPost's with the app's name changed, so the translations carry over.
 */
object KaChatTerms {
    const val CURRENT_VERSION = 1
    private const val PREFS = "kachat_terms"
    private const val KEY_ACCEPTED_VERSION = "kachat_terms_accepted_version"

    /** The version last agreed to on this device. */
    fun acceptedVersion(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_ACCEPTED_VERSION, 0)

    /** Whether the terms in force were agreed to on this device: false shows [TermsAcceptanceScreen]. */
    fun isAccepted(context: Context): Boolean = acceptedVersion(context) >= CURRENT_VERSION

    fun accept(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_ACCEPTED_VERSION, CURRENT_VERSION).apply()
    }

    /** Each section's title and text (string resources: the iPhone's words in every language). */
    val sections = listOf(
        R.string.kpui_terms_no_abuse_title to R.string.kpui_terms_no_abuse_body,
        R.string.kpui_terms_public_title to R.string.kpui_terms_public_body,
        R.string.kpui_terms_reporting_title to R.string.kpui_terms_reporting_body,
        R.string.kpui_terms_account_title to R.string.kpui_terms_account_body,
        R.string.kpui_terms_warranty_title to R.string.kpui_terms_warranty_body,
        R.string.kpui_terms_contact_title to R.string.kpui_terms_contact_body,
    )
}

/** The terms' text, shared by the first-launch screen and Settings. */
@Composable
private fun KaChatTermsText(modifier: Modifier = Modifier) {
    val colors = LocalAppColors.current
    Column(
        modifier = modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        KaChatTerms.sections.forEach { (title, text) ->
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(title), color = colors.textPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    if (text == R.string.kpui_terms_contact_body) stringResource(text, ContentReport.SUPPORT_EMAIL) else stringResource(text),
                    color = colors.textSecondary,
                    fontSize = 15.sp,
                )
            }
        }
    }
}

/** Settings > About > Terms of Use. */
@Composable
fun TermsOfUseScreen(onBack: () -> Unit) {
    val colors = LocalAppColors.current
    Column(Modifier.fillMaxSize().background(colors.background)) {
        Box(Modifier.statusBarsPadding()) {
            SettingsSubpageBar(title = stringResource(R.string.shell_terms_of_use), onBack = onBack)
        }
        KaChatTermsText(Modifier.weight(1f).verticalScroll(rememberScrollState()))
    }
}

/** The first thing the app shows until the current terms are agreed to. */
@Composable
fun TermsAcceptanceScreen(onAgree: () -> Unit) {
    val colors = LocalAppColors.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Text(
            stringResource(R.string.shell_terms_of_use),
            color = colors.textPrimary,
            fontSize = LargeTitleSize,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 6.dp),
        )
        HorizontalDivider(color = colors.surfaceVariant)
        KaChatTermsText(Modifier.weight(1f).verticalScroll(rememberScrollState()))
        HorizontalDivider(color = colors.surfaceVariant)
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(stringResource(R.string.kpui_terms_need_agree), color = colors.textSecondary, fontSize = 13.sp)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(KaspaTeal)
                    .clickable(onClick = onAgree)
                    .padding(16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(stringResource(R.string.kpui_i_agree), color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
