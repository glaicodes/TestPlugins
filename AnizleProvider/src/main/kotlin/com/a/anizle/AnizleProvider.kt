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

    override var mainUrl    = "https://anizm.net"
    override var name       = "Anizle"
    override var lang       = "tr"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    private val playerBase = "https://anizmplayer.com"

    // Session warmup — uses NiceHttp's shared cookie jar which already has
    // valid CF cookies if the Anizm extension ran recently on this device.
    private var sessionReady = false
    private suspend fun getSession() {
        if (sessionReady) return
        android.util.Log.d("Anizle", "getSession başlatıldı")
        try {
            app.get(mainUrl, headers = baseHeaders)
            sessionReady = true
            android.util.Log.d("Anizle", "getSession tamamlandı")
        } catch (e: Exception) {
            android.util.Log.e("Anizle", "getSession hatasi: ${e.message}")
        }
    }

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



    // ── Search ────────────────────────────────────────────────────────────────
    // GET /searchAnime?query=TERM&type=detailed&limit=10&...
    // Returns JSON: {"data": [{"info_title":"..","info_slug":"..","info_poster":"..","info_year":2020}]}
    // Confirmed by reverse-engineering: search uses Jackson readValue, NOT HTML parsing.
    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim()
        if (q.isBlank()) return emptyList()

        android.util.Log.d("Anizle", "Arama: $q")
        getSession()

        val responseText = try {
            val resp = app.get(
                "$mainUrl/searchAnime",
                headers = baseHeaders,
                params  = mapOf(
                    "query"          to q,
                    "type"           to "detailed",
                    "limit"          to "20",
                    "priorityField"  to "info_title",
                    "orderBy"        to "info_year",
                    "orderDirection" to "ASC",
                )
            )
            android.util.Log.d("Anizle", "HTTP ${resp.code} len=${resp.text.length}")
            resp.text
        } catch (e: Exception) {
            android.util.Log.e("Anizle", "Arama hatasi: ${e.message}")
            return emptyList()
        }

        return try {
            val root = org.json.JSONObject(responseText)
            val arr  = root.optJSONArray("data") ?: org.json.JSONArray(responseText)
            android.util.Log.d("Anizle", "JSON parse OK, ${arr.length()} sonuc")

            (0 until arr.length()).mapNotNull { i ->
                val obj   = arr.optJSONObject(i) ?: return@mapNotNull null
                val slug  = obj.optString("info_slug",  "").ifBlank { return@mapNotNull null }
                val title = obj.optString("info_title", "").ifBlank { return@mapNotNull null }
                val thumb = obj.optString("info_poster", "")
                val poster = when {
                    thumb.isBlank()           -> null
                    thumb.startsWith("http")  -> thumb
                    else -> "$mainUrl/storage/pcovers/$thumb"
                }
                newAnimeSearchResponse(title, "$mainUrl/$slug", TvType.Anime) {
                    posterUrl = poster
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("Anizle", "JSON hatasi: ${e.message}, ilk 200: ${responseText.take(200)}")
            emptyList()
        }
    }

    // ── Home page ─────────────────────────────────────────────────────────────
    override val mainPage = mainPageOf(
        "anime-izle" to "Son Eklenen Bölümler",
        ""           to "Son Eklenen Animeler",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
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
