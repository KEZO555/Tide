package com.kezo.tide.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewModelScope
import com.kezo.tide.api.Tidal
import com.kezo.tide.player.TidePlayer
import com.kezo.tide.ui.TideScreen
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

private val QUALITIES = listOf("LOW", "HIGH", "LOSSLESS")

class SettingsViewModel : LightViewModel<Unit>() {
    val quality = MutableStateFlow(Tidal.quality)
    val signedOut = MutableStateFlow(false)

    fun cycleQuality() {
        val next = QUALITIES[(QUALITIES.indexOf(quality.value) + 1).mod(QUALITIES.size)]
        quality.value = next
        viewModelScope.launch { Tidal.setQuality(next) }
    }

    fun signOut() {
        TidePlayer.stop()
        viewModelScope.launch {
            Tidal.logout()
            signedOut.value = true
        }
    }
}

class SettingsScreen(sealedActivity: SealedLightActivity) :
    LightScreen<Unit, SettingsViewModel>(sealedActivity) {

    override val viewModelClass: Class<SettingsViewModel>
        get() = SettingsViewModel::class.java

    override fun createViewModel() = SettingsViewModel()

    @Composable
    override fun Content() {
        val quality by viewModel.quality.collectAsState()
        val signedOut by viewModel.signedOut.collectAsState()
        val themeColors by LightThemeController.colors.collectAsState()

        LaunchedEffect(signedOut) {
            // pop back to home, which notices the cleared session on show
            if (signedOut) goBack()
        }

        TideScreen {
            LightTopBar(
                leftButton = LightBarButton.LightIcon(LightIcons.BACK, onClick = { goBack() }),
                center = LightTopBarCenter.Text("settings"),
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 1f.gridUnitsAsDp()),
            ) {
                Spacer(modifier = Modifier.height(1f.gridUnitsAsDp()))

                Item("quality") { viewModel.cycleQuality() }
                Detail(
                    when (quality) {
                        "LOW" -> "low  ·  96 kbps aac"
                        "HIGH" -> "high  ·  320 kbps aac"
                        else -> "lossless  ·  flac"
                    }
                )

                Item("theme") { LightThemeController.toggle() }
                Detail(
                    if (themeColors == com.thelightphone.sdk.ui.LightThemeColors.Dark) "dark"
                    else "light"
                )

                Item("account") { }
                Detail("user ${Tidal.userId}  ·  ${Tidal.countryCode.lowercase()}")

                Item("sign out") { viewModel.signOut() }
            }
            LightText(
                text = "tide  ·  an unofficial tidal client",
                variant = LightTextVariant.Superfine,
                lighten = true,
                modifier = Modifier.padding(
                    start = 1f.gridUnitsAsDp(),
                    bottom = 1f.gridUnitsAsDp(),
                ),
            )
        }
    }

    @Composable
    private fun Item(label: String, onClick: () -> Unit) {
        LightText(
            text = label,
            variant = LightTextVariant.Copy,
            modifier = Modifier
                .fillMaxWidth()
                .lightClickable(onClick = onClick)
                .padding(top = 0.75f.gridUnitsAsDp()),
        )
    }

    @Composable
    private fun Detail(text: String) {
        LightText(
            text = text,
            variant = LightTextVariant.Superfine,
            lighten = true,
        )
    }
}
