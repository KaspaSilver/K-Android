package com.kachat.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kachat.app.ui.AppTab
import com.kachat.app.ui.DockLayout
import com.kachat.app.ui.theme.KaspaTeal
import com.kachat.app.ui.theme.LocalAppColors
import com.kachat.app.util.IosHaptic
import com.kachat.app.util.rememberHaptics
import kotlinx.coroutines.delay

/** The menu's quick fade when a section is picked; the section opens after it (iOS 01f71184). */
private const val PICK_FADE_MS = 150

/** From the bottom of the system bars to just above the dock (its 60dp bar and 12dp margin). */
private val DockClearance = 72.dp

/**
 * Kaspa Hub's menu (iOS bfb902b7 `KaspaHubMenu`, owner 2026-11-26: "like when you click the + in
 * iMessage"): a glass panel that rises from the dock's left side, about as wide as the first three
 * dock icons and at most half the screen tall, with every section in a scrollable list and its
 * badge. A tap anywhere else, the dock included, closes it, as does Back; a section picked opens
 * as a Hub section ([onPick]).
 */
@Composable
fun KaspaHubMenu(
    isPresented: Boolean,
    onDismiss: () -> Unit,
    badgeCount: (AppTab) -> Int,
    onPick: (AppTab) -> Unit,
) {
    BackHandler(enabled = isPresented) { onDismiss() }
    val dockHub by DockLayout.hub.collectAsState()
    // The sections in alphabetical order (iOS `alphabeticalTabs`, owner 2026-11-27), by the name
    // shown in the person's language, so a new section lands where its name says.
    val context = androidx.compose.ui.platform.LocalContext.current
    val hub = androidx.compose.runtime.remember(dockHub, context) {
        val locale = context.resources.configuration.locales[0]
        val collator = java.text.Collator.getInstance(locale).apply { strength = java.text.Collator.SECONDARY }
        dockHub.sortedWith { a, b -> collator.compare(context.getString(a.labelRes), context.getString(b.labelRes)) }
    }
    val haptic = rememberHaptics()
    var picked by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<AppTab?>(null) }
    // A pick fades the menu out quickly and the section opens once it's gone (iOS 01f71184,
    // smoothness audit T1): building a section (Posts, the Explorer, a web view) during the
    // spring close stalled it.
    var closingForPick by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(isPresented) { if (isPresented) closingForPick = false }
    androidx.compose.runtime.LaunchedEffect(picked) {
        val tab = picked ?: return@LaunchedEffect
        delay(PICK_FADE_MS.toLong())
        picked = null
        onPick(tab)
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val maxPanelHeight = maxHeight * 0.5f
        val panelWidth = minOf(maxWidth * 0.64f, 300.dp)
        AnimatedVisibility(
            visible = isPresented,
            enter = fadeIn(spring(dampingRatio = 0.85f, stiffness = 400f)),
            exit = if (closingForPick) fadeOut(tween(PICK_FADE_MS, easing = EaseOut)) else fadeOut(spring(dampingRatio = 0.85f, stiffness = 400f)),
        ) {
            // a tap outside, the dock included, closes it; taps follow isPresented at once, not the
            // fade (iOS 01f71184, release audit T2)
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.18f))
                    .clickable(enabled = isPresented, interactionSource = remember { MutableInteractionSource() }, indication = null) { onDismiss() },
            )
        }
        AnimatedVisibility(
            visible = isPresented,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .navigationBarsPadding()
                .padding(start = 16.dp, bottom = DockClearance + 8.dp),
            enter = scaleIn(spring(dampingRatio = 0.82f, stiffness = 380f), initialScale = 0.4f, transformOrigin = TransformOrigin(0f, 1f)) +
                fadeIn(spring(dampingRatio = 0.82f, stiffness = 380f)),
            exit = if (closingForPick) {
                fadeOut(tween(PICK_FADE_MS, easing = EaseOut))
            } else {
                scaleOut(spring(dampingRatio = 0.85f, stiffness = 420f), targetScale = 0.4f, transformOrigin = TransformOrigin(0f, 1f)) +
                    fadeOut(spring(dampingRatio = 0.85f, stiffness = 420f))
            },
        ) {
            val shape = RoundedCornerShape(26.dp)
            val colors = LocalAppColors.current
            // its rows' height (about 48dp each), at most half the screen, scrolling past that
            val panelHeight = minOf((hub.size * 48 + 16).dp, maxPanelHeight)
            Column(
                Modifier
                    .width(panelWidth)
                    .height(panelHeight)
                    .shadow(18.dp, shape, ambientColor = Color.Black.copy(alpha = 0.25f), spotColor = Color.Black.copy(alpha = 0.25f))
                    .clip(shape)
                    .background(toolbarGlassFill().copy(alpha = 0.94f).compositeOver(colors.background))
                    .border(0.8.dp, toolbarGlassEdge(), shape)
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 8.dp, horizontal = 6.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                hub.forEach { tab ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .clickable(enabled = isPresented) {
                                haptic(IosHaptic.IMPACT_LIGHT)
                                closingForPick = true
                                onDismiss()
                                picked = tab
                            }
                            .padding(horizontal = 10.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier.size(34.dp).clip(CircleShape).background(KaspaTeal.copy(alpha = 0.18f)),
                            contentAlignment = Alignment.Center,
                        ) { AppTabIcon(tab, 18.dp) }
                        Spacer(Modifier.width(12.dp))
                        Text(
                            stringResource(tab.labelRes),
                            color = colors.textPrimary,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(4.dp))
                        val count = badgeCount(tab)
                        if (count > 0) {
                            Box(
                                Modifier
                                    .defaultMinSize(minWidth = 18.dp, minHeight = 18.dp)
                                    .clip(RoundedCornerShape(50))
                                    .background(colors.danger)
                                    .padding(horizontal = 5.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    if (count > 99) "99+" else count.toString(),
                                    color = Color.White,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
