package app.orbit.tv.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
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

@Composable
fun BrowseScreen(
    ui: UiState,
    vm: MainViewModel,
    modifier: Modifier = Modifier,
) {
    val gridState = rememberLazyGridState()
    val chipsFocus = remember { FocusRequester() }
    val navHome = remember { FocusRequester() }
    val requesters = remember { mutableStateMapOf<String, FocusRequester>() }
    var initialFocusDone by remember { mutableStateOf(false) }

    LaunchedEffect(ui.browseItems, ui.browseLoading) {
        if (initialFocusDone) return@LaunchedEffect
        if (ui.browseItems.isEmpty() && !ui.browseLoading) {
            runCatching { chipsFocus.requestFocus() }
            initialFocusDone = true
            return@LaunchedEffect
        }
        val first = ui.browseItems.firstOrNull() ?: return@LaunchedEffect
        repeat(5) {
            withFrameNanos { }
            val requester = requesters[first.id]
            if (requester != null) {
                runCatching { requester.requestFocus() }
                initialFocusDone = true
                return@LaunchedEffect
            }
        }
    }

    LaunchedEffect(ui.returnChannelId, ui.browseItems) {
        val target = ui.returnChannelId ?: return@LaunchedEffect
        val index = ui.browseItems.indexOfFirst { it.id == target }
        if (index >= 0) {
            gridState.scrollToItem(index)
            withFrameNanos { }
            requesters[target]?.let { runCatching { it.requestFocus() } }
        }
        vm.consumeReturnTarget()
    }

    Column(modifier = modifier.fillMaxSize().background(OrbitColors.Background)) {
        OrbitNavBar(
            selected = ui.browseKey,
            solid = true,
            onHome = { vm.closeBrowse() },
            onBrowse = vm::openBrowse,
            onSearch = vm::openSearch,
            onSettings = vm::openSettings,
            homeFocus = navHome,
            modifier = Modifier.onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown) {
                    runCatching { chipsFocus.requestFocus() }
                    true
                } else {
                    false
                }
            },
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 30.dp, end = 30.dp, top = 18.dp, bottom = 12.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Column(Modifier.weight(1f)) {
                Label(ui.browseTitle, size = 32, weight = FontWeight.ExtraBold, letterSpacing = (-0.8f))
                Spacer(Modifier.height(4.dp))
                Label(
                    if (ui.browseLoading) "Loading channels\u2026"
                    else "${ui.browseSubtitle} \u00b7 ${ui.browseItems.size} channels",
                    size = 11,
                    color = OrbitColors.Muted,
                )
            }
        }
        BrowseChips(
            selected = ui.browseKey,
            onSelect = vm::openBrowse,
            firstFocus = chipsFocus,
        )
        Spacer(Modifier.height(10.dp))
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                ui.browseLoading && ui.browseItems.isEmpty() -> {
                    Label(
                        "Loading channels\u2026",
                        size = 13,
                        color = OrbitColors.Muted,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
                ui.browseItems.isEmpty() -> {
                    EmptyBrowse(ui.browseKey, Modifier.align(Alignment.Center))
                }
                else -> {
                    LazyVerticalGrid(
                        state = gridState,
                        columns = GridCells.Adaptive(190.dp),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 30.dp, end = 30.dp, bottom = 36.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        itemsIndexed(ui.browseItems, key = { _, card -> card.id }) { _, card ->
                            val requester = requesters.getOrPut(card.id) { FocusRequester() }
                            OrbitCard(
                                card = card,
                                favorite = ui.favorites.contains(card.id),
                                modifier = Modifier.fillMaxWidth(),
                                focusRequester = requester,
                                onClick = { vm.playBrowse(card) },
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
private fun EmptyBrowse(key: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Label(
            when (key) {
                "favorites" -> "Your list is empty"
                "recents" -> "Nothing watched yet"
                else -> "No channels here"
            },
            size = 20,
            weight = FontWeight.Bold,
        )
        Spacer(Modifier.height(6.dp))
        Label(
            when (key) {
                "favorites" -> "Hold OK on any channel to add it to My List."
                "recents" -> "Play a channel and it will appear here."
                else -> "Try another category."
            },
            size = 12,
            color = OrbitColors.Muted,
        )
    }
}
