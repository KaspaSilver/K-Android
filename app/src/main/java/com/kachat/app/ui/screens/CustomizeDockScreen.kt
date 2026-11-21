package com.kachat.app.ui.screens

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.kachat.app.R
import com.kachat.app.ui.AppTab
import com.kachat.app.ui.DockLayout
import com.kachat.app.ui.theme.AppColors
import com.kachat.app.ui.theme.IosSheetColors
import com.kachat.app.ui.theme.IosSheetShape
import com.kachat.app.ui.theme.IosSheetTonalElevation
import com.kachat.app.ui.theme.LocalAppColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Customize Dock as iOS presents it from MainTabView (iOS 148a5331 `CustomizeDockView`): a sheet
 * above the whole shell with Done top right. It sits outside the NavHost and the dock, so the dock
 * rebuilding under it on every move neither closes it nor resets it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomizeDockSheet(onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    // A List in a sheet on iOS: the grouped sheet palette.
    IosSheetColors(grouped = true) {
        ModalBottomSheet(
            scrimColor = com.kachat.app.ui.theme.IosSheetScrim,
            // iOS's .large sheet tops out 10 below the status bar.
            modifier = Modifier.padding(top = 10.dp),
            shape = IosSheetShape,
            tonalElevation = IosSheetTonalElevation,
            windowInsets = WindowInsets.statusBars,
            onDismissRequest = onDismiss,
            sheetState = sheetState,
            containerColor = LocalAppColors.current.background,
            dragHandle = null,
        ) {
            CustomizeDockScreen(
                // Done slides the sheet down, as iOS's does, then removes it.
                onDone = { scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() } },
            )
        }
    }
}

/**
 * Customize Dock: two lists ordered by dragging. Your Dock is the dock itself, in order; Kaspa
 * Hub and Profile are always in it (a lock instead of a remove button) and the others carry a red
 * minus that sends them to the Hub. Kaspa Hub lists everything else in its grid order, each with a
 * green plus that moves it into the dock while there's room (five at most). Every move applies
 * at once (iOS `persist()` on every change), so the dock is already right when Done is tapped.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CustomizeDockScreen(onDone: () -> Unit) {
    val colors = LocalAppColors.current
    val haptics = LocalHapticFeedback.current
    // Seeded once from the stored arrangement; every move writes straight back.
    var dock by remember { mutableStateOf(DockLayout.dock.value) }
    var hub by remember { mutableStateOf(DockLayout.hub.value) }
    val dockIsFull = dock.size >= AppTab.MAX_DOCK_ITEMS
    val hubLabel = stringResource(AppTab.HUB.labelRes)

    fun persist() {
        DockLayout.set(dock, hub)
    }

    /** Red minus: out of the dock, to the top of Kaspa Hub where it can be seen. */
    fun removeFromDock(tab: AppTab) {
        if (tab in AppTab.PINNED_TO_DOCK) return
        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        dock = dock.filter { it != tab }
        hub = listOf(tab) + hub.filter { it != tab }
        persist()
    }

    /** Green plus: into the dock, at its end; drag it to where it should sit. */
    fun addToDock(tab: AppTab) {
        if (dockIsFull) return
        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        hub = hub.filter { it != tab }
        dock = dock.filter { it != tab } + tab
        persist()
    }

    Scaffold(
        containerColor = colors.background,
        topBar = {
            // iOS's sheet: the inline title and Done as its confirmation action, top right.
            SheetNavBar(
                title = stringResource(R.string.shell_customize_dock),
                modifier = Modifier.background(colors.background),
                trailing = {
                    SheetBarButton(stringResource(R.string.done), onClick = onDone, emphasized = true)
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .navigationBarsPadding(),
        ) {
            Spacer(Modifier.height(12.dp))

            // Your Dock
            DockSectionHeader(
                title = stringResource(R.string.shell_your_dock),
                detail = "${dock.size}/${AppTab.MAX_DOCK_ITEMS}",
                colors = colors,
            )
            ReorderableTabCard(
                items = dock,
                colors = colors,
                separatorStart = LABEL_WITH_CONTROL_START,
                onMove = { from, to ->
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    dock = dock.toMutableList().apply { add(to, removeAt(from)) }
                    persist()
                },
            ) { tab ->
                if (tab in AppTab.PINNED_TO_DOCK) {
                    val always = stringResource(R.string.shell_always_in_dock)
                    Box(Modifier.width(24.dp), contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.Lock, contentDescription = always, tint = colors.textSecondary, modifier = Modifier.size(15.dp))
                    }
                } else {
                    CircleControl(
                        color = colors.danger,
                        plus = false,
                        description = stringResource(R.string.shell_move_to, stringResource(tab.labelRes), hubLabel),
                        onClick = { removeFromDock(tab) },
                    )
                }
                Spacer(Modifier.width(14.dp))
                Box(Modifier.width(28.dp), contentAlignment = Alignment.Center) { AppTabIcon(tab, 20.dp) }
                Spacer(Modifier.width(14.dp))
                Text(stringResource(tab.labelRes), color = colors.textPrimary, fontSize = 17.sp, maxLines = 1)
            }
            DockSectionFooter(stringResource(R.string.shell_always_stays, hubLabel), colors)

            Spacer(Modifier.height(16.dp))

            // Kaspa Hub
            DockSectionHeader(title = hubLabel, detail = null, colors = colors)
            ReorderableTabCard(
                items = hub,
                colors = colors,
                separatorStart = if (dockIsFull) LABEL_START else LABEL_WITH_CONTROL_START,
                onMove = { from, to ->
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    hub = hub.toMutableList().apply { add(to, removeAt(from)) }
                    persist()
                },
            ) { tab ->
                if (!dockIsFull) {
                    CircleControl(
                        color = colors.success,
                        plus = true,
                        description = stringResource(R.string.shell_add_to_dock, stringResource(tab.labelRes)),
                        onClick = { addToDock(tab) },
                    )
                    Spacer(Modifier.width(14.dp))
                }
                Box(Modifier.width(28.dp), contentAlignment = Alignment.Center) { AppTabIcon(tab, 20.dp) }
                Spacer(Modifier.width(14.dp))
                Text(stringResource(tab.labelRes), color = colors.textPrimary, fontSize = 17.sp, maxLines = 1)
            }
            DockSectionFooter(
                if (dockIsFull) stringResource(R.string.shell_dock_full) else stringResource(R.string.shell_hub_order, hubLabel),
                colors,
            )

            Spacer(Modifier.height(24.dp))
        }
    }
}

/** A row's height, shared by the layout and by the drag's slot arithmetic. A List row with a
 *  22pt control is 56 tall on the iPhone. */
private val ROW_HEIGHT = 56.dp

/** Where a row's label starts - its separator is inset to it, as an iOS List row's is. */
private val LABEL_START = 16.dp + 28.dp + 14.dp
private val LABEL_WITH_CONTROL_START = LABEL_START + 24.dp + 14.dp

/** iOS inset-grouped section header: secondary label, the dock's count trailing. */
@Composable
private fun DockSectionHeader(title: String, detail: String?, colors: AppColors) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = colors.textSecondary)
        Spacer(Modifier.weight(1f))
        if (detail != null) {
            Text(detail, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = colors.textSecondary)
        }
    }
}

/** iOS section footer: 13pt secondary label under the card. */
@Composable
private fun DockSectionFooter(text: String, colors: AppColors) {
    Text(
        text = text,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        color = colors.textSecondary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
    )
}

/** iOS's edit-mode control: a filled circle with a white minus or plus. */
@Composable
private fun CircleControl(color: Color, plus: Boolean, description: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(24.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier.size(22.dp).clip(CircleShape).background(color),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(width = 11.dp, height = 2.dp).clip(RoundedCornerShape(1.dp)).background(Color.White))
            if (plus) {
                Box(Modifier.size(width = 2.dp, height = 11.dp).clip(RoundedCornerShape(1.dp)).background(Color.White))
            }
        }
    }
}

/**
 * Where a row sits while a drag is in progress: everything between the gap the held row left and
 * the slot it is hovering over shifts one place to close it.
 */
private fun displacedIndex(index: Int, from: Int, to: Int): Int = when {
    to > from && index > from && index <= to -> index - 1
    to < from && index >= to && index < from -> index + 1
    else -> index
}

/** Slides a row aside; the held row tracks the finger instead and is never animated. */
private val SLIDE_SPEC = spring<Float>(dampingRatio = 0.82f, stiffness = Spring.StiffnessMediumLow)

/**
 * Whether the list is mid-settle, and a spec that does not animate while it is: on release the
 * list is rewritten in the new order AND every slide target drops back to zero, which a spring
 * would animate as the row sliding back across its own new slot.
 */
@Composable
private fun rememberSettling(): Pair<() -> Unit, AnimationSpec<Float>> {
    var settling by remember { mutableStateOf(false) }
    LaunchedEffect(settling) {
        if (settling) {
            delay(100)
            settling = false
        }
    }
    return Pair({ settling = true }, if (settling) snap() else SLIDE_SPEC)
}

/**
 * One inset-grouped card of rows, reordered by dragging the grip on each row's trailing edge (the
 * iOS List edit-mode reorder control). The held row tracks the finger and the rows it passes
 * slide aside; the move is reported once, on release.
 */
@Composable
private fun ReorderableTabCard(
    items: List<AppTab>,
    colors: AppColors,
    separatorStart: Dp,
    onMove: (from: Int, to: Int) -> Unit,
    content: @Composable (AppTab) -> Unit,
) {
    // An empty section shows its header and footer only, as an empty iOS List section does.
    if (items.isEmpty()) return

    val density = LocalDensity.current
    val stridePx = with(density) { ROW_HEIGHT.toPx() }
    val latestItems by rememberUpdatedState(items)
    val latestOnMove by rememberUpdatedState(onMove)

    var draggingKey by remember { mutableStateOf<String?>(null) }
    var dragStartIndex by remember { mutableIntStateOf(0) }
    var dragY by remember { mutableFloatStateOf(0f) }
    var targetIndex by remember { mutableIntStateOf(-1) }
    val (settle, slideSpec) = rememberSettling()

    fun targetFor(): Int =
        (dragStartIndex + (dragY / stridePx).roundToInt()).coerceIn(0, latestItems.lastIndex.coerceAtLeast(0))

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(26.dp))
            .background(colors.surface)
            .animateContentSize(),
        verticalArrangement = Arrangement.Top,
    ) {
        items.forEachIndexed { index, tab ->
            key(tab.key) {
                val latestIndex by rememberUpdatedState(index)
                val isDragging = draggingKey == tab.key
                val moved = if (draggingKey != null && !isDragging) displacedIndex(index, dragStartIndex, targetIndex) else index
                val slideY by animateFloatAsState(
                    targetValue = (moved - index) * stridePx,
                    animationSpec = slideSpec,
                    label = "dockRowSlide",
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(ROW_HEIGHT)
                        .zIndex(if (isDragging) 1f else 0f)
                        .graphicsLayer { translationY = if (isDragging) dragY else slideY }
                        .shadow(if (isDragging) 8.dp else 0.dp)
                        .background(colors.surface),
                ) {
                    Row(
                        modifier = Modifier.fillMaxSize().padding(start = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        content(tab)
                        Spacer(Modifier.weight(1f))
                        // The reorder grip. Dragging starts here, as on iOS, so a swipe anywhere
                        // else on the row still scrolls the page.
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .width(56.dp)
                                .pointerInput(tab.key) {
                                    detectDragGestures(
                                        onDragStart = {
                                            dragStartIndex = latestIndex
                                            draggingKey = tab.key
                                            dragY = 0f
                                            targetIndex = latestIndex
                                        },
                                        onDragEnd = {
                                            settle()
                                            val target = targetFor()
                                            if (target != dragStartIndex) latestOnMove(dragStartIndex, target)
                                            draggingKey = null
                                            targetIndex = -1
                                        },
                                        onDragCancel = {
                                            draggingKey = null
                                            targetIndex = -1
                                        },
                                        onDrag = { change, amount ->
                                            change.consume()
                                            dragY += amount.y
                                            targetIndex = targetFor()
                                        },
                                    )
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Filled.Menu, contentDescription = null, tint = colors.textTertiary, modifier = Modifier.size(22.dp))
                        }
                    }
                    if (index < items.lastIndex && !isDragging) {
                        HorizontalDivider(
                            color = colors.divider,
                            thickness = 0.5.dp,
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .padding(start = separatorStart, end = 16.dp),
                        )
                    }
                }
            }
        }
    }
}
