package app.orbit.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.orbit.tv.catalog.UpdateState

@Composable
fun BootScreen(
    update: UpdateState,
    error: String?,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val (title, detail) = when {
        error != null -> "Catalog unavailable" to error
        update is UpdateState.Downloading -> "Downloading channels" to "Getting the latest catalog \u00b7 ${update.progress}%"
        update is UpdateState.Failed -> "Could not update" to update.message
        else -> "Preparing Orbit" to "Checking the channel catalog\u2026"
    }

    Box(
        modifier = modifier.fillMaxSize().background(OrbitColors.Background),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(OrbitColors.Surface),
                contentAlignment = Alignment.Center,
            ) {
                Label("O", size = 28, color = OrbitColors.Accent, weight = FontWeight.Bold)
            }
            Spacer(Modifier.height(8.dp))
            Label(title, size = 22, weight = FontWeight.SemiBold)
            Label(detail, size = 13, color = OrbitColors.Muted)

            if (update is UpdateState.Downloading) {
                Box(
                    Modifier
                        .padding(top = 6.dp)
                        .width(240.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(OrbitColors.SurfaceHigh),
                ) {
                    Box(
                        Modifier
                            .width((240 * update.progress / 100).dp)
                            .height(4.dp)
                            .background(OrbitColors.Accent),
                    )
                }
            }

            if (error != null) {
                Row(Modifier.padding(top = 12.dp)) {
                    OrbitButton("Try again", onClick = onRetry, primary = true)
                }
            }
            Spacer(Modifier.height(4.dp))
            Label("Free, open channel catalog \u00b7 no account needed", size = 11, color = OrbitColors.Muted)
        }
    }
}
