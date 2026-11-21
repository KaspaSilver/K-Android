package com.kachat.app.ui.screens.tokens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.CallMerge
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.ArrowCircleUp
import androidx.compose.material.icons.outlined.Hexagon
import androidx.compose.material.icons.outlined.MonetizationOn
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import com.kachat.app.R
import com.kachat.app.services.tokens.WalletToken
import com.kachat.app.ui.screens.LocalDockInset
import com.kachat.app.ui.screens.SheetBarBackButton
import com.kachat.app.ui.theme.KaspaTeal
import com.kachat.app.ui.theme.LocalAppColors
import com.kachat.app.util.AppText
import com.kachat.app.util.tr

/**
 * Settings > Help > kUSDT Guide (iOS 148a5331 `TokenGuideView`, from KaPay): what kUSDT is and how
 * KaChat handles it, a card per topic in the style of the .kachat help. Pushed inside the Settings
 * tab, its dock kept, as Help is; the inline title with a back button.
 */
@Composable
fun TokenGuideScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    remember { AppText.init(context) }
    val colors = LocalAppColors.current
    Column(Modifier.fillMaxSize().background(colors.background)) {
        // iOS `.navigationTitle("kUSDT Guide")`, inline, with the back button
        TokenPushedTopBar(title = tr(R.string.kp_kusdt_guide_title), onBack = onBack)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(top = 12.dp, bottom = 24.dp + LocalDockInset.current),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 4.dp).padding(bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                TokenIcon(WalletToken.KUSDT, size = 52.dp)
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    // iOS .title2 bold, verbatim
                    Text("kUSDT", color = colors.textPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text(tr(R.string.kp_kusdt_guide_tagline), color = colors.textSecondary, fontSize = 15.sp)
                }
            }
            TokenGuideCard(Icons.Outlined.MonetizationOn, tr(R.string.kp_kusdt_guide_what_it_is), tr(R.string.kp_kusdt_what_it_is_detail))
            TokenGuideCard(Icons.Outlined.AccountBalanceWallet, tr(R.string.kp_kusdt_guide_where_it_lives), tr(R.string.kp_kusdt_guide_where_it_lives_detail))
            TokenGuideCard(Icons.Outlined.Hexagon, tr(R.string.kp_kusdt_guide_kas_in_every_coin), tr(R.string.kp_kusdt_coin_holds_kas))
            TokenGuideCard(Icons.Outlined.ArrowCircleUp, tr(R.string.kp_kusdt_guide_sending), tr(R.string.kp_kusdt_guide_sending_detail))
            TokenGuideCard(Icons.AutoMirrored.Outlined.CallMerge, tr(R.string.kp_combine_coins_53994), tr(R.string.kp_kusdt_guide_combine_detail))
            TokenGuideCard(Icons.Outlined.Sync, tr(R.string.kp_kusdt_guide_swapping), tr(R.string.kp_kusdt_guide_swapping_detail_kc))
            TokenGuideCard(Icons.Outlined.Apps, tr(R.string.kp_kusdt_guide_other_apps), tr(R.string.kp_kusdt_guide_other_apps_detail_kc))
            TokenGuideCard(Icons.Outlined.Schedule, tr(R.string.address_tab_history), tr(R.string.kp_kusdt_guide_history_detail))
            TokenGuideCard(Icons.Outlined.Warning, tr(R.string.kp_kusdt_guide_risks), tr(R.string.kp_kusdt_not_audited), tint = colors.warning)
        }
    }
}

/**
 * A pushed token screen's inline bar (iOS `.navigationBarTitleDisplayMode(.inline)`), as Manage
 * Addresses draws its own: the back button, the title centred, [actions] at the right.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TokenPushedTopBar(
    title: String,
    onBack: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val colors = LocalAppColors.current
    CenterAlignedTopAppBar(
        title = { Text(title, color = colors.textPrimary, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = { SheetBarBackButton(onClick = onBack, modifier = Modifier.padding(start = 12.dp)) },
        actions = actions,
        colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = colors.background),
    )
}

/** iOS `TokenGuideView.card`: the symbol in a 30 frame, the headline over its subheadline detail,
 *  on the grouped cell colour, 16 corners. */
@Composable
private fun TokenGuideCard(icon: ImageVector, title: String, detail: String, tint: Color = KaspaTeal) {
    val colors = LocalAppColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surface, RoundedCornerShape(16.dp))
            .padding(16.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.width(30.dp), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, color = colors.textPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Text(detail, color = colors.textSecondary, fontSize = 15.sp)
        }
    }
}

/** Settings > Help's row for this guide (iOS 148a5331 `helpRowLabel(icon: "dollarsign.circle", ...)`,
 *  beside the Welcome Guide): its icon, title and subtitle, for the Help screen to draw. */
object KusdtGuideHelpRow {
    val icon: ImageVector get() = Icons.Outlined.MonetizationOn
    fun title(): String = tr(R.string.kp_kusdt_guide_title)
    fun subtitle(): String = tr(R.string.kp_kusdt_guide_help_subtitle)
}
