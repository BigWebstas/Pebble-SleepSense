package net.webstas.sleepsense

/** A Spotify playlist (or album, track...) as the "spotify:playlist:<id>" address Spotify opens, from a pasted link or address. */
object SpotifyLink {
    private val web = Regex("""open\.spotify\.com/(?:intl-[a-z-]+/)?(playlist|album|track|artist|show|episode)/([A-Za-z0-9]+)""")
    private val uri = Regex("""spotify:(playlist|album|track|artist|show|episode):([A-Za-z0-9]+)""")

    fun parse(text: String): String? {
        val match = web.find(text) ?: uri.find(text) ?: return null
        return "spotify:${match.groupValues[1]}:${match.groupValues[2]}"
    }
}
