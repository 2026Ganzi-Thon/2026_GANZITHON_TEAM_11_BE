package com.yangsenunbam.music

import com.yangsenunbam.common.ApiException
import com.yangsenunbam.common.ErrorCode
import com.yangsenunbam.common.unconfiguredPort
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.util.UriBuilder
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.nio.charset.StandardCharsets
import java.time.Duration

interface MusicCatalog {
    fun search(query: String, limit: Int): List<TrackResponse>

    fun find(trackId: String): TrackResponse?
}

data class ItunesTrack(val trackId: Long, val title: String, val artist: String, val artworkUrl: String?)

interface ItunesClient {
    fun search(term: String, limit: Int): List<ItunesTrack>
    fun lookup(trackId: Long): ItunesTrack?
}

object Tracks {
    const val ID_PREFIX = "itunes_"
    private const val SPOTIFY_SEARCH = "https://open.spotify.com/search/"

    fun id(trackId: Long) = "$ID_PREFIX$trackId"

    fun itunesId(id: String): Long? =
        id.takeIf { it.startsWith(ID_PREFIX) }?.removePrefix(ID_PREFIX)?.takeIf { it.all(Char::isDigit) }?.toLongOrNull()

    fun spotifySearchUrl(title: String, artist: String): String =
        SPOTIFY_SEARCH + URLEncoder.encode("$title $artist", StandardCharsets.UTF_8).replace("+", "%20")

    fun from(track: ItunesTrack) = TrackResponse(
        id(track.trackId), track.title, track.artist,
        track.artworkUrl?.replace("100x100bb", "600x600bb"),
        spotifySearchUrl(track.title, track.artist),
    )

    fun manual(id: String, title: String, artist: String) =
        TrackResponse(id, title, artist, null, spotifySearchUrl(title, artist))
}

@Component
class HttpItunesClient(
    @Value("\${app.music.itunes-base-url:https://itunes.apple.com}") baseUrl: String,
    private val mapper: JsonMapper,
) : ItunesClient {
    private val client: RestClient = run {
        val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
            .followRedirects(HttpClient.Redirect.NORMAL).build()
        val factory = JdkClientHttpRequestFactory(http).apply { setReadTimeout(Duration.ofSeconds(5)) }
        RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build()
    }

    override fun search(term: String, limit: Int): List<ItunesTrack> = fetch {
        it.path("/search").queryParam("term", term).queryParam("country", "US").queryParam("media", "music")
            .queryParam("entity", "song").queryParam("limit", limit).build()
    }

    override fun lookup(trackId: Long): ItunesTrack? = fetch {
        it.path("/lookup").queryParam("id", trackId).queryParam("country", "US").queryParam("entity", "song").build()
    }.firstOrNull { it.trackId == trackId }

    private fun fetch(uri: (UriBuilder) -> URI): List<ItunesTrack> {
        val body = try {
            client.get().uri(uri).retrieve().body(String::class.java)
        } catch (e: Exception) {
            throw unavailable()
        } ?: throw unavailable()
        val results = try { mapper.readTree(body).path("results") } catch (e: Exception) { throw unavailable() }
        if (!results.isArray) throw unavailable()
        return (0 until results.size()).mapNotNull { index ->
            val item = results.path(index)
            val id = item.path("trackId")
            val title = item.path("trackName").asString("").trim()
            val artist = item.path("artistName").asString("").trim()
            if (item.path("kind").asString("") != "song" || !id.isNumber || title.isEmpty() || artist.isEmpty()) null
            else ItunesTrack(id.asLong(), title, artist, item.path("artworkUrl100").asString("").ifBlank { null })
        }
    }

    private fun unavailable() = ApiException(ErrorCode.MUSIC_SEARCH_UNAVAILABLE, "곡 검색을 잠시 이용할 수 없어요.")
}

@Configuration(proxyBeanMethods = false)
class MusicConfiguration {
    @Bean
    @Profile("!mysql")
    @ConditionalOnMissingBean(MusicCatalog::class)
    fun unconfiguredMusicCatalog(): MusicCatalog = unconfiguredPort("BE3 곡 저장소 어댑터 연결이 필요합니다.")
}
