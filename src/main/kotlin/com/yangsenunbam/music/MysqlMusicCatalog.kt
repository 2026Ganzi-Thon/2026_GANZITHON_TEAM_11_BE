package com.yangsenunbam.music

import com.yangsenunbam.common.ApiException
import com.yangsenunbam.common.ErrorCode
import com.yangsenunbam.feed.persistence.first
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Profile
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import tools.jackson.databind.json.JsonMapper
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.text.Normalizer
import java.time.Clock
import java.time.Duration
import java.util.Locale

@Repository
@Profile("mysql")
class MysqlMusicCatalog(
    private val jdbc: JdbcTemplate,
    private val itunes: ItunesClient,
    private val mapper: JsonMapper,
    private val clock: Clock,
    @Value("\${app.music.cache-ttl:PT24H}") private val ttl: Duration,
) : MusicCatalog {
    override fun search(query: String, limit: Int): List<TrackResponse> {
        val normalized = normalize(query)
        val hash = sha256(normalized)
        val now = clock.millis()
        val cached = jdbc.first("SELECT track_ids_json, cached_at_ms FROM music_search_cache WHERE query_hash = ?", hash) {
            mapper.readValue(it.getString("track_ids_json"), Array<String>::class.java).toList() to it.getLong("cached_at_ms")
        }
        if (cached != null && now - cached.second < ttl.toMillis()) return load(cached.first).take(limit)

        val tracks = try {
            itunes.search(normalized, FETCH_SIZE).map(Tracks::from).distinctBy { it.id }
        } catch (e: ApiException) {
            if (cached != null) return load(cached.first).take(limit)
            throw e
        }
        tracks.forEach { upsert(it, now) }
        jdbc.update("""INSERT INTO music_search_cache(query_hash, track_ids_json, cached_at_ms) VALUES (?, ?, ?) AS new
            ON DUPLICATE KEY UPDATE track_ids_json = new.track_ids_json, cached_at_ms = new.cached_at_ms""",
            hash, mapper.writeValueAsString(tracks.map { it.id }), now)
        return tracks.take(limit)
    }

    override fun find(trackId: String): TrackResponse? {
        jdbc.first("SELECT * FROM music_tracks WHERE id = ?", trackId) { track(it) }?.let { return it }
        val itunesId = Tracks.itunesId(trackId) ?: return null
        val found = itunes.lookup(itunesId)?.let(Tracks::from) ?: return null
        upsert(found, clock.millis())
        return found
    }

    private fun load(ids: List<String>): List<TrackResponse> {
        if (ids.isEmpty()) return emptyList()
        val byId = jdbc.query("SELECT * FROM music_tracks WHERE id IN (${ids.joinToString(",") { "?" }})",
            RowMapper { rs, _ -> track(rs) }, *ids.toTypedArray()).associateBy { it.id }
        return ids.mapNotNull(byId::get)
    }

    private fun upsert(track: TrackResponse, now: Long) {
        jdbc.update("""INSERT INTO music_tracks(id, title, artist, cover_url, spotify_search_url, cached_at_ms)
            VALUES (?, ?, ?, ?, ?, ?) AS new
            ON DUPLICATE KEY UPDATE title = new.title, artist = new.artist, cover_url = new.cover_url,
                spotify_search_url = new.spotify_search_url, cached_at_ms = new.cached_at_ms""",
            track.id, track.title, track.artist, track.coverUrl, track.spotifySearchUrl, now)
    }

    private fun track(rs: java.sql.ResultSet) = TrackResponse(
        rs.getString("id"), rs.getString("title"), rs.getString("artist"),
        rs.getString("cover_url"), rs.getString("spotify_search_url"),
    )

    companion object {
        const val FETCH_SIZE = 50

        fun normalize(query: String): String =
            Normalizer.normalize(query, Normalizer.Form.NFKC).trim().replace(Regex("\\s+"), " ").lowercase(Locale.ROOT)

        private fun sha256(value: String): String =
            MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
    }
}
