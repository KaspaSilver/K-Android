package com.kachat.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.kachat.app.R
import com.kachat.app.ui.theme.IosActivityIndicator

/**
 * What a cold launch shows while the account loads (iOS 518fdc8c `LoadingView`): the splash
 * screen's K globe (`drawable/splash_globe`: `k_logo` at 120 dp, centred on the launch colour,
 * white or black with the app's appearance), in the same place and size, so the splash screen
 * hands over without a jump. The spinner sits 100 under the globe's centre. Centred on the whole
 * window, not inside the system bars, as the splash screen centres it.
 */
@Composable
fun LaunchGlobeView() {
    val loading = stringResource(R.string.loading_ellipsis)
    Box(
        modifier = Modifier.fillMaxSize().background(colorResource(R.color.launch_background)),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(R.drawable.k_logo),
            contentDescription = "K",
            modifier = Modifier.size(120.dp),
        )
        IosActivityIndicator(
            modifier = Modifier
                .offset(y = 100.dp)
                .size(20.dp)
                .semantics { contentDescription = loading },
        )
    }
}
