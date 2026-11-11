package com.tvdeck.app.model

const val MINUTE = 60_000L
const val HALF_HOUR = 30 * MINUTE
const val HOUR = 60 * MINUTE
const val DAY = 24 * HOUR

enum class SourceType { M3U, XTREAM }

/** A provider connection the user has added (like a "portal" in other IPTV apps). */
data class Profile(
    val id: String,
    val name: String,
    val type: SourceType,
    /** M3U playlist URL, or the Xtream server URL. */
    val url: String,
    val username: String = "",
    val password: String = "",
    /** Optional XMLTV guide URL that overrides the one from the provider. */
    val epgUrl: String = "",
    val userAgent: String = "",
    /** Xtream live stream format: "ts" or "m3u8". */
    val outputFormat: String = "ts",
)

/** What the user has switched off for a profile. Anything not listed loads, including groups the provider adds later. */
data class ContentFilter(
    val disabledLive: Set<String> = emptySet(),
    val disabledMovies: Set<String> = emptySet(),
    val disabledSeries: Set<String> = emptySet(),
    val hiddenChannels: Set<String> = emptySet(),
)

/** count is null when the section was skipped entirely, so its items were never downloaded. */
data class GroupInfo(val name: String, val count: Int?)

/** Every group the provider offers, including the ones switched off. Used by the channel manager. */
data class Catalog(
    val live: List<GroupInfo> = emptyList(),
    val movies: List<GroupInfo> = emptyList(),
    val series: List<GroupInfo> = emptyList(),
)

/**
 * How to build an archive (catch-up) URL for a channel.
 * mode: default | append | shift | flussonic | xc
 * source: URL template with placeholders like {utc}, {duration}, {Y}-{m}-{d}.
 */
data class Catchup(
    val mode: String,
    val source: String,
    val days: Int,
    val timeZoneId: String = "UTC",
)

data class Channel(
    val key: String,
    val name: String,
    val logo: String?,
    val group: String,
    val url: String,
    val epgId: String?,
    val number: Int,
    val catchup: Catchup?,
    /** Quality tag found in the provider's name ("FHD", "HD"…), shown when versions are combined. */
    val quality: String = "",
    /** Other quality versions of this channel, in the order to fall back to them. */
    val alternates: List<Channel> = emptyList(),
    /** The provider's name when the user renamed the channel; the guide still matches on it. */
    val providerName: String = "",
    /** The user combined this channel with others by hand (rather than HD/FHD being matched automatically). */
    val combinedManually: Boolean = false,
) {
    /** Name used to find the channel in the TV guide. */
    val guideName: String get() = providerName.ifEmpty { name }

    /** This channel's key plus the keys of its combined versions. */
    val allKeys: List<String> get() = (listOf(key) + alternates.map { it.key }).distinct()
}

data class Programme(
    val start: Long,
    val stop: Long,
    val title: String,
    val desc: String,
)

data class VodItem(
    val key: String,
    val name: String,
    val poster: String?,
    val group: String,
    val url: String,
    val rating: String = "",
    val xtreamId: String? = null,
)

data class Episode(
    val key: String,
    val title: String,
    val season: Int,
    val episode: Int,
    val url: String,
    val plot: String = "",
    val image: String? = null,
    val duration: String = "",
)

data class SeriesItem(
    val key: String,
    val name: String,
    val cover: String?,
    val group: String,
    val plot: String = "",
    val rating: String = "",
    val xtreamId: String? = null,
    /** Pre-filled for M3U playlists; loaded on demand for Xtream. */
    val episodes: List<Episode> = emptyList(),
)

/** Subscription details an Xtream Codes provider reports at sign-in. Null fields weren't reported. */
data class AccountInfo(
    val status: String,
    /** Epoch ms; null means no expiry date (unlimited) or not reported. */
    val expiresAt: Long?,
    val maxConnections: Int?,
    val activeConnections: Int?,
    val trial: Boolean,
    /** When these details were fetched, epoch ms. */
    val checkedAt: Long,
)

/** The user's own channel order and names for a profile. Anything not listed keeps the provider's order and name. */
data class ChannelLayout(
    /** Live groups in the user's order; groups not listed follow in the provider's order. */
    val groups: List<String> = emptyList(),
    /** Channel keys in the user's order, per group; channels not listed follow in the provider's order. */
    val order: Map<String, List<String>> = emptyMap(),
    /** Custom channel names by channel key. */
    val names: Map<String, String> = emptyMap(),
    /** Number channels 1, 2, 3… in the order shown instead of using the provider's numbers. */
    val renumber: Boolean = false,
    /** Channels combined by hand into one entry: keys in the order to try them, the first gives the entry. */
    val merges: List<List<String>> = emptyList(),
    /** Channels the user split apart, kept out of automatic HD/FHD combining. */
    val noAutoMerge: Set<String> = emptySet(),
) {
    val isEmpty: Boolean get() = groups.isEmpty() && order.isEmpty() && names.isEmpty() && !renumber &&
        merges.isEmpty() && noAutoMerge.isEmpty()
}

/** A programme the user asked to be reminded about. */
data class Reminder(
    val profileId: String,
    val channelKey: String,
    val channelName: String,
    val title: String,
    val start: Long,
    val stop: Long,
) {
    val id: String get() = "$profileId|$channelKey|$start"
}
