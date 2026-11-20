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

/** BE3 곡 검색·확인 저장소. MySQL 구현은 [MysqlMusicCatalog]. */
interface MusicCatalog {
    /** 검색 결과를 정규화·캐시해 최대 [limit]개. 결과 없음은 빈 목록, 외부 장애는 503 MUSIC_SEARCH_UNAVAILABLE. */
    fun search(query: String, limit: Int): List<TrackResponse>

    /** 검색 결과로 받은 track_id를 확인한다. 없는 곡이면 null. */
    fun find(trackId: String): TrackResponse?
}

/** iTunes Search API에서 받은 곡 한 건. */
data class ItunesTrack(val trackId: Long, val title: String, val artist: String, val artworkUrl: String?)

/** iTunes Search API 호출. 장애는 [ApiException] MUSIC_SEARCH_UNAVAILABLE로 던진다. */
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
        // 100px 썸네일 대신 같은 경로의 600px 이미지를 사용한다.
        track.artworkUrl?.replace("100x100bb", "600x600bb"),
        spotifySearchUrl(track.title, track.artist),
    )

    /** 직접 입력한 곡. 검수 때 사람이 확인하며 커버는 없다. */
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
        it.path("/search").queryParam("term", term).queryParam("country", "KR").queryParam("media", "music")
            .queryParam("entity", "song").queryParam("limit", limit).build()
    }

    override fun lookup(trackId: Long): ItunesTrack? = fetch {
        it.path("/lookup").queryParam("id", trackId).queryParam("country", "KR").queryParam("entity", "song").build()
    }.firstOrNull { it.trackId == trackId }

    private fun fetch(uri: (UriBuilder) -> URI): List<ItunesTrack> {
        val body = try {
            // iTunes는 text/javascript로 응답하므로 문자열로 받아 직접 읽는다.
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
