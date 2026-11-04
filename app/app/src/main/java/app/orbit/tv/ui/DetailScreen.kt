package app.orbit.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.orbit.tv.MainViewModel
import app.orbit.tv.UiState
import app.orbit.tv.catalog.ChannelCard
import app.orbit.tv.catalog.ChannelDetail
import kotlinx.coroutines.launch

@Composable
fun DetailScreen(
    ui: UiState,
    vm: MainViewModel,
    modifier: Modifier = Modifier,
) {
    val detail = ui.detail
    if (detail == null) {
        Box(modifier.fillMaxSize().background(OrbitColors.Background))
        return
    }

    val navHome = remember { FocusRequester() }
    val playFocus = remember { FocusRequester() }
    val aboutFocus = remember { FocusRequester() }
    val listState = rememberLazyListState()
    val similarFocus = remember { mutableStateMapOf<String, FocusRequester>() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(detail.card.id) {
        withFrameNanos { }
        runCatching { playFocus.requestFocus() }
    }

    val card = detail.card
    val palette = categoryPalette(card.category)
    val favorite = ui.favorites.contains(card.id)
    val upToNav = Modifier.onPreviewKeyEvent { event ->
        if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionUp) {
            runCatching { navHome.requestFocus() }
            true
        } else {
            false
        }
    }
    fun focusSimilar() {
        val first = ui.detailSimilar.firstOrNull() ?: return
        scope.launch {
            listState.animateScrollToItem(2)
            repeat(6) {
                withFrameNanos { }
                val requester = similarFocus[first.id]
                if (requester != null) {
                    runCatching { requester.requestFocus() }
                    return@launch
                }
            }
        }
    }

    val downToAbout = Modifier.onPreviewKeyEvent { event ->
        if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown) {
            scope.launch {
                listState.animateScrollToItem(1)
                repeat(6) {
                    withFrameNanos { }
                    if (runCatching { aboutFocus.requestFocus() }.isSuccess) return@launch
                }
            }
            true
        } else {
            false
        }
    }
    val aboutKeys = Modifier
        .focusRequester(aboutFocus)
        .focusable()
        .onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            when (event.key) {
                Key.DirectionDown -> {
                    focusSimilar()
                    true
                }
                Key.DirectionUp -> {
                    scope.launch {
                        listState.animateScrollToItem(0)
                        withFrameNanos { }
                        runCatching { playFocus.requestFocus() }
                    }
                    true
                }
                else -> false
            }
        }

    Column(modifier = modifier.fillMaxSize().background(OrbitColors.Background)) {
        OrbitNavBar(
            selected = "",
            solid = true,
            onHome = vm::closeDetail,
            onBrowse = vm::openBrowse,
            onSearch = vm::openSearch,
            onSettings = vm::openSettings,
            homeFocus = navHome,
        )

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 40.dp),
        ) {
            item(key = "header") {
                DetailHeader(
                    detail = detail,
                    palette = palette,
                    favorite = favorite,
                    playFocus = playFocus,
                    upModifier = upToNav,
                    downModifier = downToAbout,
                    onPlay = vm::playDetail,
                    onFavorite = { vm.toggleFavorite(card) },
                )
            }
            item(key = "about") {
                AboutSection(detail, aboutKeys)
            }
            if (ui.detailSimilar.isNotEmpty()) {
                item(key = "similar") {
                    SimilarSection(
                        items = ui.detailSimilar,
                        favorites = ui.favorites,
                        requesters = similarFocus,
                        onOpen = { vm.openDetail(it) },
                        onFavorite = { vm.toggleFavorite(it) },
                    )
                }
            }
        }
    }
}

@Composable
private fun DetailHeader(
    detail: ChannelDetail,
    palette: CategoryPalette,
    favorite: Boolean,
    playFocus: FocusRequester,
    upModifier: Modifier,
    downModifier: Modifier,
    onPlay: () -> Unit,
    onFavorite: () -> Unit,
) {
    val card = detail.card
    Box(
        Modifier
            .fillMaxWidth()
            .height(430.dp)
            .background(Brush.verticalGradient(listOf(palette.top, palette.bottom))),
    ) {
        Box(
            Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 46.dp, top = 10.dp)
                .size(width = 420.dp, height = 260.dp),
        ) {
            ChannelArtwork(
                card = card,
                modifier = Modifier.fillMaxSize().graphicsLayer { alpha = 0.9f },
                shape = RoundedCornerShape(12.dp),
                padding = if (card.logoUrl.isNullOrBlank()) 48.dp else 20.dp,
            )
        }
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        0f to Color(0xF0141414),
                        0.45f to Color(0xB3141414),
                        0.75f to Color(0x33141414),
                        1f to Color(0x00141414),
                    ),
                ),
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color(0x00141414),
                        0.7f to Color(0x80141414),
                        1f to Color(0xFF141414),
                    ),
                ),
        )

        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth(0.62f)
                .padding(start = 30.dp, end = 24.dp, bottom = 26.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LiveBadge()
                Chip("LIVE CHANNEL", color = Color.White, background = OrbitColors.Accent)
                Chip(card.category.uppercase())
                if (card.unverified) Chip("UNVERIFIED", color = OrbitColors.Warning)
            }
            Spacer(Modifier.height(10.dp))
            Label(
                card.name,
                size = 44,
                color = Color.White,
                weight = FontWeight.ExtraBold,
                letterSpacing = (-1f),
                lines = 2,
            )
            Spacer(Modifier.height(6.dp))
            Label(
                listOfNotNull(
                    card.network.takeIf { it.isNotBlank() },
                    card.yearLaunched().takeIf { it.isNotBlank() },
                    card.language,
                    card.country,
                    "CH ${card.number}",
                ).joinToString("  \u00b7  "),
                size = 12,
                color = Color(0xFFD2D2D2),
            )
            Spacer(Modifier.height(8.dp))
            Label(
                channelBlurb(card, detail),
                size = 12,
                color = Color(0xFFD2D2D2),
                lines = 3,
            )
            Spacer(Modifier.height(16.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                HeroButton(
                    "Play",
                    onClick = onPlay,
                    focusRequester = playFocus,
                    modifier = upModifier.then(downModifier),
                )
                HeroButton(
                    if (favorite) "\u2713  My List" else "+  My List",
                    onClick = onFavorite,
                    primary = false,
                    modifier = upModifier.then(downModifier),
                )
            }
            Spacer(Modifier.height(10.dp))
            Label(
                "\u2190 Back to " + if (detail.streams.size > 1) "browse  \u00b7  ${detail.streams.size} sources" else "browse",
                size = 10,
                color = OrbitColors.Muted,
            )
        }
    }
}

@Composable
private fun AboutSection(detail: ChannelDetail, modifier: Modifier = Modifier) {
    val card = detail.card
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(8.dp)
    Column(
        modifier
            .fillMaxWidth()
            .orbitFocusSound()
            .onFocusChanged { focused = it.isFocused }
            .background(if (focused) OrbitColors.Surface else Color.Transparent, shape)
            .border(if (focused) 1.dp else 0.dp, OrbitColors.Accent, shape)
            .padding(horizontal = 30.dp, vertical = 18.dp),
    ) {
        Label("About ${card.name}", size = 18, weight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(40.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                InfoRow("Language", card.language)
                InfoRow("Category", card.category)
                InfoRow("Country", card.country)
                InfoRow("Channel number", "CH ${card.number}")
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                InfoRow("Network", card.network.ifBlank { "Not listed" })
                InfoRow("Launched", card.launched.ifBlank { "Not listed" })
                InfoRow("Owners", detail.owners.ifBlank { "Not listed" })
                InfoRow("Sources", "${detail.streams.size} stream${if (detail.streams.size == 1) "" else "s"} on Orbit")
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                InfoRow("Website", detail.website.ifBlank { "Not listed" })
                InfoRow("Status", if (card.playable) "Available" else "Offline")
                InfoRow("Quality", "Adaptive up to source")
            }
        }
        Spacer(Modifier.height(14.dp))
        Label(
            "Orbit does not host or re-stream this channel. Playback links come from public sources and may stop working without notice.",
            size = 10,
            color = OrbitColors.Muted,
            lines = 2,
        )
        Spacer(Modifier.height(10.dp))
        Label(
            "\u2193 More like this  \u00b7  \u2191 Play  \u00b7  Back to browse",
            size = 9,
            color = if (focused) OrbitColors.Accent else OrbitColors.Muted,
            weight = FontWeight.Medium,
        )
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Label(label, size = 11, color = OrbitColors.Muted, modifier = Modifier.width(110.dp))
        Label(value, size = 11, color = OrbitColors.Text, weight = FontWeight.Medium, lines = 2)
    }
}

@Composable
private fun SimilarSection(
    items: List<ChannelCard>,
    favorites: Set<String>,
    requesters: MutableMap<String, FocusRequester>,
    onOpen: (ChannelCard) -> Unit,
    onFavorite: (ChannelCard) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 30.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Label("More like this", size = 20, weight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Label("${items.size} similar channels", size = 10, color = OrbitColors.Muted)
        }
        items.chunked(3).forEach { chunk ->
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 30.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                chunk.forEach { item ->
                    val requester = requesters.getOrPut(item.id) { FocusRequester() }
                    OrbitCard(
                        card = item,
                        favorite = favorites.contains(item.id),
                        modifier = Modifier.weight(1f),
                        focusRequester = requester,
                        onClick = { onOpen(item) },
                        onLongClick = { onFavorite(item) },
                        onInfo = { onOpen(item) },
                    )
                }
                repeat(3 - chunk.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

private fun channelBlurb(card: ChannelCard, detail: ChannelDetail): String {
    val language = card.language.takeIf { it.isNotBlank() }?.let { " $it" } ?: ""
    val kind = card.category.takeIf { it.isNotBlank() }?.let { " ${it.lowercase()}" } ?: ""
    val country = card.country.takeIf { it.isNotBlank() }?.let { " from $it" } ?: ""
    val network = card.network.takeIf { it.isNotBlank() }?.let { ", operated by $it" } ?: ""
    val year = card.yearLaunched().takeIf { it.isNotBlank() }?.let { " Launched in $it." } ?: ""
    val owners = detail.owners.takeIf { it.isNotBlank() }?.let { " Owned by $it." } ?: ""
    val sources = detail.streams.size.takeIf { it > 0 }?.let { " Streaming live with $it verified source${if (it == 1) "" else "s"}." } ?: ""
    return "${card.name} is a$language$kind channel$country$network.$year$owners$sources"
}
