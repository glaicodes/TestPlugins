package com.a.anizle

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONArray
import org.json.JSONObject

/**
 * Anizle / Anizm CloudStream 3 Provider
 *
 * Reverse-engineered from the kraptor Anizm cs3 extension (v44).
 *
 * Key findings:
 *  - Domain changes frequently; fetched from a remote list at startup
 *  - Search:   GET /searchAnime?query=...  → div.aramaSonucItem
 *  - Episodes: div#episodesMiddle a[href]
 *  - Fansubs:  div.fansubSecimKutucugu a[translator]
 *  - Videos:   a.videoPlayerButtons[video]  (XHR JSON → player html → FirePlayer hash)
 */
class AnizleProvider : MainAPI() {

    override var mainUrl    = "https://anizle.org"
    override var name       = "Anizle"
    override var lang       = "tr"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    private val playerBase  = "https://anizmplayer.com"
    private val domainListUrl =
        "https://raw.githubusercontent.com/Kraptor123/domainListesi/refs/heads/main/eklenti_domainleri.txt"

    private val baseHeaders get() = mapOf(
        "User-Agent"      to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36",
        "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.7",
        "Origin"          to mainUrl,
        "Referer"         to "$mainUrl/",
    )
    private val xhrHeaders get() = baseHeaders + mapOf(
        "X-Requested-With" to "XMLHttpRequest",
        "Accept"           to "application/json, text/javascript, */*; q=0.01",
    )

    // ── Dynamic domain ────────────────────────────────────────────────────────
    // The site keeps switching domains. Fetch the current one at startup,
    // fall back to anizle.org if the list can't be reached.
    private var domainInitialised = false

    private suspend fun ensureDomain() {
        if (domainInitialised) return
        domainInitialised = true
        try {
            val text = app.get(domainListUrl).text.trim()
            // File contains lines like "anizle_url=https://anizle.org"
            // or just a plain URL per line — handle both
            val url = text.lines()
                .map { it.trim() }
                .firstOrNull { it.startsWith("http") && it.contains("aniz") }
                ?: text.lines().firstOrNull { it.startsWith("http") }
            if (!url.isNullOrBlank()) {
                mainUrl = url.trimEnd('/')
            }
        } catch (_: Exception) { /* keep default */ }
    }

    // ── Search ────────────────────────────────────────────────────────────────
    // GET /searchAnime?query=TERM&type=detailed&limit=10&...
    // Results: div.aramaSonucItem  →  a.titleLink (url+title)  +  img (poster)
    override suspend fun search(query: String): List<SearchResponse> {
        ensureDomain()
        val q = query.trim()
        if (q.isBlank()) return emptyList()

        val doc = try {
            app.get(
                "$mainUrl/searchAnime",
                headers  = baseHeaders,
                params   = mapOf(
                    "query"          to q,
                    "type"           to "detailed",
                    "limit"          to "20",
                    "priorityField"  to "info_title",
                    "orderBy"        to "info_year",
                    "orderDirection" to "ASC",
                )
            ).document
        } catch (_: Exception) { return emptyList() }

        return doc.select("div.aramaSonucItem").mapNotNull { el ->
            val link   = el.selectFirst("a.titleLink") ?: el.selectFirst("a[href]") ?: return@mapNotNull null
            val url    = link.attr("abs:href").ifBlank { return@mapNotNull null }
            val title  = el.selectFirst("div.title, div.posterAlt.truncateText")
                ?.text()?.trim()
                ?: link.text().trim()
            if (title.isBlank()) return@mapNotNull null
            val poster = el.selectFirst("img[src*=pcovers], img")?.attr("abs:src")?.ifBlank { null }

            newAnimeSearchResponse(title, url, TvType.Anime) { posterUrl = poster }
        }
    }

    // ── Home page ─────────────────────────────────────────────────────────────
    override val mainPage = mainPageOf(
        "anime-izle" to "Son Eklenen Bölümler",
        ""           to "Son Eklenen Animeler",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        ensureDomain()
        val url = if (request.data == "anime-izle")
            "$mainUrl/anime-izle?sayfa=$page"
        else
            "$mainUrl?sayfa=$page"

        val doc = app.get(url, headers = baseHeaders).document

        val items = doc.select("div#episodesMiddle div.posterBlock > a, a.imgWrapperLink, a[href*=-bolum]")
            .ifEmpty { doc.select("a[href*=-izle]").filter { it.selectFirst("img") != null } }
            .distinctBy { it.attr("href") }
            .mapNotNull { el ->
                val href   = el.attr("abs:href").ifBlank { return@mapNotNull null }
                val img    = el.selectFirst("img") ?: return@mapNotNull null
                val poster = img.attr("abs:src").ifBlank { null }
                val title  = el.selectFirst("div.title, .truncateText, strong, b, h6")
                    ?.text()?.trim()
                    ?: img.attr("alt").trim()
                if (title.isBlank()) return@mapNotNull null

                // If this is an episode link, strip to anime URL
                val animeUrl = if (href.contains("-bolum"))
                    href.replace(Regex("""-\d+[-.]?bolum[^/]*$"""), "").trimEnd('-')
                else
                    href.removeSuffix("-izle")

                newAnimeSearchResponse(title, animeUrl, TvType.Anime) { posterUrl = poster }
            }

        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    // ── Load (anime detail page) ───────────────────────────────────────────────
    override suspend fun load(url: String): LoadResponse {
        ensureDomain()
        val doc = app.get(url, headers = baseHeaders).document

        val title = doc.selectFirst("h2.anizm_pageTitle, h1")?.text()?.trim()
            ?: url.substringAfterLast("/").replace("-", " ").trim()

        val poster = doc.selectFirst("div.infoPosterImg > img, img[src*=pcovers]")
            ?.attr("abs:src")

        val plot = doc.selectFirst("div.infoDesc, .anime-description, .description")
            ?.also { it.select("h1,h2,h3,h4,a").remove() }
            ?.text()?.trim()?.ifBlank { null }

        val year = doc.select("span.dataValue, li, td").map { it.text().trim() }
            .firstOrNull { it.matches(Regex("""\d{4}""")) }?.toIntOrNull()

        val tags = doc.select("span.dataValue > span.tag > span.label, a[href*=/kategoriler/]")
            .map { it.text().trim() }.filter { it.isNotBlank() }.ifEmpty { null }

        // Episodes are inside div#episodesMiddle
        val episodes = doc.select("div#episodesMiddle a[href]")
            .ifEmpty { doc.select("div.episodeListTabContent a[href], a[href*=-bolum]") }
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
    // Step 1: div.fansubSecimKutucugu a[translator]  → translator URLs
    // Step 2: GET translator URL (XHR) → JSON {data: html}
    //         html contains: a.videoPlayerButtons[video]
    // Step 3: GET video URL (XHR) → JSON {player: html} → /player/{id}
    // Step 4: GET /player/{id} → packed JS → FirePlayer hash (32 hex)
    // Step 5: POST anizmplayer.com/player/index.php?data={hash}&do=getVideo
    //         → JSON {hls, securedLink} or {videoSource}
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        ensureDomain()
        val doc = try {
            app.get(data, headers = baseHeaders).document
        } catch (_: Exception) { return false }

        // Step 1: fansub buttons
        val fansubEls = doc.select("div.fansubSecimKutucugu a[translator]")
            .ifEmpty { doc.select("div#fansec > a[translator], a[translator]") }

        if (fansubEls.isEmpty()) return false

        var found = false

        for (fansubEl in fansubEls) {
            val trUrl     = fansubEl.attr("abs:translator").ifBlank {
                fansubEl.attr("translator").let { if (it.startsWith("http")) it else "$mainUrl/$it" }
            }.ifBlank { continue }
            val fansubName = fansubEl.text().trim().ifBlank { "Fansub" }

            // Step 2: video button list
            val trText = try {
                app.get(trUrl, headers = xhrHeaders).text
            } catch (_: Exception) { continue }

            val trHtml = try { JSONObject(trText).optString("data", trText) }
            catch (_: Exception) { trText }

            // Parse video buttons: <a class="videoPlayerButtons" video="URL">Name</a>
            val trDoc  = org.jsoup.Jsoup.parse(trHtml)
            val videoEls = trDoc.select("a.videoPlayerButtons[video]")
                .ifEmpty { trDoc.select("a[video]") }

            for (videoEl in videoEls) {
                val videoUrl  = videoEl.attr("video").let {
                    if (it.startsWith("http")) it else "$mainUrl/$it"
                }.ifBlank { continue }
                val videoName = videoEl.text().trim().ifBlank { "Player" }

                // Step 3: player numeric ID
                val vText = try {
                    app.get(videoUrl, headers = xhrHeaders).text
                } catch (_: Exception) { continue }
                val playerHtml = try { JSONObject(vText).optString("player", vText) }
                catch (_: Exception) { vText }

                val playerId = Regex("""/player/(\d+)""")
                    .find(playerHtml)?.groupValues?.get(1) ?: continue

                // Step 4: FirePlayer hash
                val pageHtml = try {
                    app.get("$mainUrl/player/$playerId", headers = baseHeaders).text
                } catch (_: Exception) { continue }

                val fireId = extractFireplayerId(pageHtml) ?: continue

                // Step 5: real stream
                val streamText = try {
                    app.post(
                        "$playerBase/player/index.php?data=$fireId&do=getVideo",
                        headers = mapOf(
                            "User-Agent"       to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/125.0.0.0 Safari/537.36",
                            "X-Requested-With" to "XMLHttpRequest",
                            "Accept"           to "application/json, */*; q=0.01",
                            "Referer"          to "$playerBase/player/$fireId",
                            "Origin"           to playerBase,
                        )
                    ).text
                } catch (_: Exception) { continue }

                val json  = try { JSONObject(streamText) } catch (_: Exception) { continue }
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
