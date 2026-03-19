package com.a.anizle

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import org.json.JSONObject

/**
 * Anizle CloudStream 3 Provider
 * Source: https://anizle.org
 */
class AnizleProvider : MainAPI() {

    override var mainUrl    = "https://anizle.org"
    override var name       = "Anizle"
    override var lang       = "tr"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    private val playerBase = "https://anizmplayer.com"

    private val baseHeaders = mapOf(
        "User-Agent"      to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.7",
        "Origin"          to "https://anizle.org",
        "Referer"         to "https://anizle.org/",
    )
    private val xhrHeaders = baseHeaders + mapOf(
        "X-Requested-With" to "XMLHttpRequest",
        "Accept"           to "application/json, text/javascript, */*; q=0.01",
    )

    // ── Anime list cache (fetched once, reused for all searches) ─────────────
    // Same approach as the Aniyomi extension: fetch the full list once and
    // filter locally. Origin + Referer headers are required to get a response.
    private val animeList: List<Triple<String, String, String>> by lazy {
        try {
            val arr = org.json.JSONArray(
                app.get("$mainUrl/getAnimeListForSearch", headers = baseHeaders).text
            )
            (0 until arr.length()).mapNotNull { i ->
                val obj   = arr.optJSONObject(i) ?: return@mapNotNull null
                val slug  = obj.optString("info_slug",  "").ifBlank { return@mapNotNull null }
                val title = obj.optString("info_title", "").ifBlank { return@mapNotNull null }
                val thumb = obj.optString("info_poster", "")
                Triple(slug, title, thumb)
            }
        } catch (e: Exception) { emptyList() }
    }

    // ── Search ────────────────────────────────────────────────────────────────
    // Filters the cached list locally — instant after first load.
    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim().lowercase()
        if (q.isBlank()) return emptyList()

        return animeList
            .filter { (_, title, _) -> title.lowercase().contains(q) }
            .take(20)
            .map { (slug, title, thumb) ->
                val poster = when {
                    thumb.isBlank()          -> null
                    thumb.startsWith("http") -> thumb
                    else                     -> "$mainUrl/storage/pcovers/$thumb"
                }
                newAnimeSearchResponse(title, "$mainUrl/$slug", TvType.Anime) {
                    posterUrl = poster
                }
            }
    }

    // ── Home page ─────────────────────────────────────────────────────────────
    override val mainPage = mainPageOf(
        "1" to "Son Eklenen Bölümler",
        "2" to "Son Eklenen Animeler",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get("$mainUrl?sayfa=$page", headers = baseHeaders).document

        val items: List<SearchResponse> = when (request.data) {
            "2" -> {
                // "Son Eklenen Animeler" section at the bottom — cards ending in -izle
                doc.select("a[href*=-izle]")
                    .filter { el ->
                        el.selectFirst("img[src*=pcovers]") != null &&
                        !el.attr("href").contains("-bolum")
                    }
                    .distinctBy { it.attr("href") }
                    .mapNotNull { el ->
                        val href  = el.attr("abs:href").ifBlank { return@mapNotNull null }
                        val url   = href.removeSuffix("-izle")
                        val img   = el.selectFirst("img") ?: return@mapNotNull null
                        val poster = img.attr("abs:src").ifBlank { null }
                        val title = el.text().trim().ifBlank { img.attr("alt").trim() }
                        if (title.isBlank()) return@mapNotNull null
                        newAnimeSearchResponse(title, url, TvType.Anime) { posterUrl = poster }
                    }
            }
            else -> {
                // "Son Eklenen Bölümler" — episode links containing -bolum
                doc.select("a[href*=-bolum]")
                    .filter { it.selectFirst("img") != null }
                    .distinctBy { it.attr("href") }
                    .mapNotNull { el ->
                        val href   = el.attr("abs:href").ifBlank { return@mapNotNull null }
                        val img    = el.selectFirst("img") ?: return@mapNotNull null
                        val poster = img.attr("abs:src").ifBlank { null }
                        val title  = el.select("h6, strong, b, p").firstOrNull()?.text()?.trim()
                            ?: el.text().lines().lastOrNull { it.isNotBlank() }?.trim()
                            ?: return@mapNotNull null
                        // Convert episode URL to anime URL
                        val animeUrl = href
                            .replace(Regex("""-\d+[-.]?bolum[^/]*$"""), "")
                            .trimEnd('-')
                        newAnimeSearchResponse(title, animeUrl, TvType.Anime) { posterUrl = poster }
                    }
            }
        }

        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    // ── Load (anime detail page) ───────────────────────────────────────────────
    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = baseHeaders).document

        val title = doc.selectFirst("h1")?.text()?.trim()
            ?: url.substringAfterLast("/").replace("-", " ").trim()

        val poster = doc.select("img[src*=pcovers], img[src*=storage]")
            .firstOrNull()?.attr("abs:src")

        val plot = doc.selectFirst(".anime-description, .description, .summary")
            ?.also { it.select("h1,h2,h3,h4,a").remove() }
            ?.text()?.trim()?.ifBlank { null }

        val year = doc.select("li, td, span").map { it.text().trim() }
            .firstOrNull { it.matches(Regex("""\d{4}""")) }?.toIntOrNull()

        val tags = doc.select("a[href*=/kategoriler/]")
            .map { it.text().trim() }.filter { it.isNotBlank() }.ifEmpty { null }

        val episodes = doc.select("a[href*=-bolum]")
            .mapNotNull { el ->
                val href  = el.attr("abs:href").ifBlank { return@mapNotNull null }
                val label = el.text().trim().ifBlank { "Bölüm" }
                val epNum = Regex("""(\d+)[.\s]*[Bb]ölüm""").find(label)
                    ?.groupValues?.get(1)?.toIntOrNull()
                    ?: Regex("""-(\d+)[-.]?bolum""").find(href)
                        ?.groupValues?.get(1)?.toIntOrNull()
                newEpisode(href) { name = label; episode = epNum }
            }
            .distinctBy { it.data }

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            posterUrl = poster
            this.plot = plot
            this.year = year
            this.tags = tags
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    // ── Load links ────────────────────────────────────────────────────────────
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val html = try {
            app.get(data, headers = baseHeaders).text
        } catch (e: Exception) { return false }

        // Step 1: translator buttons
        val translators = mutableListOf<Pair<String, String>>()
        Regex("""translator="([^"]+)"[^>]*data-fansub-name="([^"]*)"""").findAll(html).forEach {
            val trUrl = it.groupValues[1].ifBlank { return@forEach }
            if (translators.none { t -> t.first == trUrl })
                translators += trUrl to it.groupValues[2].ifBlank { "Fansub" }
        }
        if (translators.isEmpty()) {
            Regex("""data-fansub-name="([^"]*)"[^>]*translator="([^"]+)"""").findAll(html).forEach {
                val trUrl = it.groupValues[2].ifBlank { return@forEach }
                if (translators.none { t -> t.first == trUrl })
                    translators += trUrl to it.groupValues[1].ifBlank { "Fansub" }
            }
        }
        if (translators.isEmpty()) return false

        var found = false

        for ((trUrl, fansubName) in translators) {
            // Step 2: video list
            val trText = try {
                app.get(trUrl, headers = xhrHeaders).text
            } catch (e: Exception) { continue }

            val trHtml = try { JSONObject(trText).optString("data", trText) }
            catch (e: Exception) { trText }

            val videos = mutableListOf<Pair<String, String>>()
            Regex("""video="([^"]+)"[^>]*data-video-name="([^"]*)"""").findAll(trHtml).forEach {
                videos += it.groupValues[1] to it.groupValues[2].ifBlank { "Player" }
            }
            if (videos.isEmpty()) {
                Regex("""data-video-name="([^"]*)"[^>]*video="([^"]+)"""").findAll(trHtml).forEach {
                    videos += it.groupValues[2] to it.groupValues[1].ifBlank { "Player" }
                }
            }

            for ((videoUrl, videoName) in videos) {
                // Step 3: player numeric ID
                val vText = try {
                    app.get(videoUrl, headers = xhrHeaders).text
                } catch (e: Exception) { continue }
                val playerHtml = try { JSONObject(vText).optString("player", vText) }
                catch (e: Exception) { vText }

                val playerId = Regex("""/player/(\d+)""")
                    .find(playerHtml)?.groupValues?.get(1) ?: continue

                // Step 4: FirePlayer hash
                val pageHtml = try {
                    app.get("$mainUrl/player/$playerId", headers = baseHeaders).text
                } catch (e: Exception) { continue }

                val fireId = extractFireplayerId(pageHtml) ?: continue

                // Step 5: real stream URL
                val streamText = try {
                    app.post(
                        "$playerBase/player/index.php?data=$fireId&do=getVideo",
                        headers = mapOf(
                            "User-Agent"       to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120.0.0.0 Safari/537.36",
                            "X-Requested-With" to "XMLHttpRequest",
                            "Accept"           to "application/json, */*; q=0.01",
                            "Referer"          to "$playerBase/player/$fireId",
                            "Origin"           to playerBase,
                        )
                    ).text
                } catch (e: Exception) { continue }

                val json = try { JSONObject(streamText) } catch (e: Exception) { continue }
                val label = "$fansubName – $videoName"

                val securedLink = json.optString("securedLink", "")
                if (json.optBoolean("hls", false) && securedLink.isNotBlank()) {
                    callback(newExtractorLink(source = name, name = label, url = securedLink,
                        type = ExtractorLinkType.M3U8) { quality = Qualities.Unknown.value })
                    found = true; continue
                }
                val videoSource = json.optString("videoSource", "")
                if (videoSource.isNotBlank()) {
                    callback(newExtractorLink(source = name, name = label, url = videoSource,
                        type = ExtractorLinkType.VIDEO) { quality = Qualities.Unknown.value })
                    found = true
                }
            }
        }
        return found
    }

    // ── JS helpers ────────────────────────────────────────────────────────────

    private fun extractFireplayerId(html: String): String? {
        val evalMatch = Regex(
            """eval\(function\(p,a,c,k,e,d\)\{.*?return p\}\('(.*?)',(\d+),(\d+),'([^']+)'\.split\('\|'\),0,\{\}\)\)""",
            setOf(RegexOption.DOT_MATCHES_ALL)
        ).find(html)
        if (evalMatch != null) {
            runCatching {
                val decoded = unpackJs(
                    evalMatch.groupValues[1],
                    evalMatch.groupValues[2].toInt(),
                    evalMatch.groupValues[3].toInt(),
                    evalMatch.groupValues[4].split("|")
                )
                Regex("""FirePlayer\s*\(\s*["']([a-f0-9]{32})["']""").find(decoded)
                    ?.groupValues?.get(1)?.let { return it }
            }
        }
        return Regex("""FirePlayer\s*\(["']([a-f0-9]{32})["']""")
            .find(html)?.groupValues?.get(1)
    }

    private fun unpackJs(p: String, a: Int, c: Int, k: List<String>): String {
        fun toBase(n: Int, base: Int): String {
            val head = if (n < base) "" else toBase(n / base, base)
            val rem  = n % base
            return head + when {
                rem > 35 -> (rem + 29).toChar()
                rem > 9  -> (rem + 87).toChar()
                else     -> ('0' + rem)
            }
        }
        var idx = c
        val dict = mutableMapOf<String, String>()
        while (idx-- > 0) {
            val key = toBase(idx, a)
            dict[key] = if (idx < k.size && k[idx].isNotEmpty()) k[idx] else key
        }
        return Regex("""\b\w+\b""").replace(p) { dict[it.value] ?: it.value }
    }
}
