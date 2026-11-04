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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.orbit.tv.MainViewModel
import app.orbit.tv.UiState
import app.orbit.tv.catalog.ChannelCard
import app.orbit.tv.catalog.HomeRow

private val KEY_ROWS = listOf("abcdefg", "hijklmn", "opqrstu", "vwxyz", "0123456789")
private val QUICK_PICKS = listOf("Malayalam", "News", "Movies", "Music", "Sports", "Religious")

@Composable
fun SearchScreen(
    ui: UiState,
    vm: MainViewModel,
    modifier: Modifier = Modifier,
) {
    val firstKey = remember { FocusRequester() }
    val navHome = remember { FocusRequester() }
    val resultStates = rememberLazyGridState()
    val resultRequesters = remember { mutableMapOf<String, FocusRequester>() }
    var jumpToResults by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { firstKey.requestFocus() }
    }
    LaunchedEffect(ui.searchResults, jumpToResults) {
        if (jumpToResults && ui.searchResults.isNotEmpty()) {
            withFrameNanos { }
            runCatching { resultRequesters[ui.searchResults.first().id]?.requestFocus() }
            jumpToResults = false
        }
    }
    LaunchedEffect(ui.returnChannelId, ui.searchResults) {
        val target = ui.returnChannelId ?: return@LaunchedEffect
        val index = ui.searchResults.indexOfFirst { it.id == target }
        if (index >= 0) {
            withFrameNanos { }
            resultStates.scrollToItem(index)
            withFrameNanos { }
            runCatching { resultRequesters[target]?.requestFocus() }
        }
        vm.consumeReturnTarget()
    }

    Column(modifier = modifier.fillMaxSize().background(OrbitColors.Background)) {
        OrbitNavBar(
            selected = "",
            solid = true,
            onHome = vm::closeSearch,
            onBrowse = vm::openBrowse,
            onSearch = {},
            onSettings = vm::openSettings,
            homeFocus = navHome,
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 48.dp, end = 32.dp, top = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Label("Search", size = 22, color = OrbitColors.Text, weight = FontWeight.ExtraBold)
            Spacer(Modifier.width(20.dp))
            Box(
                Modifier
                    .weight(1f)
                    .height(42.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(OrbitColors.Surface)
                    .border(1.dp, OrbitColors.Line, RoundedCornerShape(6.dp))
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Label(
                    if (ui.searchQuery.isEmpty()) "Type a channel, language or category" else ui.searchQuery,
                    size = 16,
                    color = if (ui.searchQuery.isEmpty()) OrbitColors.Muted else OrbitColors.Text,
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 48.dp, end = 32.dp, top = 14.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(26.dp),
        ) {
            Column(Modifier.width(358.dp)) {
                KEY_ROWS.forEachIndexed { rowIndex, keys ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        keys.forEachIndexed { index, ch ->
                            OrbitButton(
                                ch.toString(),
                                onClick = { vm.onQueryChanged((ui.searchQuery + ch).take(24)) },
                                modifier = Modifier.width(46.dp),
                                focusRequester = if (rowIndex == 0 && index == 0) firstKey else null,
                                contentPadding = PaddingValues(horizontal = 2.dp, vertical = 9.dp),
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OrbitButton(
                        "space",
                        onClick = { if (ui.searchQuery.isNotEmpty()) vm.onQueryChanged(ui.searchQuery + " ") },
                        modifier = Modifier.weight(1f),
                    )
                    OrbitButton(
                        "\u232b",
                        onClick = { vm.onQueryChanged(ui.searchQuery.dropLast(1)) },
                        modifier = Modifier.width(52.dp),
                    )
                    OrbitButton(
                        "clear",
                        onClick = { vm.onQueryChanged("") },
                        modifier = Modifier.width(80.dp),
                    )
                }

                Spacer(Modifier.height(16.dp))
                Label("QUICK PICKS", size = 10, color = OrbitColors.Accent, weight = FontWeight.Bold, letterSpacing = 2f)
                Spacer(Modifier.height(6.dp))
                QUICK_PICKS.chunked(3).forEach { chunk ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        chunk.forEach { term ->
                            OrbitButton(
                                term,
                                onClick = {
                                    vm.onQueryChanged(term)
                                    jumpToResults = true
                                },
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                }
                Label(
                    "Back returns home \u00b7 search understands small typos",
                    size = 10,
                    color = OrbitColors.Muted,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            Column(Modifier.weight(1f)) {
                Label(
                    when {
                        ui.searching -> "Searching\u2026"
                        ui.searchQuery.isBlank() -> "${ui.counts?.playable ?: 0} playable channels"
                        ui.searchResults.isEmpty() -> "No channels match \u201c${ui.searchQuery.trim()}\u201d"
                        else -> "${ui.searchResults.size} results"
                    },
                    size = 11,
                    color = OrbitColors.Muted,
                )
                Spacer(Modifier.height(8.dp))
                if (ui.searchQuery.isBlank()) {
                    TrendingPane(
                        rows = ui.rows,
                        favorites = ui.favorites,
                        playable = ui.counts?.playable ?: 0,
                        onPlay = vm::playQuick,
                        onFavourite = vm::toggleFavorite,
                    )
                } else {
                    LazyVerticalGrid(
                        state = resultStates,
                        columns = GridCells.Fixed(2),
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        contentPadding = PaddingValues(bottom = 10.dp),
                    ) {
                        gridItemsIndexed(ui.searchResults, key = { _, card -> card.id }) { _, card ->
                            OrbitCard(
                                card = card,
                                favorite = ui.favorites.contains(card.id),
                                modifier = Modifier.fillMaxWidth(),
                                focusRequester = resultRequesters.getOrPut(card.id) { FocusRequester() },
                                onClick = { vm.playSearchResult(card) },
                                onLongClick = { vm.toggleFavorite(card) },
                                onInfo = { vm.openDetail(card) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TrendingPane(
    rows: List<HomeRow>,
    favorites: Set<String>,
    playable: Int,
    onPlay: (ChannelCard) -> Unit,
    onFavourite: (ChannelCard) -> Unit,
) {
    val trending = rows.firstOrNull { it.id.startsWith("col:") }?.items?.take(6).orEmpty()
        .ifEmpty { rows.firstOrNull()?.items?.take(6).orEmpty() }
    Column(Modifier.fillMaxSize()) {
        Label("Trending now", size = 22, weight = FontWeight.Bold)
        Spacer(Modifier.height(3.dp))
        Label(
            if (trending.isEmpty()) "$playable channels ready to play"
            else "Popular on Orbit \u00b7 $playable playable channels",
            size = 11,
            color = OrbitColors.Muted,
        )
        Spacer(Modifier.height(12.dp))
        if (trending.isEmpty()) {
            Label(
                "Type a few letters on the left, or pick a quick category.",
                size = 12,
                color = OrbitColors.Muted,
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(bottom = 12.dp),
            ) {
                items(trending.chunked(2), key = { pair -> pair.joinToString("|") { it.id } }) { pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        pair.forEach { card ->
                            OrbitCard(
                                card = card,
                                favorite = favorites.contains(card.id),
                                modifier = Modifier.weight(1f),
                                onClick = { onPlay(card) },
                                onLongClick = { onFavourite(card) },
                            )
                        }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}
