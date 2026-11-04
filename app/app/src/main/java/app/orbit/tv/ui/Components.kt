package app.orbit.tv.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.orbit.tv.catalog.ChannelCard
import coil.compose.SubcomposeAsyncImage
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val LONG_PRESS_MS = 550L

/**
 * Click plus D-pad long press. combinedClickable only understands pointer
 * gestures, so a held OK on a remote never reaches onLongClick without this.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Modifier.tvItemClickable(
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
): Modifier {
    val scope = rememberCoroutineScope()
    var holdJob by remember { mutableStateOf<Job?>(null) }
    var longFired by remember { mutableStateOf(false) }
    return this
        .onFocusChanged {
            if (!it.isFocused) {
                holdJob?.cancel()
                holdJob = null
                longFired = false
            }
        }
        .onPreviewKeyEvent { event ->
            if (event.key != Key.DirectionCenter && event.key != Key.Enter) {
                return@onPreviewKeyEvent false
            }
            val native = event.nativeKeyEvent
            when (event.type) {
                KeyEventType.KeyDown -> {
                    val held = native.repeatCount > 0 ||
                        (native.flags and android.view.KeyEvent.FLAG_LONG_PRESS) != 0
                    if (held) {
                        holdJob?.cancel()
                        holdJob = null
                        if (!longFired) {
                            longFired = true
                            onLongClick()
                        }
                    } else if (!longFired && holdJob?.isActive != true) {
                        holdJob = scope.launch {
                            delay(LONG_PRESS_MS)
                            longFired = true
                            onLongClick()
                        }
                    }
                    true
                }
                KeyEventType.KeyUp -> {
                    val fired = longFired
                    holdJob?.cancel()
                    holdJob = null
                    longFired = false
                    if (!fired) onClick()
                    true
                }
                else -> false
            }
        }
        .combinedClickable(onClick = onClick, onLongClick = onLongClick)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChannelTile(
    card: ChannelCard,
    favorite: Boolean,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    width: Dp = 224.dp,
    showState: Boolean = false,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (focused) 1.05f else 1f,
        animationSpec = tween(120),
        label = "tile-scale",
    )
    val shape = RoundedCornerShape(10.dp)
    val focusModifier = if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier

    Box(
        modifier = modifier
            .width(width)
            .onFocusChanged { focused = it.isFocused }
            .then(focusModifier)
            .tvItemClickable(onClick = onClick, onLongClick = onLongClick)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            },
    ) {
        ChannelArtwork(
            card = card,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f),
            shape = shape,
            padding = if (isArtwork(card.logoUrl)) 0.dp else 16.dp,
        )
        Box(
            Modifier
                .matchParentSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        0.5f to Color.Transparent,
                        1f to Color(0xF209060F),
                    ),
                ),
        )
        Box(
            Modifier
                .matchParentSize()
                .border(
                    width = if (focused) 2.dp else 1.dp,
                    color = if (focused) OrbitColors.Accent else Color(0x1AFFFFFF),
                    shape = shape,
                ),
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 7.dp),
        ) {
            Label(
                card.name,
                size = 12,
                color = Color.White,
                weight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 1.dp),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (card.network.isNotBlank()) {
                    Label(card.network, size = 9, color = Color(0xFFC9C2D6), modifier = Modifier.weight(1f))
                } else {
                    Label(card.language, size = 9, color = Color(0xFFC9C2D6), modifier = Modifier.weight(1f))
                }
                Label("CH ${card.number}", size = 8, color = Color(0xFFC9C2D6), weight = FontWeight.Medium)
            }
        }
        if (favorite) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(7.dp)
                    .background(Color(0xB309060F), RoundedCornerShape(50))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            ) {
                Label("\u2605", size = 10, color = OrbitColors.Gold)
            }
        }
        if (showState && !card.playable) {
            Chip(
                "offline",
                color = OrbitColors.Error,
                background = Color(0xFF2A1516),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp),
            )
        } else if (showState && card.unverified) {
            Chip(
                "unverified",
                color = OrbitColors.Warning,
                background = Color(0xFF2A2114),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp),
            )
        }
    }
}

@Composable
fun ChannelArtwork(
    card: ChannelCard,
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = RoundedCornerShape(8.dp),
    padding: Dp = 16.dp,
) {
    val palette = categoryPalette(card.category)
    val artwork = isArtwork(card.logoUrl)
    Box(
        modifier = modifier
            .clip(shape)
            .background(Brush.verticalGradient(listOf(palette.top, palette.bottom))),
    ) {
        Box(
            Modifier
                .matchParentSize()
                .background(
                    Brush.radialGradient(
                        listOf(palette.glow.copy(alpha = 0.34f), Color.Transparent),
                    ),
                ),
        )
        Label(
            monogram(card.name),
            size = 30,
            color = Color.White.copy(alpha = 0.07f),
            weight = FontWeight.Bold,
            letterSpacing = 3f,
            modifier = Modifier.align(Alignment.Center),
        )
        SubcomposeAsyncImage(
            model = card.logoUrl,
            contentDescription = card.name,
            contentScale = if (artwork) ContentScale.Crop else ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .padding(if (artwork) 0.dp else padding),
            loading = { ArtworkFallback(card, canvas = artwork) },
            error = { ArtworkFallback(card, canvas = artwork) },
        )
    }
}

@Composable
private fun ArtworkFallback(card: ChannelCard, canvas: Boolean) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Label(
            monogram(card.name),
            size = if (canvas) 30 else 26,
            color = OrbitColors.Accent.copy(alpha = 0.85f),
            weight = FontWeight.Bold,
            letterSpacing = 2f,
        )
    }
}

private fun isArtwork(url: String?): Boolean {
    val path = url?.substringBefore('?')?.lowercase() ?: return false
    return path.endsWith(".jpg") || path.endsWith(".jpeg")
}

fun monogram(name: String): String {
    val words = name.split(' ', '-', '.').filter { it.isNotBlank() }
    return words.take(2).mapNotNull { it.firstOrNull()?.uppercaseChar() }
        .joinToString("").ifEmpty { "TV" }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun OrbitButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    focusRequester: FocusRequester? = null,
    contentPadding: PaddingValues = PaddingValues(horizontal = 18.dp, vertical = 9.dp),
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(6.dp)
    val focusModifier = if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier
    val background = when {
        focused && primary -> OrbitColors.Accent
        focused -> OrbitColors.SurfaceHigh
        primary -> OrbitColors.AccentSoft
        else -> OrbitColors.Surface
    }
    val textColor = when {
        focused && primary -> OrbitColors.Background
        primary -> OrbitColors.Accent
        focused -> OrbitColors.Text
        else -> OrbitColors.Muted
    }
    Box(
        modifier = modifier
            .onFocusChanged { focused = it.isFocused }
            .then(focusModifier)
            .clip(shape)
            .background(background)
            .border(1.dp, if (focused) OrbitColors.Accent else OrbitColors.Line, shape)
            .combinedClickable(onClick = onClick)
            .padding(contentPadding),
        contentAlignment = Alignment.Center,
    ) {
        Label(text, size = 12, color = textColor, weight = FontWeight.Medium, letterSpacing = 0.3f)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = true,
    focusRequester: FocusRequester? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(50)
    val focusModifier = if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier
    val background = when {
        primary && focused -> Color(0xFFFF6B78)
        primary -> OrbitColors.Accent
        focused -> OrbitColors.SurfaceHigh
        else -> Color(0xB315111E)
    }
    Box(
        modifier = modifier
            .onFocusChanged { focused = it.isFocused }
            .then(focusModifier)
            .clip(shape)
            .background(background)
            .border(
                width = if (focused) 2.dp else 1.dp,
                color = if (focused) Color.White else if (primary) Color.Transparent else OrbitColors.Line,
                shape = shape,
            )
            .combinedClickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 11.dp),
        contentAlignment = Alignment.Center,
    ) {
        Label(
            text,
            size = 13,
            color = if (primary) Color.White else OrbitColors.Text,
            weight = FontWeight.SemiBold,
            letterSpacing = 0.2f,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CircleAction(
    glyph: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    focusRequester: FocusRequester? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val focusModifier = if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier
    Box(
        modifier = modifier
            .size(42.dp)
            .onFocusChanged { focused = it.isFocused }
            .then(focusModifier)
            .clip(CircleShape)
            .background(if (active) OrbitColors.AccentSoft else Color(0xB315111E))
            .border(
                width = if (focused) 2.dp else 1.dp,
                color = if (focused) Color.White else OrbitColors.Line,
                shape = CircleShape,
            )
            .combinedClickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Label(
            glyph,
            size = 17,
            color = if (active) OrbitColors.Accent else OrbitColors.Text,
            weight = FontWeight.SemiBold,
        )
    }
}

@Composable
fun TopBar(
    onSearch: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.widthIn(min = 200.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Label("ORBIT", size = 15, color = OrbitColors.Accent, weight = FontWeight.Bold, letterSpacing = 3f)
        Box(Modifier.width(10.dp))
        OrbitButton("Search", onClick = onSearch)
        OrbitButton("Settings", onClick = onSettings)
    }
}

@Composable
fun OrbitNavBar(
    selected: String,
    solid: Boolean,
    onHome: () -> Unit,
    onBrowse: (String) -> Unit,
    onSearch: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
    homeFocus: FocusRequester? = null,
) {
    val background = if (solid) {
        Brush.verticalGradient(listOf(Color(0xFF141414), Color(0xFF141414)))
    } else {
        Brush.verticalGradient(listOf(Color(0xF0141414), Color(0x00141414)))
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(background)
            .padding(horizontal = 30.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        Label("ORBIT", size = 26, color = OrbitColors.Accent, weight = FontWeight.ExtraBold, letterSpacing = 2f)
        Spacer(Modifier.width(4.dp))
        NavAction("Home", selected == "home", onHome, homeFocus)
        NavAction("Channels", selected == "all", onClick = { onBrowse("all") })
        NavAction("Malayalam", selected == "malayalam", onClick = { onBrowse("malayalam") })
        NavAction("News", selected == "news", onClick = { onBrowse("news") })
        NavAction("Sports", selected == "sports", onClick = { onBrowse("sports") })
        NavAction("My List", selected == "favorites", onClick = { onBrowse("favorites") })
        NavAction("Recently Watched", selected == "recents", onClick = { onBrowse("recents") })
        Spacer(Modifier.weight(1f))
        SearchIconButton(onClick = onSearch)
        BellIcon()
        AvatarBadge(onClick = onSettings)
    }
}

@Composable
private fun SearchIconButton(onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val tint = if (focused) Color.White else Color(0xFFE5E5E5)
    Box(
        modifier = Modifier
            .onFocusChanged { focused = it.isFocused }
            .clip(RoundedCornerShape(4.dp))
            .background(if (focused) Color(0x22FFFFFF) else Color.Transparent)
            .tvItemClickable(onClick = onClick)
            .padding(6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(20.dp)) {
            val stroke = 2.dp.toPx()
            drawCircle(
                color = tint,
                radius = size.minDimension * 0.32f,
                center = Offset(size.width * 0.42f, size.height * 0.42f),
                style = Stroke(width = stroke),
            )
            drawLine(
                color = tint,
                start = Offset(size.width * 0.64f, size.height * 0.64f),
                end = Offset(size.width * 0.88f, size.height * 0.88f),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
        }
    }
}

@Composable
private fun BellIcon() {
    Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(18.dp)) {
            val tint = Color(0xFFB3B3B3)
            val stroke = 1.6.dp.toPx()
            val w = size.width
            val h = size.height
            val bell = Path().apply {
                moveTo(w * 0.18f, h * 0.7f)
                lineTo(w * 0.28f, h * 0.6f)
                lineTo(w * 0.28f, h * 0.44f)
                cubicTo(w * 0.28f, h * 0.24f, w * 0.39f, h * 0.16f, w * 0.5f, h * 0.16f)
                cubicTo(w * 0.61f, h * 0.16f, w * 0.72f, h * 0.24f, w * 0.72f, h * 0.44f)
                lineTo(w * 0.72f, h * 0.6f)
                lineTo(w * 0.82f, h * 0.7f)
                close()
            }
            drawPath(bell, tint, style = Stroke(width = stroke, cap = StrokeCap.Round))
            drawCircle(
                color = tint,
                radius = 1.6.dp.toPx(),
                center = Offset(w * 0.5f, h * 0.86f),
            )
        }
    }
}

@Composable
private fun NavAction(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    focusRequester: FocusRequester? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val focusModifier = if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier
    Box(
        modifier = Modifier
            .onFocusChanged { focused = it.isFocused }
            .then(focusModifier)
            .clip(RoundedCornerShape(4.dp))
            .background(if (focused) Color(0x22FFFFFF) else Color.Transparent)
            .tvItemClickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 6.dp),
    ) {
        Label(
            text,
            size = 13,
            color = if (selected || focused) OrbitColors.Text else Color(0xFFE5E5E5),
            weight = if (selected || focused) FontWeight.Bold else FontWeight.Medium,
        )
    }
}

@Composable
private fun AvatarBadge(onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .size(34.dp)
            .onFocusChanged { focused = it.isFocused }
            .clip(RoundedCornerShape(4.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF1D6FE0), Color(0xFF6AD1FF))))
            .border(2.dp, if (focused) Color.White else Color.Transparent, RoundedCornerShape(5.dp))
            .tvItemClickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Label("O", size = 14, color = Color.White, weight = FontWeight.Bold)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HeroButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = true,
    onLongClick: () -> Unit = {},
    focusRequester: FocusRequester? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(5.dp)
    val focusModifier = if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier
    Column(
        modifier = modifier
            .onFocusChanged { focused = it.isFocused }
            .then(focusModifier)
            .clip(shape)
            .background(if (primary) Color.White else Color(0x996D6D6E))
            .border(if (focused) 2.dp else 0.dp, OrbitColors.Accent, shape)
            .tvItemClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 26.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Label(
            text,
            size = 16,
            color = if (primary) Color.Black else Color.White,
            weight = FontWeight.Bold,
        )
    }
}

@Composable
fun BrowseChips(
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    firstFocus: FocusRequester? = null,
) {
    val filters = listOf(
        "all" to "All",
        "malayalam" to "Malayalam",
        "news" to "News",
        "entertainment" to "Entertainment",
        "movies" to "Movies",
        "music" to "Music",
        "sports" to "Sports",
    )
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 30.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        filters.forEachIndexed { index, (key, label) ->
            FilterChip(
                text = label,
                selected = selected == key,
                onClick = { onSelect(key) },
                focusRequester = if (index == 0) firstFocus else null,
            )
        }
    }
}

@Composable
private fun FilterChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    focusRequester: FocusRequester? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val focusModifier = if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier
    Box(
        modifier = Modifier
            .onFocusChanged { focused = it.isFocused }
            .then(focusModifier)
            .clip(RoundedCornerShape(30.dp))
            .background(if (selected) Color.White else Color.Transparent)
            .border(
                1.dp,
                when {
                    focused -> Color.White
                    selected -> Color.White
                    else -> OrbitColors.Line
                },
                RoundedCornerShape(30.dp),
            )
            .tvItemClickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 8.dp),
    ) {
        Label(
            text,
            size = 12,
            color = if (selected) Color.Black else if (focused) Color.White else OrbitColors.Muted,
            weight = if (selected) FontWeight.Bold else FontWeight.Medium,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun OrbitCard(
    card: ChannelCard,
    favorite: Boolean,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    width: Dp? = null,
    rank: Int? = null,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onInfo: (() -> Unit)? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (focused) 1.04f else 1f,
        animationSpec = tween(120),
        label = "card-scale",
    )
    val shape = RoundedCornerShape(8.dp)
    val focusModifier = if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier
    val sizeModifier = if (width != null) Modifier.width(width) else Modifier

    Row(modifier = modifier, verticalAlignment = Alignment.Bottom) {
        if (rank != null) {
            Label(
                "$rank",
                size = 62,
                color = Color(0xFF4D4D4D),
                weight = FontWeight.ExtraBold,
                modifier = Modifier.padding(end = 2.dp),
            )
        }
        Column(
            modifier = Modifier
                .then(sizeModifier)
                .onFocusChanged { focused = it.isFocused }
                .then(focusModifier)
                .tvItemClickable(onClick = onClick, onLongClick = onLongClick)
                .onPreviewKeyEvent { event ->
                    if (onInfo != null && event.type == KeyEventType.KeyDown && event.key == Key.Menu) {
                        onInfo()
                        true
                    } else {
                        false
                    }
                }
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                },
        ) {
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
                ChannelArtwork(
                    card = card,
                    modifier = Modifier.fillMaxSize(),
                    shape = shape,
                    padding = if (isArtwork(card.logoUrl)) 0.dp else 18.dp,
                )
                Box(
                    Modifier
                        .matchParentSize()
                        .background(
                            Brush.verticalGradient(
                                0f to Color.Transparent,
                                0.55f to Color.Transparent,
                                1f to Color(0xE6000000),
                            ),
                        ),
                )
                Row(
                    Modifier
                        .matchParentSize()
                        .padding(9.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    if (rank == null) LiveBadge()
                    Spacer(Modifier.weight(1f))
                    if (favorite) {
                        Label("\u2605", size = 14, color = OrbitColors.Gold)
                    }
                }
                Column(
                    Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .padding(horizontal = 11.dp, vertical = 8.dp),
                ) {
                    Label(
                        card.name,
                        size = 13,
                        color = Color.White,
                        weight = FontWeight.Bold,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Label(
                            "${card.language} \u00b7 ${card.category}",
                            size = 9,
                            color = Color(0xFFD2D2D2),
                            modifier = Modifier.weight(1f),
                        )
                        Label("CH ${card.number}", size = 9, color = Color(0xFFD2D2D2), weight = FontWeight.Medium)
                    }
                }
                Box(
                    Modifier
                        .matchParentSize()
                        .border(
                            width = if (focused) 2.dp else 1.dp,
                            color = if (focused) Color.White else Color(0x14FFFFFF),
                            shape = shape,
                        ),
                )
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .clip(RoundedCornerShape(bottomStart = 8.dp, bottomEnd = 8.dp))
                    .background(if (focused) OrbitColors.SurfaceHigh else Color.Transparent)
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            ) {
                if (focused) {
                    Column {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            PopAction("\u25b6", primary = true)
                            PopAction(if (favorite) "\u2713" else "+", primary = false)
                            if (onInfo != null) PopAction("\u25be", primary = false)
                            Spacer(Modifier.weight(1f))
                            Label("LIVE", size = 8, color = OrbitColors.Live, weight = FontWeight.Bold, letterSpacing = 0.8f)
                        }
                        Spacer(Modifier.height(4.dp))
                        Label(
                            listOfNotNull(
                                card.language.takeIf { it.isNotBlank() },
                                card.category.takeIf { it.isNotBlank() },
                                "CH ${card.number}",
                            ).joinToString("  \u00b7  "),
                            size = 9,
                            color = OrbitColors.Text,
                        )
                        Label(
                            if (onInfo != null) "OK play  \u00b7  hold OK My List  \u00b7  info"
                            else "OK play  \u00b7  hold OK My List",
                            size = 8,
                            color = OrbitColors.Muted,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PopAction(glyph: String, primary: Boolean) {
    Box(
        Modifier
            .size(26.dp)
            .clip(CircleShape)
            .background(if (primary) Color.White else Color(0xFF2A2A2A))
            .border(2.dp, if (primary) Color.White else Color(0x88FFFFFF), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Label(
            glyph,
            size = 10,
            color = if (primary) Color.Black else Color.White,
            weight = FontWeight.Bold,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Top10Card(
    card: ChannelCard,
    rank: Int,
    favorite: Boolean,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onInfo: (() -> Unit)? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (focused) 1.06f else 1f,
        animationSpec = tween(120),
        label = "top10-scale",
    )
    val shape = RoundedCornerShape(4.dp)
    val focusModifier = if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier
    Row(verticalAlignment = Alignment.CenterVertically) {
        Label(
            "$rank",
            size = 104,
            color = Color(0xFF595959),
            weight = FontWeight.ExtraBold,
            letterSpacing = (-9f),
            drawStyle = Stroke(width = 2.5f),
            modifier = Modifier.padding(end = 2.dp),
        )
        Column(
            modifier = modifier
                .width(148.dp)
                .onFocusChanged { focused = it.isFocused }
                .then(focusModifier)
                .tvItemClickable(onClick = onClick, onLongClick = onLongClick)
                .onPreviewKeyEvent { event ->
                    if (onInfo != null && event.type == KeyEventType.KeyDown && event.key == Key.Menu) {
                        onInfo()
                        true
                    } else {
                        false
                    }
                }
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                },
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(2f / 3f)
                    .clip(shape)
                    .background(Brush.verticalGradient(listOf(categoryPalette(card.category).top, categoryPalette(card.category).bottom))),
            ) {
                SubcomposeAsyncImage(
                    model = card.logoUrl,
                    contentDescription = card.name,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().padding(18.dp),
                    loading = { ArtworkFallback(card, canvas = false) },
                    error = { ArtworkFallback(card, canvas = false) },
                )
                Box(
                    Modifier
                        .matchParentSize()
                        .background(
                            Brush.verticalGradient(
                                0f to Color.Transparent,
                                0.55f to Color.Transparent,
                                1f to Color(0xF2000000),
                            ),
                        ),
                )
                Row(Modifier.matchParentSize().padding(8.dp), verticalAlignment = Alignment.Top) {
                    LiveBadge()
                    Spacer(Modifier.weight(1f))
                    if (favorite) Label("\u2605", size = 12, color = OrbitColors.Gold)
                }
                Label(
                    card.name,
                    size = 12,
                    color = Color.White,
                    weight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.BottomStart).padding(horizontal = 9.dp, vertical = 7.dp),
                )
                Box(
                    Modifier
                        .matchParentSize()
                        .border(if (focused) 2.dp else 1.dp, if (focused) Color.White else Color(0x14FFFFFF), shape),
                )
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(40.dp)
                    .padding(horizontal = 9.dp, vertical = 4.dp),
            ) {
                if (focused) {
                    Column {
                        Label(
                            "${card.language} \u00b7 ${card.category}",
                            size = 9,
                            color = OrbitColors.Text,
                        )
                        Label("CH ${card.number}  \u00b7  OK to play", size = 8, color = OrbitColors.Muted)
                    }
                }
            }
        }
    }
}
