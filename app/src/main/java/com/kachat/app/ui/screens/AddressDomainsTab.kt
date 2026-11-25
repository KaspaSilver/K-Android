package com.kachat.app.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The Domains tab of an address screen (the chatting address, a spending address, a KasSigner
 * address): the .kachat names that address holds, the only name service since 2026-11-23 (iOS
 * 518fdc8c `AddressDomainsTab` = `KachatAddressDomainsList`). [onOpenName] opens a name's page,
 * which the host shows full screen.
 */
@Composable
fun AddressDomainsTab(
    address: String,
    onOpenName: (com.kachat.app.services.kachatnames.NameInfo) -> Unit,
    /** Extra room at the scroll's end for what floats over the page's foot (its bar, the dock). */
    bottomPadding: Dp = 0.dp,
) {
    KachatAddressDomainsList(address.takeIf { it.isNotEmpty() }, onOpen = onOpenName, bottomPadding = bottomPadding)
}
