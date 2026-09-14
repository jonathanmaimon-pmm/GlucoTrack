package com.glucotrack.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.glucotrack.analysis.GlucoseBand

private val Teal = Color(0xFF00696E)
private val TealLight = Color(0xFF4FD8DF)

private val LightColors = lightColorScheme(
    primary = Teal,
    secondary = Color(0xFF4A6365),
    background = Color(0xFFFAFDFC),
    surface = Color(0xFFFAFDFC),
)

private val DarkColors = darkColorScheme(
    primary = TealLight,
    secondary = Color(0xFFB1CBCD),
    background = Color(0xFF0E1415),
    surface = Color(0xFF0E1415),
)

/**
 * Colours for the three glucose bands.
 *
 * High and low are deliberately distinct hues rather than two shades of red, so the direction of
 * a problem is readable at a glance and without relying on colour vision alone — every place
 * these are used also carries a text label.
 */
object BandColors {
    val low = Color(0xFFD32F2F)
    val inRange = Color(0xFF2E7D32)
    val high = Color(0xFFE38C00)

    fun of(band: GlucoseBand): Color = when (band) {
        GlucoseBand.LOW -> low
        GlucoseBand.IN_RANGE -> inRange
        GlucoseBand.HIGH -> high
    }
}

@Composable
fun GlucoTrackTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
