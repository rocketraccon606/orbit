package app.orbit.tv.catalog

data class ChannelCard(
    val id: String,
    val name: String,
    val logoUrl: String?,
    val category: String,
    val language: String,
    val country: String,
    val state: String,
    val number: Int,
    val network: String = "",
    val launched: String = "",
) {
    val playable: Boolean get() = state != "dead"
    val unverified: Boolean get() = state == "unverified"
}

data class StreamRef(
    val id: String,
    val url: String,
    val kind: String,
    val state: String,
    val userAgent: String?,
    val referrer: String?,
    val position: Int,
)

data class ChannelDetail(
    val card: ChannelCard,
    val streams: List<StreamRef>,
    val website: String = "",
    val owners: String = "",
)

data class CollectionSummary(
    val id: String,
    val title: String,
    val kind: String,
    val position: Int,
    val count: Int,
)

data class HomeRow(
    val id: String,
    val title: String,
    val items: List<ChannelCard>,
)

data class CatalogCounts(
    val channels: Int,
    val playable: Int,
    val collections: Int,
    val malayalam: Int,
)

data class CatalogInfo(
    val version: String,
    val builtAt: String,
    val schema: Int,
    val source: String,
    val counts: CatalogCounts?,
)
