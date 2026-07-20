package com.kezo.tide.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.kezo.tide.ui.ROW_UNITS
import com.kezo.tide.ui.TideScreen
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.flow.StateFlow

/** Short label for an audio-quality value, used on the settings row. */
fun qualityLabel(value: String): String = when (value) {
    "LOW" -> "Low"
    "LOSSLESS" -> "Lossless"
    else -> "High"
}

private val QUALITY_OPTIONS = listOf(
    Triple("LOW", "Low", "96 kbps AAC"),
    Triple("HIGH", "High", "320 kbps AAC"),
    Triple("LOSSLESS", "Lossless", "FLAC · best quality"),
)

class AudioQualityViewModel : LightViewModel<Unit>()

/**
 * LightOS-style select-from-list sub-page: each option is a row with a
 * checkmark on the selected one.
 */
class AudioQualityScreen(
    sealedActivity: SealedLightActivity,
    private val quality: StateFlow<String>,
    private val onSelect: (String) -> Unit,
) : LightScreen<Unit, AudioQualityViewModel>(sealedActivity) {

    override val viewModelClass: Class<AudioQualityViewModel>
        get() = AudioQualityViewModel::class.java

    override fun createViewModel() = AudioQualityViewModel()

    @Composable
    override fun Content() {
        val current by quality.collectAsState()

        TideScreen {
            LightTopBar(
                leftButton = LightBarButton.LightIcon(LightIcons.BACK, onClick = { goBack() }),
                center = LightTopBarCenter.Text("Audio Quality"),
            )
            LightScrollView(modifier = Modifier.weight(1f).fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 1f.gridUnitsAsDp()),
                ) {
                    QUALITY_OPTIONS.forEach { (value, label, sub) ->
                        QualityRow(
                            label = label,
                            sub = sub,
                            selected = current == value,
                            onClick = { onSelect(value) },
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun QualityRow(
        label: String,
        sub: String,
        selected: Boolean,
        onClick: () -> Unit,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(ROW_UNITS.gridUnitsAsDp())
                .lightClickable(onClick = onClick),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                LightText(text = label, variant = LightTextVariant.Subheading)
                LightText(text = sub, variant = LightTextVariant.Detail, lighten = true)
            }
            if (selected) {
                LightIcon(icon = LightIcons.ACCEPT, size = 1.7f)
            }
        }
    }
}
