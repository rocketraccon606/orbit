package app.orbit.tv.ui

import app.orbit.tv.BuildConfig

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.orbit.tv.MainViewModel
import app.orbit.tv.UiState
import app.orbit.tv.catalog.UpdateState

@Composable
fun SettingsScreen(
    ui: UiState,
    update: UpdateState,
    vm: MainViewModel,
    modifier: Modifier = Modifier,
) {
    val firstAction = remember { FocusRequester() }
    val navHome = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { firstAction.requestFocus() }
    }

    Column(modifier = modifier.fillMaxSize().background(OrbitColors.Background)) {
        OrbitNavBar(
            selected = "",
            solid = true,
            onHome = vm::closeSettings,
            onBrowse = vm::openBrowse,
            onSearch = vm::openSearch,
            onSettings = {},
            homeFocus = navHome,
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 56.dp, end = 56.dp, top = 14.dp, bottom = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Label("SETTINGS", size = 17, color = OrbitColors.Accent, weight = FontWeight.Bold, letterSpacing = 3f)
            Spacer(Modifier.weight(1f))
            Label("Back returns home", size = 10, color = OrbitColors.Muted)
        }

        Row(Modifier.padding(horizontal = 56.dp), horizontalArrangement = Arrangement.spacedBy(40.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionTitle("Catalog")
                InfoLine("Version", ui.catalogVersion.ifBlank { "not installed" })
                InfoLine("Channels", "${ui.counts?.playable ?: 0} playable of ${ui.counts?.channels ?: 0}")
                InfoLine("Malayalam", "${ui.counts?.malayalam ?: 0} channels")
                InfoLine("Collections", "${ui.counts?.collections ?: 0}")
                InfoLine(
                    "Updates",
                    when (update) {
                        is UpdateState.Checking -> "checking\u2026"
                        is UpdateState.Downloading -> "downloading ${update.progress}%"
                        is UpdateState.Updated -> "installed"
                        is UpdateState.Failed -> update.message
                        UpdateState.Idle -> "up to date"
                    },
                )
                Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    val buttonPadding = PaddingValues(horizontal = 12.dp, vertical = 9.dp)
                    OrbitButton(
                        "Check for updates",
                        onClick = vm::checkForUpdates,
                        primary = true,
                        focusRequester = firstAction,
                        contentPadding = buttonPadding,
                    )
                    OrbitButton("Clear favourites", onClick = vm::clearFavorites, contentPadding = buttonPadding)
                    OrbitButton("Clear watch history", onClick = vm::clearRecents, contentPadding = buttonPadding)
                }
            }

            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionTitle("About Orbit")
                Label(
                    "Orbit is a free, open-source live TV browser. Channels are compiled from public IPTV catalogs and checked before they are listed. Orbit does not host, re-stream or sell any channel.",
                    size = 12,
                    color = OrbitColors.Muted,
                    lines = 5,
                )
                Spacer(Modifier.padding(2.dp))
                Label(
                    "No account. No ads. No background services. Favourites and history stay on this device.",
                    size = 12,
                    color = OrbitColors.Muted,
                    lines = 3,
                )
                Spacer(Modifier.padding(2.dp))
                Label(
                    "Channel availability depends on the broadcaster. Some streams may be restricted to certain countries or go offline without notice.",
                    size = 11,
                    color = OrbitColors.Muted,
                    lines = 3,
                )
                Spacer(Modifier.padding(2.dp))
                Label(
                    "Orbit ${BuildConfig.VERSION_NAME} \u00b7 catalog schema ${ui.catalogSchema}",
                    size = 10,
                    color = OrbitColors.Muted,
                )
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Label(text, size = 13, color = OrbitColors.Text, weight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 4.dp))
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Label(label, size = 12, color = OrbitColors.Muted, modifier = Modifier.width(120.dp))
        Label(value, size = 12, color = OrbitColors.Text)
    }
}
