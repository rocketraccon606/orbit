package app.orbit.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.orbit.tv.MainViewModel
import app.orbit.tv.Screen
import app.orbit.tv.catalog.CatalogState
import app.orbit.tv.catalog.UpdateState
import app.orbit.tv.player.PlaybackPhase
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun OrbitRoot(vm: MainViewModel) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val catalog by vm.catalogState.collectAsStateWithLifecycle()
    val update by vm.updateState.collectAsStateWithLifecycle()
    val playback by vm.playback.state.collectAsStateWithLifecycle()

    var infoVisible by remember { mutableStateOf(true) }
    var digits by remember { mutableStateOf("") }
    var digitJob by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    val playerFocus = remember { FocusRequester() }

    // The player has no focusable children; without this the root never receives
    // remote keys (channel up/down, digits, OK) while a stream is open.
    LaunchedEffect(ui.screen, ui.quickPanel) {
        if (ui.screen == Screen.Player && !ui.quickPanel) {
            withFrameNanos { }
            runCatching { playerFocus.requestFocus() }
        }
    }

    LaunchedEffect(ui.screen, playback.channel?.id, playback.phase) {
        if (ui.screen == Screen.Player && playback.phase != PlaybackPhase.Failed && playback.phase != PlaybackPhase.Idle) {
            infoVisible = true
        }
    }
    LaunchedEffect(infoVisible, ui.screen) {
        if (infoVisible && ui.screen == Screen.Player) {
            delay(4_000)
            infoVisible = false
        }
    }
    LaunchedEffect(ui.screen) {
        if (ui.screen != Screen.Player) {
            digits = ""
            digitJob?.cancel()
        }
    }
    LaunchedEffect(ui.message) {
        if (ui.message != null) {
            delay(2_800)
            vm.clearMessage()
        }
    }

    fun commitDigits() {
        val value = digits.toIntOrNull()
        digits = ""
        digitJob?.cancel()
        if (value != null) vm.jumpToNumber(value)
    }

    BackHandler(enabled = ui.screen != Screen.Home) {
        when (ui.screen) {
            Screen.Player -> if (ui.quickPanel) vm.closeQuickPanel() else vm.closePlayer()
            Screen.Detail -> vm.closeDetail()
            Screen.Browse -> vm.closeBrowse()
            Screen.Search -> vm.closeSearch()
            Screen.Settings -> vm.closeSettings()
            Screen.Home -> Unit
        }
    }

    val keyModifier = Modifier.onPreviewKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        if (ui.screen != Screen.Player) return@onPreviewKeyEvent false

        val digit = when (event.key) {
            Key.Zero -> 0
            Key.One -> 1
            Key.Two -> 2
            Key.Three -> 3
            Key.Four -> 4
            Key.Five -> 5
            Key.Six -> 6
            Key.Seven -> 7
            Key.Eight -> 8
            Key.Nine -> 9
            else -> -1
        }
        if (digit >= 0) {
            digits = (digits + digit).take(3)
            digitJob?.cancel()
            digitJob = scope.launch {
                delay(1_300)
                if (digits.isNotEmpty()) {
                    val value = digits.toIntOrNull()
                    digits = ""
                    if (value != null) vm.jumpToNumber(value)
                }
            }
            return@onPreviewKeyEvent true
        }

        if (ui.quickPanel) return@onPreviewKeyEvent false

        when (event.key) {
            Key.DirectionUp, Key.ChannelUp -> {
                vm.nextChannel(-1)
                true
            }
            Key.DirectionDown, Key.ChannelDown -> {
                vm.nextChannel(1)
                true
            }
            Key.DirectionCenter, Key.Enter -> {
                if (digits.isNotEmpty()) {
                    commitDigits()
                } else {
                    vm.openQuickPanel()
                }
                true
            }
            Key.MediaPlayPause -> {
                when (playback.phase) {
                    PlaybackPhase.Playing -> vm.playback.pause()
                    PlaybackPhase.Idle, PlaybackPhase.Failed -> vm.onForeground()
                    else -> Unit
                }
                true
            }
            Key.MediaPrevious -> {
                vm.lastChannel()
                true
            }
            else -> false
        }
    }

    val playerFocusMod = if (ui.screen == Screen.Player) {
        Modifier.focusRequester(playerFocus).focusable()
    } else {
        Modifier
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(OrbitColors.Background)
            .then(playerFocusMod)
            .then(keyModifier),
    ) {
        when (val state = catalog) {
            is CatalogState.Missing, is CatalogState.Error -> {
                BootScreen(
                    update = update,
                    error = (state as? CatalogState.Error)?.message,
                    onRetry = vm::checkForUpdates,
                )
            }
            is CatalogState.Ready -> {
                if (ui.screen == Screen.Home && ui.loadingHome && ui.rows.isEmpty()) {
                    BootScreen(update = update, error = null, onRetry = vm::checkForUpdates)
                } else {
                    when (ui.screen) {
                        Screen.Home -> HomeScreen(ui = ui, vm = vm)
                        Screen.Browse -> BrowseScreen(ui = ui, vm = vm)
                        Screen.Detail -> DetailScreen(ui = ui, vm = vm)
                        Screen.Search -> SearchScreen(ui = ui, vm = vm)
                        Screen.Settings -> SettingsScreen(ui = ui, update = update, vm = vm)
                        Screen.Player -> PlayerScreen(
                            ui = ui,
                            playback = playback,
                            infoVisible = infoVisible,
                            digits = digits,
                            vm = vm,
                        )
                    }
                }
            }
        }

        ui.message?.let { message ->
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 26.dp)
                    .background(OrbitColors.SurfaceHigh, androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
                    .padding(horizontal = 18.dp, vertical = 9.dp),
            ) {
                Label(message, size = 12, color = OrbitColors.Text)
            }
        }
    }
}
