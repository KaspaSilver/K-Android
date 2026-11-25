package com.kachat.app.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kachat.app.R
import com.kachat.app.models.GroupActionProgress
import com.kachat.app.ui.theme.KaspaTeal
import com.kachat.app.ui.theme.LocalAppColors
import com.kachat.app.util.IosHaptic
import com.kachat.app.util.rememberHaptics
import kotlinx.coroutines.launch

// Creating a group and adding members, as half sheets (iOS GroupActionSheets.swift, owner
// 2026-11-24): a confirmation sheet in place of the old alert, then a progress sheet that shows
// every transaction as it goes out (setting up, inviting each person, the recovery copy, the
// photo), so a slow network reads as "working", not "broken". The steps come from
// GroupRepository's GroupActionReport.

/**
 * A half sheet as tall as its content that floats (iOS `fittedSheetDetent`): the content scrolls
 * if it is taller than the screen. Back goes to [onBack] (Android has no iOS counterpart; it acts
 * as the sheet's Cancel or Done), never straight to the sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GroupActionHalfSheet(
    sheetState: SheetState,
    grouped: Boolean,
    onDismissRequest: () -> Unit,
    onBack: () -> Unit,
    content: @Composable () -> Unit,
) {
    com.kachat.app.ui.theme.IosSheetColors(grouped = grouped) {
        ModalBottomSheet(
            scrimColor = com.kachat.app.ui.theme.IosSheetScrim,
            modifier = Modifier.iosFloatingSheet(),
            shape = IosFloatingSheetShape,
            tonalElevation = com.kachat.app.ui.theme.IosSheetTonalElevation,
            windowInsets = androidx.compose.foundation.layout.WindowInsets.statusBars,
            onDismissRequest = onDismissRequest,
            sheetState = sheetState,
            containerColor = LocalAppColors.current.background,
            dragHandle = null,
            properties = ModalBottomSheetProperties(
                securePolicy = androidx.compose.ui.window.SecureFlagPolicy.Inherit,
                isFocusable = true,
                shouldDismissOnBackPress = false,
            ),
        ) {
            SheetWindowBack(onBack)
            Box(Modifier.navigationBarsPadding()) {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                    content()
                }
            }
        }
    }
}

/** The accent capsule a sheet's main button is (iOS: headline in black on the accent, 52 tall). */
@Composable
private fun CapsuleButton(title: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(CircleShape)
            .background(KaspaTeal)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(title, color = Color.Black, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
    }
}

/**
 * "Create group" / "Add members": what will happen and what it costs, with the button that does
 * it (iOS `GroupActionConfirmSheet`). [onConfirm] runs once the sheet has gone down, as iOS acts
 * in the sheet's onDismiss.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupActionConfirmSheet(
    title: String,
    message: String,
    feeLine: String?,
    confirmTitle: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val haptic = rememberHaptics()
    val latestDismiss by rememberUpdatedState(onDismiss)
    fun close(then: () -> Unit = {}) {
        scope.launch { sheetState.hide() }.invokeOnCompletion {
            latestDismiss()
            then()
        }
    }
    GroupActionHalfSheet(sheetState = sheetState, grouped = false, onDismissRequest = onDismiss, onBack = { close() }) {
        val colors = LocalAppColors.current
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                title,
                color = colors.textPrimary,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 20.dp),
            )
            Text(message, color = colors.textPrimary, fontSize = 17.sp, textAlign = TextAlign.Center)
            if (!feeLine.isNullOrEmpty()) {
                Text(feeLine, color = colors.textSecondary, fontSize = 15.sp, textAlign = TextAlign.Center)
            }
            Box(Modifier.padding(top = 6.dp)) {
                CapsuleButton(confirmTitle) {
                    haptic(IosHaptic.IMPACT_MEDIUM)
                    close(onConfirm)
                }
            }
            Text(
                stringResource(R.string.cancel),
                color = KaspaTeal,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { close() }
                    .padding(vertical = 6.dp),
            )
        }
    }
}

/**
 * The steps of a group action as they happen, with Done once it's over (iOS
 * `GroupActionProgressSheet`). It can't be swiped away, tapped away or backed out of while it
 * works; once it's over Back is Done.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupActionProgressSheet(progress: GroupActionProgress, onDone: () -> Unit) {
    val finished by rememberUpdatedState(progress.isFinished)
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { it != SheetValue.Hidden || finished },
    )
    val scope = rememberCoroutineScope()
    val haptic = rememberHaptics()
    val latestDone by rememberUpdatedState(onDone)
    val done: () -> Unit = {
        scope.launch { sheetState.hide() }.invokeOnCompletion { latestDone() }
    }
    GroupActionHalfSheet(
        sheetState = sheetState,
        grouped = true,
        onDismissRequest = { if (finished) latestDone() },
        onBack = { if (finished) done() },
    ) {
        val colors = LocalAppColors.current
        val total = progress.steps.size
        val settled = progress.settledCount
        val failed = progress.failedCount
        val fraction by animateFloatAsState(
            targetValue = settled.toFloat() / total.coerceAtLeast(1),
            animationSpec = tween(durationMillis = 250),
            label = "groupActionProgress",
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(Modifier.padding(top = 22.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(progress.headline, color = colors.textPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.fillMaxWidth().clip(CircleShape),
                    color = if (failed > 0) colors.warning else KaspaTeal,
                    trackColor = colors.textSecondary.copy(alpha = 0.2f),
                )
                Text(
                    stringResource(R.string.ga54_x_of_y, settled, total),
                    color = colors.textSecondary,
                    fontSize = 12.sp,
                    style = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum"),
                )
            }

            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(colors.surface)
            ) {
                progress.steps.forEachIndexed { index, step ->
                    GroupActionStepRow(step)
                    if (index < progress.steps.lastIndex) {
                        HorizontalDivider(modifier = Modifier.padding(start = 44.dp), thickness = 0.5.dp, color = colors.divider)
                    }
                }
            }

            if (progress.isFinished) {
                if (failed > 0) {
                    Text(stringResource(R.string.ga54_some_steps_failed), color = colors.textSecondary, fontSize = 13.sp)
                }
                CapsuleButton(stringResource(R.string.done)) {
                    haptic(IosHaptic.IMPACT_LIGHT)
                    done()
                }
            } else {
                Text(stringResource(R.string.ga54_each_step_is_tx), color = colors.textSecondary, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun GroupActionStepRow(step: GroupActionProgress.Step) {
    val colors = LocalAppColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
            when (step.state) {
                GroupActionProgress.State.Waiting ->
                    Icon(Icons.Default.RadioButtonUnchecked, contentDescription = null, tint = colors.textSecondary.copy(alpha = 0.5f), modifier = Modifier.size(22.dp))
                GroupActionProgress.State.Working ->
                    com.kachat.app.ui.theme.IosActivityIndicator(modifier = Modifier.size(18.dp), color = colors.textSecondary)
                GroupActionProgress.State.Done ->
                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = colors.success, modifier = Modifier.size(22.dp))
                is GroupActionProgress.State.Failed ->
                    Icon(Icons.Default.Cancel, contentDescription = null, tint = colors.danger, modifier = Modifier.size(22.dp))
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            val working = step.state == GroupActionProgress.State.Working
            Text(
                step.title,
                color = if (step.state == GroupActionProgress.State.Waiting) colors.textSecondary else colors.textPrimary,
                fontSize = 15.sp,
                fontWeight = if (working) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val state = step.state
            if (state is GroupActionProgress.State.Failed) {
                Text(state.message, color = colors.danger, fontSize = 12.sp)
            } else if (working && step.detail != null) {
                Text(
                    step.detail,
                    color = colors.textSecondary,
                    fontSize = 12.sp,
                    style = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum"),
                )
            }
        }
    }
}
