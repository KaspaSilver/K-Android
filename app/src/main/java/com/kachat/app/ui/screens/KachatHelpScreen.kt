package com.kachat.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Eco
import androidx.compose.material.icons.outlined.Lan
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PanTool
import androidx.compose.material.icons.outlined.Sell
import androidx.compose.material.icons.outlined.ShoppingCart
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kachat.app.R
import com.kachat.app.services.kachatnames.KachatNames
import com.kachat.app.services.kachatnames.KachatNamesService
import com.kachat.app.services.kachatnames.Manifest
import com.kachat.app.services.kachatnames.Params
import com.kachat.app.ui.theme.KaspaTeal
import com.kachat.app.ui.theme.LocalAppColors
import com.kachat.app.util.KaspaUnit
import com.kachat.app.util.tr

/**
 * The .kachat tab's "?" (iOS 3d126db `KachatHelpView`, owner's design 2026-11-19): a full screen
 * with two cards, How It Works and Pricing, each opening its own page. Prices, the bond and the
 * paid-period limits come from the registry's params, so testnet shows its own; before the
 * manifest has loaded, mainnet's pinned tables stand in. Shown full screen over the dock
 * ([KachatMarketScreen] puts it in a full-screen dialog, as iOS's fullScreenCover).
 */
@Composable
fun KachatHelpScreen(
    /** Names are live on this network (`KachatHubModel.isLive`). */
    isLive: Boolean,
    /** The registry service, for the params; null where it isn't launched (pinned tables then). */
    service: KachatNamesService?,
    onClose: () -> Unit,
) {
    // The pushed page: null on the two cards, else "how" or "pricing" (iOS's NavigationStack).
    var page by remember { mutableStateOf<String?>(null) }
    BackHandler { if (page != null) page = null else onClose() }
    val colors = LocalAppColors.current
    val title = when (page) {
        "how" -> tr(R.string.kcn_kp_how_it_works)
        "pricing" -> tr(R.string.kcn_kp_pricing)
        else -> tr(R.string.kcn_kp_about_kachat)
    }
    Column(
        Modifier
            .fillMaxSize()
            .background(colors.background)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        // Inline bar: Back on a page, Done (confirmation, trailing) always, as iOS's toolbar.
        SheetNavBar(
            title = title,
            leading = { if (page != null) SheetBarBackButton(onClick = { page = null }) },
            trailing = { SheetBarButton(stringResource(R.string.done), onClick = onClose, emphasized = true) },
        )
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(if (page == null) 16.dp else 12.dp),
        ) {
            when (page) {
                "how" -> KachatHowItWorksPage(isLive)
                "pricing" -> KachatPricingPage(service)
                else -> {
                    KachatHelpCard(
                        icon = Icons.Outlined.Lightbulb,
                        title = tr(R.string.kcn_kp_how_it_works),
                        detail = tr(R.string.kcn_kp_kachat_help_how_detail),
                        onClick = { page = "how" },
                    )
                    KachatHelpCard(
                        icon = Icons.Outlined.Sell,
                        title = tr(R.string.kcn_kp_pricing),
                        detail = tr(R.string.kcn_kp_kachat_help_pricing_detail),
                        onClick = { page = "pricing" },
                    )
                }
            }
        }
    }
}

/** One of the help screen's two cards: an accent icon, a title and one line, with a chevron. */
@Composable
private fun KachatHelpCard(icon: ImageVector, title: String, detail: String, onClick: () -> Unit) {
    val colors = LocalAppColors.current
    val shape = RoundedCornerShape(20.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.surface)
            .border(1.dp, KaspaTeal.copy(alpha = 0.35f), shape)
            .clickable(onClick = onClick)
            .padding(18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(
            Modifier.size(52.dp).clip(CircleShape).background(KaspaTeal.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = KaspaTeal, modifier = Modifier.size(26.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, color = colors.textPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text(detail, color = colors.textSecondary, fontSize = 15.sp)
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = colors.textSecondary,
            modifier = Modifier.size(20.dp),
        )
    }
}

// MARK: - How It Works

@Composable
private fun KachatHowItWorksPage(isLive: Boolean) {
    val colors = LocalAppColors.current
    KachatHelpStep(KachatSymbols.AtBadgePlus, stringResource(R.string.km_claim), tr(R.string.kcn_kp_kachat_claim_detail))
    KachatHelpStep(Icons.Filled.Refresh, stringResource(R.string.kn_renew), tr(R.string.kcn_kp_kachat_renew_detail))
    KachatHelpStep(Icons.Outlined.Sell, stringResource(R.string.km_list), stringResource(R.string.km_list_detail))
    KachatHelpStep(Icons.Outlined.ShoppingCart, stringResource(R.string.km_buy), stringResource(R.string.km_buy_detail))
    KachatHelpStep(Icons.Outlined.PanTool, stringResource(R.string.kl_offer), KaspaUnit.label(stringResource(R.string.kl_offer_detail)))
    KachatHelpStep(Icons.Outlined.VerifiedUser, stringResource(R.string.km_trustless), stringResource(R.string.km_trustless_detail))
    Text(
        stringResource(
            when {
                isLive && KachatNamesService.networkName == "mainnet" -> R.string.kn_how_live_footer_mainnet
                isLive -> R.string.kn_how_live_footer_testnet
                else -> R.string.km_nothing_live
            }
        ),
        color = colors.textSecondary,
        fontSize = 13.sp,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
    )
}

/** A step or a pricing note: the accent icon, a headline and its text, on a 16-radius card. */
@Composable
private fun KachatHelpStep(icon: ImageVector, title: String, detail: String) {
    val colors = LocalAppColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(colors.surface)
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.width(30.dp), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = KaspaTeal, modifier = Modifier.size(22.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, color = colors.textPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Text(detail, color = colors.textSecondary, fontSize = 15.sp)
        }
    }
}

// MARK: - Pricing

@Composable
private fun KachatPricingPage(service: KachatNamesService?) {
    val colors = LocalAppColors.current
    val context = LocalContext.current
    val manifest: Manifest? = service?.manifest?.collectAsState()?.value
    val params: Params? = manifest?.params
    val network = KachatNamesService.networkName
    val registerPrices = params?.registerPrices
        ?: Manifest.PINNED_REGISTER_PRICES[network] ?: Manifest.PINNED_REGISTER_PRICES["mainnet"].orEmpty()
    val renewPrices = params?.renewPrices
        ?: Manifest.PINNED_RENEW_PRICES[network] ?: Manifest.PINNED_RENEW_PRICES["mainnet"].orEmpty()
    // The bond plus the registry deposit: locked with the name, returned on release (1 + 1 KAS on
    // mainnet).
    val lockedSompi = (params?.bond ?: 100_000_000L) + (params?.gapValue ?: 100_000_000L)
    val maxPeriods = params?.maxYears ?: 2L
    /** "1 year" / "2 years", or on a short clock (testnet) a duration (iOS `KachatLive.periods`). */
    fun periods(count: Long): String = when {
        !KachatLive.yearlyPeriods(params) -> KachatLive.duration(count * (params?.periodMs ?: KachatNames.YEAR_MS), context)
        count == 1L -> tr(R.string.kn_one_year)
        else -> tr(R.string.kn_n_years, count.toInt())
    }
    val lengthTitles = listOf(
        tr(R.string.kcn_kp_one_character), tr(R.string.kcn_kp_two_characters), tr(R.string.kcn_kp_three_characters),
        tr(R.string.kcn_kp_four_characters), tr(R.string.kcn_kp_five_or_more_characters),
    )

    // The claim and renew table by name length.
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(colors.surface)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
            val header = @Composable { text: String, modifier: Modifier, align: TextAlign ->
                Text(text.uppercase(), color = colors.textSecondary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, textAlign = align, modifier = modifier)
            }
            header(tr(R.string.kcn_kp_name_length), Modifier.weight(1f), TextAlign.Start)
            header(stringResource(R.string.km_claim), Modifier.width(92.dp), TextAlign.End)
            header(stringResource(R.string.kn_renew), Modifier.width(92.dp), TextAlign.End)
        }
        val tiers = minOf(lengthTitles.size, registerPrices.size, renewPrices.size)
        for (tier in 0 until tiers) {
            HorizontalDivider(Modifier.padding(start = 16.dp), color = colors.divider, thickness = 0.5.dp)
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(lengthTitles[tier], color = colors.textPrimary, fontSize = 15.sp, modifier = Modifier.weight(1f))
                Box(Modifier.width(92.dp), contentAlignment = Alignment.CenterEnd) {
                    KachatFitText(KaspaUnit.amount(registerPrices[tier]), color = colors.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, minScale = 0.7f, textAlign = TextAlign.End)
                }
                Box(Modifier.width(92.dp), contentAlignment = Alignment.CenterEnd) {
                    KachatFitText(KaspaUnit.amount(renewPrices[tier]), color = colors.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.Normal, minScale = 0.7f, textAlign = TextAlign.End)
                }
            }
        }
        HorizontalDivider(Modifier.padding(start = 16.dp), color = colors.divider, thickness = 0.5.dp)
        Text(
            tr(R.string.kcn_kp_claim_pays_first_period, periods(1)),
            color = colors.textSecondary,
            fontSize = 13.sp,
            modifier = Modifier.padding(16.dp),
        )
    }

    KachatHelpStep(Icons.Outlined.Eco, tr(R.string.kcn_kp_where_the_price_goes), tr(R.string.kcn_kp_price_goes_to_miners))
    KachatHelpStep(Icons.Outlined.Lock, tr(R.string.kcn_kp_locked_not_spent), tr(R.string.kcn_kp_locked_with_your_name, KaspaUnit.amount(lockedSompi)))
    KachatHelpStep(Icons.Outlined.CalendarMonth, tr(R.string.kcn_kp_paying_ahead), tr(R.string.kcn_kp_pay_up_to_at_a_time, periods(maxPeriods)))
    KachatHelpStep(Icons.Outlined.ShoppingCart, tr(R.string.kcn_kp_buying_and_selling), tr(R.string.kcn_kp_no_marketplace_fee))
    KachatHelpStep(Icons.Outlined.Lan, stringResource(R.string.kl_network_fee), tr(R.string.kcn_kp_kachat_network_fee_note))
    Spacer(Modifier.size(0.dp))
}
