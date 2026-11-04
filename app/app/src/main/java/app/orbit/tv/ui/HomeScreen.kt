package app.orbit.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.orbit.tv.MainViewModel
import app.orbit.tv.UiState
import app.orbit.tv.catalog.ChannelCard
import app.orbit.tv.catalog.HomeRow
import coil.compose.SubcomposeAsyncImage
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(
    ui: UiState,
    vm: MainViewModel,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val rowStates = remember { mutableStateMapOf<String, LazyListState>() }
    val requesters = remember { mutableMapOf<String, FocusRequester>() }
    val scope = rememberCoroutineScope()
    val heroPlay = remember { FocusRequester() }
    val navHome = remember { FocusRequester() }
    var initialFocusDone by remember { mutableStateOf(false) }
    val solidHeader by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 24
        }
    }

    fun goHome() {
        scope.launch {
            listState.scrollToItem(0)
            withFrameNanos { }
            runCatching { heroPlay.requestFocus() }
        }
    }

    LaunchedEffect(ui.rows, ui.returnChannelId) {
        if (ui.returnChannelId == null && !initialFocusDone && ui.rows.isNotEmpty()) {
            repeat(5) {
                withFrameNanos { }
                if (runCatching { heroPlay.requestFocus() }.isSuccess) {
                    initialFocusDone = true
                    return@LaunchedEffect
                }
            }
            initialFocusDone = true
        }
    }

    LaunchedEffect(ui.returnChannelId, ui.rows) {
        val target = ui.returnChannelId ?: return@LaunchedEffect
        val rowIndex = ui.rows.indexOfFirst { row -> row.items.any { it.id == target } }
        if (rowIndex >= 0) {
            val row = ui.rows[rowIndex]
            val itemIndex = row.items.indexOfFirst { it.id == target }
            listState.scrollToItem(rowIndex + 1)
            withFrameNanos { }
            rowStates[row.id]?.scrollToItem(itemIndex.coerceAtLeast(0))
            withFrameNanos { }
            requesters[row.id + "|" + target]?.let { runCatching { it.requestFocus() } }
        }
        vm.consumeReturnTarget()
    }

    val heroRank = ui.rows.firstOrNull { it.id == "top10" }
        ?.items
        ?.indexOfFirst { it.id == ui.hero?.id }
        ?.takeIf { it >= 0 }
        ?.plus(1)

    Box(modifier = modifier.fillMaxSize().background(OrbitColors.Background)) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 30.dp),
        ) {
            item(key = "hero") {
                Hero(
                    card = ui.hero,
                    favourite = ui.hero?.let { ui.favorites.contains(it.id) } == true,
                    onPlay = vm::playHero,
                    onFavourite = { ui.hero?.let(vm::toggleFavorite) },
                    onMore = { ui.hero?.let(vm::openDetail) },
                    onFocusHero = { scope.launch { listState.scrollToItem(0) } },
                    onUpEdge = { runCatching { navHome.requestFocus() } },
                    playFocus = heroPlay,
                    rank = heroRank,
                )
            }
            items(ui.rows, key = { it.id }) { row ->
                HomeRowView(
                    row = row,
                    favorites = ui.favorites,
                    rowStates = rowStates,
                    requesters = requesters,
                    onPlay = { card -> vm.playFromRow(row.id, card) },
                    onFavourite = vm::toggleFavorite,
                    onInfo = vm::openDetail,
                )
            }
            item(key = "footer") {
                Footer(ui = ui, onSettings = vm::openSettings)
            }
        }

        OrbitNavBar(
            selected = "home",
            solid = solidHeader,
            onHome = ::goHome,
            onBrowse = vm::openBrowse,
            onSearch = vm::openSearch,
            onSettings = vm::openSettings,
            homeFocus = navHome,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown) {
                        runCatching { heroPlay.requestFocus() }
                        true
                    } else {
                        false
                    }
                },
        )
    }
}

@Composable
private fun Hero(
    card: ChannelCard?,
    favourite: Boolean,
    onPlay: () -> Unit,
    onFavourite: () -> Unit,
    onMore: () -> Unit,
    onFocusHero: () -> Unit,
    onUpEdge: () -> Unit,
    playFocus: FocusRequester,
    rank: Int? = null,
) {
    val palette = categoryPalette(card?.category.orEmpty())
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(468.dp)
            .background(
                Brush.radialGradient(
                    listOf(palette.glow.copy(alpha = 0.34f), Color(0x00141414)),
                    center = androidx.compose.ui.geometry.Offset(780f, 210f),
                    radius = 720f,
                ),
            )
            .background(OrbitColors.Background),
    ) {
        if (card != null) {
            HeroLogo(
                card = card,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 58.dp, top = 34.dp)
                    .width(420.dp)
                    .height(300.dp),
            )
        }
        Box(
            Modifier
                .matchParentSize()
                .background(
                    Brush.horizontalGradient(
                        listOf(
                            OrbitColors.Background,
                            Color(0xE6141414),
                            Color(0x33141414),
                            Color(0x00141414),
                        ),
                    ),
                ),
        )
        Box(
            Modifier
                .matchParentSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color(0x99141414),
                        0.42f to Color(0x00141414),
                        0.8f to Color(0x66141414),
                        1f to OrbitColors.Background,
                    ),
                ),
        )
        Column(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .width(580.dp)
                .padding(start = 38.dp, top = 84.dp, bottom = 30.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Label("O", size = 20, color = OrbitColors.Accent, weight = FontWeight.ExtraBold)
                Label(
                    "LIVE CHANNEL",
                    size = 11,
                    color = Color.White.copy(alpha = 0.72f),
                    weight = FontWeight.Bold,
                    letterSpacing = 3f,
                )
                if (favourite) Label("\u2605", size = 13, color = OrbitColors.Gold)
            }
            Label(
                card?.name ?: "Live TV, sorted",
                size = 56,
                color = Color.White,
                weight = FontWeight.ExtraBold,
                letterSpacing = (-2f),
                lines = 2,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .background(OrbitColors.Accent)
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Label("TOP", size = 7, color = Color.White, weight = FontWeight.ExtraBold, letterSpacing = 1f)
                        Label("10", size = 13, color = Color.White, weight = FontWeight.ExtraBold)
                    }
                }
                Spacer(Modifier.width(10.dp))
                Label(
                    if (rank != null) "#$rank in India Today" else "Featured live channel",
                    size = 18,
                    color = Color.White,
                    weight = FontWeight.Bold,
                )
            }
            Label(
                card?.let {
                    listOfNotNull(
                        it.language.takeIf { v -> v.isNotBlank() },
                        it.category.takeIf { v -> v.isNotBlank() },
                        it.country.takeIf { v -> v.isNotBlank() },
                        "CH ${it.number}",
                    ).joinToString("  \u00b7  ")
                } ?: "Curated channels from around the world",
                size = 12,
                color = Color(0xFFD2D2D2),
                weight = FontWeight.Medium,
            )
            Label(
                if (card != null) heroBlurb(card) else "Curated live channels, checked before they are listed.",
                size = 14,
                color = Color.White,
                lines = 3,
            )
            Row(
                modifier = Modifier
                    .padding(top = 6.dp)
                    .onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionUp) {
                            onUpEdge()
                            true
                        } else {
                            false
                        }
                    },
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                HeroButton(
                    "\u25b6  Play",
                    onClick = onPlay,
                    onLongClick = onFavourite,
                    focusRequester = playFocus,
                    modifier = Modifier.onFocusChanged { if (it.isFocused) onFocusHero() },
                )
                HeroButton(
                    "\u24d8  More Info",
                    onClick = onMore,
                    primary = false,
                    modifier = Modifier.onFocusChanged { if (it.isFocused) onFocusHero() },
                )
            }
        }
    }
}

@Composable
private fun HeroLogo(card: ChannelCard, modifier: Modifier = Modifier) {
    val palette = categoryPalette(card.category)
    val shape = RoundedCornerShape(16.dp)
    // JPEG logos carry their own solid background, so they get the artwork-card
    // treatment; transparent PNGs can float as a reference-style watermark.
    val raster = card.logoUrl?.substringBefore('?')?.lowercase()
        ?.let { it.endsWith(".jpg") || it.endsWith(".jpeg") } == true
    if (raster) {
        Box(modifier.clip(shape).border(1.dp, Color(0x14FFFFFF), shape)) {
            ChannelArtwork(card, Modifier.fillMaxSize(), shape, padding = 0.dp)
        }
        return
    }
    Box(modifier.alpha(0.88f), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .matchParentSize()
                .background(
                    Brush.radialGradient(
                        listOf(palette.glow.copy(alpha = 0.42f), Color.Transparent),
                    ),
                ),
        )
        SubcomposeAsyncImage(
            model = card.logoUrl,
            contentDescription = card.name,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize().padding(16.dp),
            loading = { HeroMonogram(card) },
            error = { HeroMonogram(card) },
        )
    }
}

@Composable
private fun HeroMonogram(card: ChannelCard) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Label(
            monogram(card.name),
            size = 96,
            color = Color.White.copy(alpha = 0.18f),
            weight = FontWeight.ExtraBold,
            letterSpacing = 4f,
        )
    }
}

private fun heroBlurb(card: ChannelCard): String {
    val launched = card.yearLaunched()
    val since = if (launched.isNotBlank()) ", on air since $launched" else ""
    val network = if (card.network.isNotBlank()) "${card.network} \u00b7 " else ""
    return "$network live ${card.language} ${card.category.lowercase()} from " +
        "${card.country.ifBlank { "around the world" }}$since. One tap to tune in, with automatic stream fallback."
}

@Composable
private fun HomeRowView(
    row: HomeRow,
    favorites: Set<String>,
    rowStates: MutableMap<String, LazyListState>,
    requesters: MutableMap<String, FocusRequester>,
    onPlay: (ChannelCard) -> Unit,
    onFavourite: (ChannelCard) -> Unit,
    onInfo: (ChannelCard) -> Unit,
) {
    val state = rememberLazyListState()
    SideEffect { rowStates[row.id] = state }
    Column(modifier = Modifier.padding(top = 20.dp)) {
        Row(
            modifier = Modifier.padding(start = 30.dp, end = 30.dp, bottom = 6.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Label(row.title, size = 20, weight = FontWeight.Bold)
            Spacer(Modifier.width(10.dp))
            Label(
                if (row.id == "top10") "Ranked on Orbit" else "${row.items.size} channels",
                size = 10,
                color = OrbitColors.Muted,
            )
        }
        LazyRow(
            state = state,
            contentPadding = PaddingValues(horizontal = 30.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(if (row.id == "top10") 4.dp else 12.dp),
        ) {
            itemsIndexed(row.items, key = { _, card -> card.id }) { index, card ->
                val key = row.id + "|" + card.id
                val requester = requesters.getOrPut(key) { FocusRequester() }
                if (row.id == "top10") {
                    Top10Card(
                        card = card,
                        rank = index + 1,
                        favorite = favorites.contains(card.id),
                        focusRequester = requester,
                        onClick = { onPlay(card) },
                        onLongClick = { onFavourite(card) },
                        onInfo = { onInfo(card) },
                    )
                } else {
                    OrbitCard(
                        card = card,
                        favorite = favorites.contains(card.id),
                        focusRequester = requester,
                        width = 220.dp,
                        onClick = { onPlay(card) },
                        onLongClick = { onFavourite(card) },
                        onInfo = { onInfo(card) },
                    )
                }
            }
        }
    }
}

@Composable
private fun Footer(ui: UiState, onSettings: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(start = 30.dp, end = 30.dp, top = 26.dp, bottom = 10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
            listOf("Audio and Subtitles", "Help Centre", "Playlist Sources", "Parental Controls", "Terms of Use", "Privacy")
                .forEach { Label(it, size = 10, color = Color(0xFF808080)) }
            Spacer(Modifier.weight(1f))
            OrbitButton(
                "About & credits",
                onClick = onSettings,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 7.dp),
            )
            Label(
                "Catalog ${ui.catalogVersion.ifBlank { "not installed" }}",
                size = 9,
                color = OrbitColors.Muted,
            )
        }
        Spacer(Modifier.height(12.dp))
        Label(
            "Orbit is a free, open-source live TV browser. Channels are compiled from public IPTV catalogs and checked before they are listed. " +
                "Orbit does not host, re-stream or sell any channel. Channel names and logos belong to their owners.",
            size = 10,
            color = Color(0xFF808080),
            lines = 2,
        )
        Label("No account \u00b7 No ads \u00b7 No background services", size = 10, color = Color(0xFF808080))
    }
}
