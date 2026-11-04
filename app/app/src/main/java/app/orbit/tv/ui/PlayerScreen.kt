package app.orbit.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import app.orbit.tv.MainViewModel
import app.orbit.tv.UiState
import app.orbit.tv.catalog.ChannelCard
import app.orbit.tv.player.PlaybackPhase
import app.orbit.tv.player.PlaybackState
import coil.compose.AsyncImage

@Composable
fun PlayerScreen(
    ui: UiState,
    playback: PlaybackState,
    infoVisible: Boolean,
    digits: String,
    vm: MainViewModel,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val playerView = remember {
        PlayerView(context).apply {
            useController = false
            setKeepContentOnPlayerReset(true)
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
            setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
            setBackgroundColor(android.graphics.Color.BLACK)
        }
    }
    LaunchedEffect(Unit) {
        vm.playback.attach(playerView)
    }
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose { playerView.player = null }
    }

    Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(factory = { playerView }, modifier = Modifier.fillMaxSize())

        if (!playback.firstFrame && playback.phase != PlaybackPhase.Failed) {
            PreparingOverlay(playback)
        }
        if (playback.phase == PlaybackPhase.Failed) {
            FailedOverlay(playback)
        }

        if (infoVisible && playback.firstFrame && playback.phase != PlaybackPhase.Failed) {
            PlayerTopBar(playback, Modifier.align(Alignment.TopCenter))
        }

        if ((infoVisible || !playback.firstFrame) && playback.phase != PlaybackPhase.Failed) {
            InfoBar(playback, Modifier.align(Alignment.BottomStart))
        }

        if (digits.isNotEmpty()) {
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 26.dp)
                    .background(Color(0xEE14161C), RoundedCornerShape(8.dp))
                    .border(1.dp, OrbitColors.Accent, RoundedCornerShape(8.dp))
                    .padding(horizontal = 22.dp, vertical = 8.dp),
            ) {
                Label("$digits", size = 24, color = OrbitColors.Accent, weight = FontWeight.Bold, letterSpacing = 4f)
            }
        }

        if (ui.quickPanel) {
            QuickPanel(
                items = ui.quickItems,
                favorites = ui.favorites,
                onPlay = vm::playQuick,
            )
        }
    }
}

@Composable
private fun PreparingOverlay(playback: PlaybackState) {
    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    listOf(Color(0xFF161A23), Color(0xFF07080B)),
                ),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val card = playback.channel
            if (card != null) {
                MonogramCard(card, 92.dp)
                Spacer(Modifier.height(8.dp))
                Label(card.name, size = 20, weight = FontWeight.SemiBold)
                Label(
                    if (playback.streamCount > 1) "Connecting \u00b7 stream ${playback.streamIndex + 1} of ${playback.streamCount}"
                    else "Connecting\u2026",
                    size = 12,
                    color = OrbitColors.Muted,
                )
            } else {
                Label("Connecting\u2026", size = 16, color = OrbitColors.Muted)
            }
            if (playback.message != null) {
                Label(playback.message, size = 11, color = OrbitColors.Warning)
            }
        }
    }
}

@Composable
private fun FailedOverlay(playback: PlaybackState) {
    Box(Modifier.fillMaxSize().background(Color(0xF207080B)), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            playback.channel?.let { MonogramCard(it, 84.dp) }
            Label(playback.channel?.name ?: "Channel unavailable", size = 20, weight = FontWeight.SemiBold)
            Label(
                playback.message ?: "This channel is not available right now.",
                size = 12,
                color = OrbitColors.Muted,
            )
            Label(
                "Try \u2191 \u2193 for another channel \u00b7 OK for My channels \u00b7 Back to browse",
                size = 11,
                color = OrbitColors.Warning,
            )
        }
    }
}

@Composable
private fun PlayerTopBar(playback: PlaybackState, modifier: Modifier = Modifier) {
    val card = playback.channel
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color(0xE6000000), Color(0x00000000))))
            .padding(start = 30.dp, end = 30.dp, top = 18.dp, bottom = 34.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(40.dp)
                .background(Color(0x33FFFFFF), RoundedCornerShape(50)),
            contentAlignment = Alignment.Center,
        ) {
            Label("\u2190", size = 18, color = Color.White, weight = FontWeight.Bold)
        }
        Spacer(Modifier.width(14.dp))
        Label(card?.name ?: "Orbit", size = 22, color = Color.White, weight = FontWeight.Bold)
        Spacer(Modifier.width(12.dp))
        LiveBadge()
        Spacer(Modifier.weight(1f))
        Label(
            card?.let {
                listOfNotNull(
                    "CH ${it.number}",
                    it.category.takeIf { v -> v.isNotBlank() },
                    it.language.takeIf { v -> v.isNotBlank() },
                ).joinToString("  \u00b7  ")
            } ?: "",
            size = 11,
            color = OrbitColors.Muted,
        )
        Spacer(Modifier.width(18.dp))
        Label("OK menu  \u00b7  Back to browse", size = 10, color = OrbitColors.Muted)
    }
}

@Composable
private fun InfoBar(playback: PlaybackState, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(Color.Transparent, Color(0xE607080B)),
                ),
            )
            .padding(start = 44.dp, end = 44.dp, top = 40.dp, bottom = 24.dp),
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            playback.channel?.let { MonogramCard(it, 46.dp) }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (playback.phase == PlaybackPhase.Playing) LiveBadge()
                    Label(playback.channel?.name ?: "", size = 18, weight = FontWeight.SemiBold)
                }
                Label(
                    listOfNotNull(
                        playback.channel?.network?.takeIf { it.isNotBlank() },
                        playback.channel?.yearLaunched()?.takeIf { it.isNotBlank() },
                        playback.channel?.category,
                        playback.channel?.language,
                        playback.channel?.country,
                        playback.channel?.number?.let { "CH $it" },
                    ).joinToString("  \u00b7  "),
                    size = 11,
                    color = OrbitColors.Muted,
                )
            }
            Label(
                "OK menu  \u00b7  \u2191\u2193 channel  \u00b7  CH\u00b1 zap  \u00b7  0-9 jump  \u00b7  Back browse",
                size = 10,
                color = OrbitColors.Muted,
            )
        }
    }
}

@Composable
private fun MonogramCard(card: ChannelCard, size: androidx.compose.ui.unit.Dp) {
    ChannelArtwork(
        card = card,
        modifier = Modifier.size(size),
        shape = RoundedCornerShape(10.dp),
        padding = 12.dp,
    )
}

@Composable
private fun QuickPanel(
    items: List<ChannelCard>,
    favorites: Set<String>,
    onPlay: (ChannelCard) -> Unit,
) {
    val firstItem = remember { FocusRequester() }
    LaunchedEffect(items) {
        withFrameNanos { }
        runCatching { firstItem.requestFocus() }
    }
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.End) {
        Column(
            Modifier
                .width(420.dp)
                .fillMaxHeight()
                .background(Color(0xF20B0D12))
                .border(1.dp, OrbitColors.Line)
                .padding(horizontal = 22.dp, vertical = 22.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Label("MY CHANNELS", size = 13, color = OrbitColors.Accent, weight = FontWeight.Bold, letterSpacing = 2f)
                Spacer(Modifier.weight(1f))
                Label("OK to play", size = 10, color = OrbitColors.Muted)
            }
            Spacer(Modifier.height(12.dp))
            if (items.isEmpty()) {
                Label(
                    "Nothing here yet. Long-press OK on a channel from Home to add a favourite.",
                    size = 12,
                    color = OrbitColors.Muted,
                    lines = 3,
                )
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(items, key = { it.id }) { card ->
                    QuickRow(
                        card = card,
                        favorite = favorites.contains(card.id),
                        focusRequester = if (card === items.firstOrNull()) firstItem else null,
                        onPlay = { onPlay(card) },
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            Label("Back closes this menu", size = 10, color = OrbitColors.Muted)
        }
    }
}

@Composable
private fun QuickRow(
    card: ChannelCard,
    favorite: Boolean,
    focusRequester: FocusRequester?,
    onPlay: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(6.dp)
    val focusMod = if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .orbitFocusSound()
            .onFocusChanged { focused = it.isFocused }
            .then(focusMod)
            .clip(shape)
            .background(if (focused) OrbitColors.SurfaceHigh else OrbitColors.Surface)
            .border(1.dp, if (focused) OrbitColors.Accent else OrbitColors.Line, shape)
            .clickable(onClick = onPlay)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ChannelArtwork(
            card = card,
            modifier = Modifier.size(width = 66.dp, height = 38.dp),
            shape = RoundedCornerShape(4.dp),
            padding = 6.dp,
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Label(card.name, size = 12, weight = FontWeight.Medium)
            Label("${card.category} \u00b7 ${card.language}", size = 9, color = OrbitColors.Muted)
        }
        if (favorite) Label("\u2605", size = 10, color = OrbitColors.Gold)
    }
}
