package com.kachat.app.ui.screens

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kachat.app.R
import com.kachat.app.ui.theme.KaspaTeal
import com.kachat.app.ui.theme.LocalAppColors
import com.kachat.app.ui.theme.mirrorInRtl
import com.kachat.app.util.IosHaptic
import com.kachat.app.util.rememberHaptics

/** Manage Addresses' Used / Unused / Checking (iOS 824a30bb `ManagedAddressCard.UsedState`). */
enum class ManagedAddressUsed { USED, UNUSED, CHECKING }

/** Whether an address keeps its key hidden from a future quantum computer (iOS 824a30bb
 *  `QuantumProtection`). */
enum class QuantumProtection {
    /** A quantum-resistant address that has never spent. */
    PROTECTED,
    /** A regular address, or a quantum one whose key a spend revealed. */
    NOT_PROTECTED,
    /** A quantum address whose key a spend revealed, with funds on it again. */
    REVEALED_WITH_FUNDS;

    companion object {
        /**
         * The quantum (KIP-22) state of an address, spelled out on its card (iOS 824a30bb
         * `quantumState(for:)`): protected only for a quantum-resistant address whose key has never
         * been on chain; any regular address, or a quantum one that SPENT once (its key public
         * forever), is not protected, and that one is orange while funds sit on it again.
         */
        fun of(isQuantum: Boolean, spent: Boolean, balanceSompi: Long): QuantumProtection = when {
            !isQuantum -> NOT_PROTECTED
            !spent -> PROTECTED
            balanceSompi > 0L -> REVEALED_WITH_FUNDS
            else -> NOT_PROTECTED
        }
    }
}

/** The balance card's teal gradient (iOS 824a30bb `BalanceCardBackground`), also behind each
 *  address card on Manage Addresses and Manage kUSDT. */
fun Modifier.balanceCardBackground(cornerRadius: Dp = 22.dp): Modifier {
    val shape = RoundedCornerShape(cornerRadius)
    return this
        .clip(shape)
        .background(Brush.linearGradient(listOf(KaspaTeal.copy(alpha = 0.32f), KaspaTeal.copy(alpha = 0.08f))))
        .border(0.8.dp, KaspaTeal.copy(alpha = 0.35f), shape)
}

/**
 * "Quantum Protected" / "Not Quantum Protected", spelled out on an address card in place of the
 * shield icon (iOS 824a30bb `QuantumProtectionTag`): green when protected, orange when a revealed
 * key has funds on it again, gray otherwise.
 */
@Composable
fun QuantumProtectionTag(state: QuantumProtection) {
    val colors = LocalAppColors.current
    val protected = state == QuantumProtection.PROTECTED
    val color = when (state) {
        QuantumProtection.PROTECTED -> colors.success
        QuantumProtection.REVEALED_WITH_FUNDS -> colors.warning
        QuantumProtection.NOT_PROTECTED -> colors.textSecondary
    }
    Text(
        stringResource(if (protected) R.string.addrcard_quantum_protected else R.string.addrcard_not_quantum_protected),
        color = color,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        softWrap = false,
        modifier = Modifier
            .background(color.copy(alpha = 0.15f), RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

/**
 * A card's in-place rename (iOS b49a021d `InlineRename`, owner 2026-11-27: no pop-up): the name it
 * has now ([current], "" for the default), the default shown while the field is empty
 * ([placeholder]), and where the name is saved when the rename ends ([onChange], once, not per
 * keystroke: iOS d88891ea, audit 2026-11-27 L20; an empty name goes back to the default).
 */
class InlineRename(
    val current: String,
    val placeholder: String,
    val onChange: (String) -> Unit,
)

/** The save rules behind [InlineRename] (iOS b49a021d), kept pure for tests. */
object InlineRenameRules {
    /** An address's label as saved from what's typed: trimmed, and null (the default name,
     *  "Address #n") when nothing is left. */
    fun addressLabel(typed: String): String? = typed.trim().ifEmpty { null }

    /** A Cold Storage account's name as saved from what's typed: trimmed; an empty name keeps the
     *  old one (iOS `ColdStorageManager.renameAccount`). */
    fun accountName(typed: String, old: String): String = typed.trim().ifEmpty { old }

    /** The text the field starts with for a kUSDT card: the name it was given, or empty when it
     *  has the default ([defaultTitle], "Address #n" as the card shows it). Only the default
     *  itself: a saved name like "Address #5 savings" is the person's own (iOS d88891ea, audit
     *  2026-11-27 L14). */
    fun kusdtStartingText(label: String, defaultTitle: String): String = if (label == defaultTitle) "" else label

    /** The rename ended: whether what's typed is saved (iOS d88891ea `finishRename`: only a
     *  change from the name it had). */
    fun shouldSave(draft: String, current: String): Boolean = draft != current

    /** A Cold Storage account's rename ended: the name to save, or null to keep the one it has
     *  (empty, or unchanged). Saved once here, not per keystroke, which left a cleared name as its
     *  first letter (iOS d88891ea, audit 2026-11-27 M6). */
    fun accountNameToSave(draft: String, current: String): String? =
        draft.trim().takeIf { it.isNotEmpty() && it != current }
}

/**
 * One address on Manage Addresses (iOS 824a30bb `ManagedAddressCard`, owner 2026-11-26): drawn like
 * the Total Balance card, with its [name], balance ([amount], large), worth in your currency
 * ([detail]) and Used / Unused, then Copy, Send, Receive and Rename, and "Tap to View History" (the
 * whole card opens the address's page, [onOpen], where the full address shows). The address itself
 * isn't on the card. Manage kUSDT and Cold Storage draw their addresses with it too.
 *
 * [quantum] null leaves the tag off (kUSDT: it only ever lives on regular addresses). [rename] is
 * in place (iOS b49a021d, owner 2026-11-27): Rename turns the name into a text field with the
 * keyboard up, the quantum tag steps aside and the button reads Done; the name is saved when the
 * rename ends (Done, return, the keyboard going away). Null for the Chatting Address, which keeps its name. [onMore] is
 * the ⋮: the address's other actions, null for none; with [moreIsHide] the top-right button is an
 * eye with a slash that hides the address at once (Cold Storage, where Hide is the only other
 * action). Send is off when [canSend] is false (nothing to send).
 */
@Composable
fun ManagedAddressCard(
    name: String,
    amount: String,
    canSend: Boolean,
    used: ManagedAddressUsed,
    ownsUsername: Boolean,
    quantum: QuantumProtection?,
    onCopy: () -> Unit,
    onSend: () -> Unit,
    onReceive: () -> Unit,
    rename: InlineRename?,
    onMore: (() -> Unit)?,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    moreIsHide: Boolean = false,
    nameAccessory: @Composable RowScope.() -> Unit = {},
    detail: @Composable ColumnScope.() -> Unit = {},
) {
    val colors = LocalAppColors.current
    val haptic = rememberHaptics()
    val focusManager = LocalFocusManager.current
    var isRenaming by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    val renamingNow = isRenaming && rename != null
    val latestRename by rememberUpdatedState(rename)
    // The rename ends (Done, return, the keyboard going away): the name typed is saved once,
    // here, not on every keystroke, which re-saved and redrew the list each time (iOS d88891ea,
    // audit 2026-11-27 L20). The field shows it as it's typed.
    val finishRename: () -> Unit = {
        if (isRenaming) {
            isRenaming = false
            latestRename?.let { r -> if (InlineRenameRules.shouldSave(draft, r.current)) r.onChange(draft) }
        }
    }
    // scrolled away or the screen closed mid-rename: what was typed is kept
    DisposableEffect(Unit) { onDispose { finishRename() } }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .balanceCardBackground()
            .clickable(onClick = onOpen)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (renamingNow && rename != null) {
                    InlineRenameField(
                        placeholder = rename.placeholder,
                        draft = draft,
                        onDraftChange = { draft = it },
                        onEnd = finishRename,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    Text(
                        name,
                        color = colors.textPrimary,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
                nameAccessory()
                // Beside the name (owner, 2026-11-26); out of the way while it's typed
                if (quantum != null && !renamingNow) QuantumProtectionTag(quantum)
            }
            Spacer(Modifier.width(4.dp))
            if (onMore != null) {
                // iOS: a bold "ellipsis" turned upright, secondary, in a 30 frame; with
                // moreIsHide an "eye.slash" (iOS b49a021d).
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .clickable(role = Role.Button) {
                            haptic(IosHaptic.IMPACT_LIGHT)
                            onMore()
                        },
                ) {
                    if (moreIsHide) {
                        Icon(Icons.Filled.VisibilityOff, contentDescription = stringResource(R.string.hide_address), tint = colors.textSecondary, modifier = Modifier.size(20.dp))
                    } else {
                        Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.kpui_more), tint = colors.textSecondary, modifier = Modifier.size(24.dp))
                    }
                }
            }
        }
        KachatFitText(
            amount,
            color = colors.textPrimary,
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            minScale = 0.5f,
        )
        // Its worth in your currency, and anything else the asset needs (subheadline, secondary).
        detail()
        val (usedText, usedColor) = when (used) {
            ManagedAddressUsed.USED -> stringResource(R.string.pr_used) to colors.warning
            ManagedAddressUsed.UNUSED -> stringResource(R.string.pr_unused) to colors.success
            ManagedAddressUsed.CHECKING -> stringResource(R.string.pr_checking) to colors.textSecondary
        }
        Text(usedText, color = usedColor, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 2.dp))
        if (ownsUsername) {
            Box(Modifier.padding(top = 2.dp)) { ContainsDomainTag() }
        }

        Row(Modifier.fillMaxWidth().padding(top = 10.dp)) {
            AddressCardActionButton(stringResource(R.string.copy), Icons.Filled.ContentCopy, enabled = true, onClick = onCopy)
            AddressCardActionButton(stringResource(R.string.send), Icons.Filled.ArrowUpward, enabled = canSend, onClick = onSend)
            AddressCardActionButton(stringResource(R.string.receive), Icons.Filled.ArrowDownward, enabled = true, onClick = onReceive)
            if (rename != null) {
                if (renamingNow) {
                    AddressCardActionButton(stringResource(R.string.done), Icons.Filled.Check, enabled = true) {
                        focusManager.clearFocus()
                        finishRename()
                    }
                } else {
                    AddressCardActionButton(stringResource(R.string.rename), Icons.Filled.Edit, enabled = true) {
                        draft = rename.current
                        isRenaming = true
                    }
                }
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.addrcard_tap_to_view_history), color = KaspaTeal, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = KaspaTeal, modifier = Modifier.size(14.dp).mirrorInRtl())
        }
    }
}

/**
 * The name as a text field, in the name's own place and size, while a card is being renamed (iOS
 * b49a021d `InlineRenameField`): focused with the keyboard up as it appears, [placeholder] (the
 * default name) while empty. Return, a lost focus or the keyboard going away ends it ([onEnd]).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun InlineRenameField(
    placeholder: String,
    draft: String,
    onDraftChange: (String) -> Unit,
    onEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalAppColors.current
    val focusManager = LocalFocusManager.current
    val focusRequester = remember { FocusRequester() }
    var hadFocus by remember { mutableStateOf(false) }
    // A turn later: the field doesn't exist yet on the tap that shows it.
    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }
    // The keyboard going away (Back, a swipe) ends the rename, as on iOS.
    val imeVisible = WindowInsets.isImeVisible
    var imeWasVisible by remember { mutableStateOf(false) }
    LaunchedEffect(imeVisible) {
        if (imeVisible) {
            imeWasVisible = true
        } else if (imeWasVisible) {
            focusManager.clearFocus()
            onEnd()
        }
    }
    val shape = RoundedCornerShape(8.dp)
    BasicTextField(
        value = draft,
        onValueChange = onDraftChange,
        singleLine = true,
        textStyle = TextStyle(color = colors.textPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
        cursorBrush = SolidColor(KaspaTeal),
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.Words,
            autoCorrect = false,
            imeAction = ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(onDone = {
            focusManager.clearFocus()
            onEnd()
        }),
        modifier = modifier
            .focusRequester(focusRequester)
            .onFocusChanged {
                if (it.isFocused) hadFocus = true else if (hadFocus) onEnd()
            },
        decorationBox = { inner ->
            Box(
                modifier = Modifier
                    .background(colors.textPrimary.copy(alpha = 0.08f), shape)
                    .border(1.dp, KaspaTeal.copy(alpha = 0.6f), shape)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                if (draft.isEmpty()) {
                    Text(placeholder, color = colors.textSecondary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                }
                inner()
            }
        },
    )
}

/** A round button with its name under it (iOS b49a021d `AddressCardActionButton`), on Manage
 *  Addresses' cards and Cold Storage's account cards: it takes only its own taps, so the rest of
 *  the card still opens what the card leads to. */
@Composable
fun RowScope.AddressCardActionButton(title: String, icon: ImageVector, enabled: Boolean = true, onClick: () -> Unit) {
    val colors = LocalAppColors.current
    val haptic = rememberHaptics()
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .weight(1f)
            .alpha(if (enabled) 1f else 0.4f)
            .clickable(enabled = enabled, role = Role.Button) {
                haptic(IosHaptic.IMPACT_LIGHT)
                onClick()
            },
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(42.dp).background(KaspaTeal.copy(alpha = 0.18f), CircleShape),
        ) {
            Icon(icon, contentDescription = null, tint = KaspaTeal, modifier = Modifier.size(18.dp))
        }
        KachatFitText(
            title,
            color = colors.textPrimary,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            minScale = 0.8f,
        )
    }
}

/** The amount Manage Addresses' and Cold Storage's cards show: "1,234.50000000 KAS", "-- KAS"
 *  while unknown, and "0 KAS" for an empty address, not "0.00000000 KAS" (iOS 01f71184
 *  `ManagedAddressCardAmount.kas`, release audit). */
fun managedKasAmount(sompi: Long?): String =
    "${sompi?.let { if (it == 0L) "0" else com.kachat.app.util.KaspaUnit.groupedExact(it) } ?: "--"} ${com.kachat.app.util.KaspaUnit.symbol}"
