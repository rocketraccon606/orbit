package app.orbit.tv

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.orbit.tv.catalog.CatalogDao
import app.orbit.tv.catalog.CatalogState
import app.orbit.tv.catalog.ChannelCard
import app.orbit.tv.catalog.ChannelDetail
import app.orbit.tv.catalog.CatalogCounts
import app.orbit.tv.catalog.HomeRow
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface Screen {
    data object Home : Screen
    data object Search : Screen
    data object Settings : Screen
    data object Browse : Screen
    data object Detail : Screen
    data object Player : Screen
}

data class Playlist(val key: String, val items: List<ChannelCard>)

data class BrowseFilter(val key: String, val title: String, val subtitle: String)

data class UiState(
    val screen: Screen = Screen.Home,
    val rows: List<HomeRow> = emptyList(),
    val hero: ChannelCard? = null,
    val favorites: Set<String> = emptySet(),
    val loadingHome: Boolean = true,
    val message: String? = null,
    val returnChannelId: String? = null,
    val quickPanel: Boolean = false,
    val quickItems: List<ChannelCard> = emptyList(),
    val searchQuery: String = "",
    val searchResults: List<ChannelCard> = emptyList(),
    val searching: Boolean = false,
    val counts: CatalogCounts? = null,
    val catalogVersion: String = "",
    val catalogSchema: Int = 0,
    val playlistKey: String = "",
    val returnScreen: Screen = Screen.Home,
    val browseTitle: String = "Channels",
    val browseSubtitle: String = "",
    val browseKey: String = "all",
    val browseItems: List<ChannelCard> = emptyList(),
    val browseLoading: Boolean = false,
    val detail: ChannelDetail? = null,
    val detailSimilar: List<ChannelCard> = emptyList(),
    val detailLoading: Boolean = false,
    val detailBack: Screen = Screen.Home,
)

class MainViewModel(private val container: AppContainer) : ViewModel() {

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui

    val catalogState = container.catalog.state
    val updateState = container.catalog.update
    val playback = container.playback

    private var dao: CatalogDao? = null
    private var loadedVersion: String? = null
    private var searchJob: Job? = null
    private val playToken = AtomicInteger(0)

    private var playlist: Playlist? = null
    private var currentCard: ChannelCard? = null
    private var lastCard: ChannelCard? = null
    private var allChannels: Playlist? = null

    init {
        viewModelScope.launch {
            container.catalog.state.collect { state ->
                when (state) {
                    is CatalogState.Ready -> {
                        dao = state.dao
                        if (loadedVersion != state.info.version) {
                            loadedVersion = state.info.version
                            allChannels = null
                            _ui.update { it.copy(counts = state.info.counts, catalogVersion = state.info.version, catalogSchema = state.info.schema) }
                            reloadHome()
                        }
                    }
                    is CatalogState.Error -> _ui.update {
                        it.copy(loadingHome = false, message = state.message)
                    }
                    CatalogState.Missing -> Unit
                }
            }
        }
        viewModelScope.launch { container.catalog.start() }
    }

    fun reloadHome() {
        val db = dao ?: return
        viewModelScope.launch {
            _ui.update { it.copy(loadingHome = it.rows.isEmpty()) }
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val favourites = container.users.favoriteIds()
                    val rows = buildList {
                        val recents = db.channelsInOrder(container.users.recents(20))
                            .filter { it.playable }
                        if (recents.isNotEmpty()) add(HomeRow("recents", "Continue watching", recents))
                        val favs = db.channelsInOrder(container.users.favorites(20))
                            .filter { it.playable }
                        if (favs.isNotEmpty()) add(HomeRow("favorites", "My List", favs))
                        val picks = db.collectionItems("editor-picks", 10).filter { it.playable }
                        if (picks.isNotEmpty()) {
                            add(HomeRow("top10", "Top 10 channels today", picks))
                        }
                        val curated = listOf(
                            "malayalam" to "Malayalam channels",
                            "india-news" to "India news",
                            "india-entertainment" to "Indian entertainment",
                            "india-movies" to "Indian movies",
                            "india-sports" to "Live sports",
                            "world-news" to "World news",
                            "world-entertainment" to "World entertainment",
                            "india-music" to "Music",
                        )
                        for ((id, title) in curated) {
                            val items = db.collectionItems(id, 40).filter { it.playable }
                            if (items.isNotEmpty()) add(HomeRow("col:$id", title, items))
                        }
                    }
                    rows to favourites
                }
            }
            result.onSuccess { (rows, favourites) ->
                _ui.update {
                    it.copy(
                        rows = rows,
                        hero = rows.firstOrNull { r -> r.id.startsWith("col:") }
                            ?.items?.firstOrNull(),
                        favorites = favourites,
                        loadingHome = false,
                    )
                }
            }.onFailure { e ->
                _ui.update { it.copy(loadingHome = false, message = e.message ?: "Could not read catalog") }
            }
        }
    }

    fun toggleFavorite(card: ChannelCard) {
        viewModelScope.launch {
            val nowFavorite = withContext(Dispatchers.IO) {
                container.users.toggleFavorite(card.id)
            }
            _ui.update {
                it.copy(
                    favorites = if (nowFavorite) it.favorites + card.id else it.favorites - card.id,
                    message = if (nowFavorite) "\u2605 ${card.name} added to My channels"
                    else "${card.name} removed from My channels",
                )
            }
            reloadHome()
            if (_ui.value.screen == Screen.Browse && _ui.value.browseKey == "favorites") reloadBrowse()
        }
    }
    fun openSearch() {
        _ui.update { it.copy(screen = Screen.Search) }
    }

    fun closeSearch() {
        searchJob?.cancel()
        _ui.update { it.copy(screen = Screen.Home, searchQuery = "", searchResults = emptyList(), searching = false) }
    }

    fun onQueryChanged(query: String) {
        _ui.update { it.copy(searchQuery = query) }
        searchJob?.cancel()
        val db = dao
        if (query.isBlank() || db == null) {
            _ui.update { it.copy(searchResults = emptyList(), searching = false) }
            return
        }
        _ui.update { it.copy(searching = true) }
        searchJob = viewModelScope.launch {
            delay(180)
            val results = withContext(Dispatchers.IO) { runCatching { db.search(query, 60) }.getOrDefault(emptyList()) }
            _ui.update { it.copy(searchResults = results, searching = false) }
        }
    }

    fun openSettings() {
        _ui.update { it.copy(screen = Screen.Settings) }
    }

    fun closeSettings() {
        _ui.update { it.copy(screen = Screen.Home) }
    }

    fun openBrowse(key: String) {
        val filter = when (key) {
            "malayalam" -> BrowseFilter("malayalam", "Malayalam", "Channels from Kerala")
            "news" -> BrowseFilter("news", "News", "Live news channels")
            "sports" -> BrowseFilter("sports", "Sports", "Matches and sports news")
            "entertainment" -> BrowseFilter("entertainment", "Entertainment", "Serials, reality and more")
            "movies" -> BrowseFilter("movies", "Movies", "Movie channels")
            "music" -> BrowseFilter("music", "Music", "Music channels")
            "favorites" -> BrowseFilter("favorites", "My List", "Saved channels")
            "recents" -> BrowseFilter("recents", "Recently Watched", "Your last 20 channels")
            else -> BrowseFilter("all", "Channels", "Every playable channel")
        }
        _ui.update {
            it.copy(
                screen = Screen.Browse,
                browseKey = filter.key,
                browseTitle = filter.title,
                browseSubtitle = filter.subtitle,
                browseItems = emptyList(),
                browseLoading = true,
            )
        }
        reloadBrowse()
    }

    fun closeBrowse() {
        _ui.update { it.copy(screen = Screen.Home) }
    }

    private fun reloadBrowse() {
        val db = dao ?: return
        val key = _ui.value.browseKey
        viewModelScope.launch {
            val items = withContext(Dispatchers.IO) {
                runCatching {
                    when (key) {
                        "malayalam" -> db.collectionItems("malayalam", 120)
                        "news" -> db.channelsByCategory("News", 300)
                        "sports" -> db.channelsByCategory("Sports", 120)
                        "entertainment" -> db.channelsByCategory("Entertainment", 300)
                        "movies" -> db.channelsByCategory("Movies", 120)
                        "music" -> db.channelsByCategory("Music", 120)
                        "favorites" -> db.channelsInOrder(container.users.favorites(200))
                        "recents" -> db.channelsInOrder(container.users.recents(20))
                        else -> db.allPlayable()
                    }.filter { it.playable }
                }.getOrDefault(emptyList())
            }
            _ui.update {
                if (it.browseKey == key) it.copy(browseItems = items, browseLoading = false) else it
            }
        }
    }

    fun openDetail(card: ChannelCard) {
        val current = _ui.value
        val back = if (current.screen == Screen.Detail) current.detailBack else current.screen
        _ui.update {
            it.copy(
                screen = Screen.Detail,
                detail = ChannelDetail(card, emptyList()),
                detailSimilar = emptyList(),
                detailLoading = true,
                detailBack = back,
            )
        }
        val db = dao ?: return
        viewModelScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                runCatching {
                    val detail = db.channel(card.id) ?: ChannelDetail(card, emptyList())
                    detail to db.similar(card, 8)
                }.getOrElse { ChannelDetail(card, emptyList()) to emptyList() }
            }
            _ui.update {
                if (it.screen == Screen.Detail && it.detail?.card?.id == card.id) {
                    it.copy(detail = loaded.first, detailSimilar = loaded.second, detailLoading = false)
                } else {
                    it
                }
            }
        }
    }

    fun closeDetail() {
        val back = _ui.value.detailBack
        _ui.update { it.copy(screen = back, detail = null, detailSimilar = emptyList()) }
        if (back == Screen.Browse && _ui.value.browseKey == "favorites") reloadBrowse()
    }

    fun playBrowse(card: ChannelCard) {
        val items = _ui.value.browseItems.takeIf { list -> list.any { it.id == card.id } } ?: listOf(card)
        playInternal(card, Playlist("browse", items), origin = Screen.Browse)
    }

    fun playDetail() {
        val detail = _ui.value.detail ?: return
        val items = (listOf(detail.card) + _ui.value.detailSimilar).distinctBy { it.id }
        playInternal(detail.card, Playlist("detail", items), origin = Screen.Detail)
    }

    fun playFromRow(rowId: String, card: ChannelCard) {
        val row = _ui.value.rows.firstOrNull { it.id == rowId }
        val items = row?.items?.takeIf { it.isNotEmpty() } ?: listOf(card)
        playInternal(card, Playlist(rowId, items), origin = Screen.Home)
    }

    fun playSearchResult(card: ChannelCard) {
        playInternal(
            card,
            Playlist("search", _ui.value.searchResults.ifEmpty { listOf(card) }),
            origin = Screen.Search,
        )
    }

    fun playQuick(card: ChannelCard) {
        val items = _ui.value.quickItems.ifEmpty { listOf(card) }
        playInternal(card, Playlist("quick", items))
    }

    fun playHero() {
        val hero = _ui.value.hero ?: return
        val row = _ui.value.rows.firstOrNull { r -> r.items.any { it.id == hero.id } }
        playFromRow(row?.id ?: "hero", hero)
    }

    fun checkForUpdates() {
        viewModelScope.launch { container.catalog.retry() }
    }

    fun clearFavorites() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { container.users.clearFavorites() }
            _ui.update { it.copy(favorites = emptySet(), message = "Favourites cleared") }
            reloadHome()
        }
    }

    fun clearRecents() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { container.users.clearRecents() }
            _ui.update { it.copy(message = "Watch history cleared") }
            reloadHome()
        }
    }

    private fun playInternal(card: ChannelCard, list: Playlist, origin: Screen? = null) {
        val startedAt = System.nanoTime()
        val token = playToken.incrementAndGet()
        lastCard = currentCard
        currentCard = card
        playlist = list
        _ui.update {
            it.copy(
                screen = Screen.Player,
                quickPanel = false,
                message = null,
                playlistKey = list.key,
                returnScreen = origin ?: it.returnScreen,
            )
        }
        viewModelScope.launch {
            val db = dao
            val detail = withContext(Dispatchers.IO) {
                runCatching {
                    container.users.touchRecent(card.id)
                    container.users.incrementWatchCount(card.id)
                    db?.channel(card.id)
                }.getOrNull()
            }
            if (token != playToken.get()) return@launch
            if (detail == null) {
                _ui.update { it.copy(screen = Screen.Home, message = "Could not open ${card.name}") }
            } else {
                container.playback.play(detail, startedAt)
            }
        }
    }

    private suspend fun allPlaylist(db: CatalogDao): Playlist? {
        allChannels?.let { return it }
        val items = withContext(Dispatchers.IO) {
            runCatching { db.allPlayable() }.getOrDefault(emptyList())
        }
        if (items.isEmpty()) return null
        return Playlist("all", items).also { allChannels = it }
    }

    fun nextChannel(delta: Int) {
        val current = currentCard ?: return
        val db = dao ?: return
        viewModelScope.launch {
            // CH+/CH- always follows channel order, regardless of which row
            // the current channel was opened from.
            val all = allPlaylist(db) ?: return@launch
            val index = all.items.indexOfFirst { it.id == current.id }
            if (index < 0) return@launch
            val next = all.items[(index + delta + all.items.size) % all.items.size]
            if (next.id != current.id) playInternal(next, all)
        }
    }

    fun lastChannel() {
        val target = lastCard ?: return
        val db = dao ?: return
        viewModelScope.launch {
            val list = playlist?.takeIf { p -> p.items.any { it.id == target.id } }
                ?: allPlaylist(db)
                ?: Playlist("last", listOf(target))
            playInternal(target, list)
        }
    }

    fun jumpToNumber(number: Int) {
        val db = dao ?: return
        viewModelScope.launch {
            val card = withContext(Dispatchers.IO) {
                runCatching { db.channelByNumber(number) }.getOrNull()
            } ?: return@launch
            val list = playlist?.takeIf { p -> p.items.any { it.id == card.id } }
                ?: allPlaylist(db)
                ?: Playlist("number", listOf(card))
            playInternal(card, list)
        }
    }

    fun openQuickPanel() {
        val db = dao ?: return
        viewModelScope.launch {
            val items = withContext(Dispatchers.IO) {
                runCatching {
                    val favs = db.channelsInOrder(container.users.favorites(8)).filter { it.playable }
                    val recents = db.channelsInOrder(container.users.recents(8)).filter { it.playable }
                    (favs + recents).distinctBy { it.id }
                }.getOrDefault(emptyList())
            }
            _ui.update { it.copy(quickPanel = true, quickItems = items) }
        }
    }

    fun closeQuickPanel() {
        _ui.update { it.copy(quickPanel = false) }
    }

    fun closePlayer() {
        playToken.incrementAndGet()
        container.playback.detach()
        val returnId = currentCard?.id
        val target = _ui.value.returnScreen
        _ui.update {
            it.copy(
                screen = target,
                returnChannelId = returnId,
                quickPanel = false,
                returnScreen = Screen.Home,
            )
        }
        if (target == Screen.Home) reloadHome()
    }

    fun consumeReturnTarget() {
        _ui.update { it.copy(returnChannelId = null) }
    }

    fun clearMessage() {
        _ui.update { it.copy(message = null) }
    }

    fun showMessage(text: String) {
        _ui.update { it.copy(message = text) }
    }

    fun onForeground() {
        val state = playback.state.value
        if (_ui.value.screen == Screen.Player && currentCard != null &&
            state.phase == app.orbit.tv.player.PlaybackPhase.Idle
        ) {
            playInternal(currentCard!!, playlist ?: Playlist("last", listOf(currentCard!!)))
        }
    }

    fun onBackground() {
        container.onBackground()
    }

    fun currentPlaylistKey(): String = _ui.value.playlistKey

    override fun onCleared() {
        searchJob?.cancel()
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { MainViewModel(container) }
        }
    }
}
