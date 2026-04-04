package com.a.anizle

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
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
    // Python source uses anizle.org specifically for player page requests (step 4)
    private val videoBase  = "https://anizle.org"

    // CloudflareKiller — used as fallback in getSession() when CF challenges the homepage.
    private val cfKiller = CloudflareKiller()

    // Session cache — reused across search/load/loadLinks calls within the same session.
    private var csrfToken: String? = null
    private var sessionFetchedAt: Long = 0L
    private val sessionTtlMs = 5 * 60 * 1000L // 5 minutes


    private suspend fun getSession() {
        val now = System.currentTimeMillis()
        if (csrfToken != null && (now - sessionFetchedAt) < sessionTtlMs) {
            android.util.Log.d("Anizle", "getSession: using cached token (age ${(now - sessionFetchedAt) / 1000}s)")
            return
        }
        android.util.Log.d("Anizle", "getSession: refreshing")
        try {
            // Fast path: plain request reuses CF cookies pre-warmed by the Anizm extension.
            var resp = app.get(mainUrl, headers = baseHeaders)
            var html = resp.text
            android.util.Log.d("Anizle", "getSession HTTP ${resp.code} len=${html.length}")

            // Fallback: if CF challenged us, solve it once with cfKiller (~3-5s, first time only).
            if (html.contains("Just a moment", ignoreCase = true) ||
                html.contains("cf-browser-verification", ignoreCase = true)) {
                android.util.Log.w("Anizle", "getSession: CF challenge, retrying with cfKiller")
                resp = app.get(mainUrl, headers = baseHeaders, interceptor = cfKiller)
                html = resp.text
                android.util.Log.d("Anizle", "getSession cfKiller HTTP ${resp.code} len=${html.length}")
            }

            // Extract CSRF token from meta tag: <meta name="csrf-token" content="...">
            csrfToken = Regex("""<meta[^>]+name=["']csrf-token["'][^>]+content=["']([^"']+)["']""")
                .find(html)?.groupValues?.get(1)
                ?: Regex("""<meta[^>]+content=["']([^"']+)["'][^>]+name=["']csrf-token["']""")
                .find(html)?.groupValues?.get(1)
            sessionFetchedAt = System.currentTimeMillis()
            android.util.Log.d("Anizle", "CSRF token: $csrfToken")
        } catch (e: Exception) {
            android.util.Log.e("Anizle", "getSession hatasi: ${e.message}")
        }
    }

    private val baseHeaders get() = mapOf(
        "User-Agent"      to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36",
        "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.7",
        "Referer"         to "$mainUrl/",
    )
    private val xhrHeaders get() = mapOf(
        "User-Agent"       to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36",
        "Accept-Language"  to "tr-TR,tr;q=0.9,en;q=0.7",
        "Origin"           to mainUrl,
        "Referer"          to "$mainUrl/",
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
            val params = mutableMapOf(
                "query"          to q,
                "type"           to "detailed",
                "limit"          to "20",
                "priorityField"  to "info_title",
                "orderBy"        to "info_year",
                "orderDirection" to "ASC",
            )
            csrfToken?.let { params["_token"] = it }
            android.util.Log.d("Anizle", "GET /searchAnime _token=${csrfToken?.take(10)}")
            val resp = app.get(
                "$mainUrl/searchAnime",
                headers = xhrHeaders,
                params  = params,
            )
            android.util.Log.d("Anizle", "HTTP ${resp.code} len=${resp.text.length}")
            resp.text
        } catch (e: Exception) {
            android.util.Log.e("Anizle", "Arama hatasi: ${e.message}")
            return emptyList()
        }

        return try {
            val root = JSONObject(responseText)
            val arr  = root.optJSONArray("data") ?: JSONArray(responseText)
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
        val isEpisodePage = request.data == "anime-izle"
        val url = if (isEpisodePage) "$mainUrl/anime-izle?sayfa=$page" else "$mainUrl?sayfa=$page"
        val doc = app.get(url, headers = baseHeaders).document


        // Helper: make a src attribute value absolute
        fun toAbsUrl(src: String): String? {
            if (src.isBlank() || src.startsWith("data:")) return null
            return when {
                src.startsWith("http") -> src
                src.startsWith("//")   -> "https:$src"
                src.startsWith("/")    -> "$mainUrl$src"
                else                   -> "$mainUrl/$src"  // bare relative path
            }
        }

        // Helper: strip site suffix from alt-derived titles ("Name - Anizm.TV" → "Name")
        fun cleanTitle(raw: String): String =
            raw.replace(Regex("""\s*-\s*Anizm[.\w]*$""", RegexOption.IGNORE_CASE), "").trim()

        val items = doc.select("a.imgWrapperLink, div.posterBlock > a, a[href*=-bolum]")
            .ifEmpty { doc.select("a[href*=-izle]").filter { it.selectFirst("img") != null } }
            .distinctBy { it.attr("href") }
            .mapNotNull { el ->
                val href = el.attr("abs:href").ifBlank { return@mapNotNull null }
                val isEpLink = href.contains("-bolum")

                // ── Poster extraction ────────────────────────────────────────
                // img.attr("src") returns the raw relative path; abs:src is empty because
                // Jsoup has no base URI. Manually resolve relative paths.
                val img = el.selectFirst("img")
                val poster: String? = img?.let {
                    toAbsUrl(it.attr("src"))
                        ?: toAbsUrl(it.attr("data-src"))
                        ?: toAbsUrl(it.attr("data-original"))
                } ?: run {
                    // posterBlock images are JS-loaded and commented out as <!--src="URL"-->
                    // Extract from the HTML comment inside div.poster
                    val posterDiv = el.selectFirst("div.poster")
                    if (posterDiv != null) {
                        val html = posterDiv.html()
                        Regex("""<!--src="([^"]+)"""").find(html)?.groupValues?.get(1)
                            ?.let { toAbsUrl(it) }
                    } else null
                }

                // ── Title extraction ─────────────────────────────────────────
                val title: String? = if (isEpLink) {
                    // Anime name is in a sibling element outside the episode <a>
                    val container = el.parent()
                    val siblingAnimeLink = container
                        ?.select("a[href]:not([href*=-bolum])")
                        ?.firstOrNull { it.attr("abs:href") != href }
                    siblingAnimeLink?.text()?.trim()?.let { cleanTitle(it) }?.ifBlank { null }
                        ?: container?.selectFirst(".animeName, .anime-name, h4, h5, h3, strong")
                            ?.clone()?.also { it.select("span").remove() }
                            ?.text()?.trim()?.let { cleanTitle(it) }?.ifBlank { null }
                        // Last resort: strip episode number and site name from img alt
                        ?: img?.attr("alt")?.trim()
                            ?.replace(Regex("""\s*\d+\.?\s*[Bb][oöô]l[uüû]m.*$"""), "")
                            ?.let { cleanTitle(it) }?.ifBlank { null }
                } else {
                    val titleEl = el.selectFirst("div.title, .truncateText, h4, h5, h3, strong, b")
                    val clone = (titleEl ?: el).clone()
                    clone.select("span.tag, span.label, .tag, .genres, a[href*=kategori]").remove()
                    cleanTitle(clone.text().trim())
                        .ifBlank { img?.attr("alt")?.let { cleanTitle(it) } ?: "" }
                        .ifBlank { null }
                }
                if (title.isNullOrBlank()) return@mapNotNull null

                val animeUrl = if (isEpLink)
                    href.replace(Regex("""-\d+[-.]?bolum[^/?#]*"""), "").trimEnd('-', '/')
                else
                    href

                newAnimeSearchResponse(title, animeUrl, TvType.Anime) { posterUrl = poster }
            }

        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    // ── Load (anime detail page) ───────────────────────────────────────────────
    override suspend fun load(url: String): LoadResponse {
        getSession()

        val doc = app.get(url, headers = baseHeaders).document

        val title = doc.selectFirst("h2.anizm_pageTitle, h1")?.text()?.trim()
            ?: url.substringAfterLast("/").replace("-", " ").trim()

        val posterEl = doc.selectFirst("div.infoPosterImg > img, img[src*=pcovers]")
        val poster = posterEl?.attr("abs:src")
            ?.ifBlank { posterEl.attr("data-src") }
            ?.ifBlank { posterEl.attr("abs:data-src") }
            ?.ifBlank { null }

        val plot = doc.selectFirst("div.infoDesc, .anime-description, .description")
            ?.also { it.select("h1,h2,h3,h4,a").remove() }
            ?.text()?.trim()?.ifBlank { null }

        val year = doc.select("span.dataValue, li, td").map { it.text().trim() }
            .firstOrNull { it.matches(Regex("""\d{4}""")) }?.toIntOrNull()

        val tags = doc.select("span.dataValue > span.tag > span.label, a[href*=/kategoriler/]")
            .map { it.text().trim() }.filter { it.isNotBlank() }.take(4).ifEmpty { null }

        // Episodes are inside div#episodesMiddle
        // Exclude trailers (fragman) and PVs; separate OVAs to the end
        val allLinks = doc.select("div#episodesMiddle a[href]")
            .ifEmpty { doc.select("div.episodeListTabContent a[href]") }
            .distinctBy { it.attr("abs:href") }
        val episodes = run {
            val regular = mutableListOf<Episode>()
            val ovas     = mutableListOf<Episode>()
            for (el in allLinks) {
                val href  = el.attr("abs:href").ifBlank { continue }
                val label = el.text().trim().ifBlank { continue }
                val hrefL = href.lowercase()
                val labelL = label.lowercase()
                // Skip trailers and PVs
                if (hrefL.contains("fragman") || labelL.contains("fragman")) continue
                if (hrefL.contains("-pv") || labelL.contains(" pv") || labelL == "pv") continue
                val isOva = hrefL.contains("ova") || labelL.contains("ova")
                val ep = newEpisode(href) { name = label }
                if (isOva) ovas.add(ep) else regular.add(ep)
            }
            // Number regular episodes in order, then append OVAs unnumbered
            regular.mapIndexed { i, ep ->
                val num = Regex("""(\d+)[.\s]*[Bb]ölüm""").find(ep.name ?: "")
                    ?.groupValues?.get(1)?.toIntOrNull()
                    ?: Regex("""-(\d+)[-.]?bolum""").find(ep.data)
                        ?.groupValues?.get(1)?.toIntOrNull()
                    ?: (i + 1)
                ep.apply { episode = num }
            } + ovas
        }

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
        getSession() // ensure cookies active for all requests below
        android.util.Log.d("Anizle", "loadLinks: $data")

        // One-shot CF pre-warm: cfKiller opens a WebView, solves Turnstile, and
        // deposits cf_clearance into the shared OkHttp cookie jar so all subsequent
        // /video/* fetches in this session pass through without another challenge.
        // cfKiller has a built-in 60s WebView timeout, so no wrapper is needed.
        try {
            android.util.Log.d("Anizle", "CF pre-warm: running cfKiller…")
            app.get("$mainUrl/video/1",
                headers = baseHeaders + mapOf("Referer" to "$mainUrl/"),
                interceptor = cfKiller)
            android.util.Log.d("Anizle", "CF pre-warm: done")
        } catch (e: Exception) {
            android.util.Log.w("Anizle", "CF pre-warm: ${e.message}")
        }

        val epHtml = try {
            app.get(data, headers = baseHeaders).text
        } catch (e: Exception) {
            android.util.Log.e("Anizle", "Episode page error: ${e.message}")
            return false
        }
        android.util.Log.d("Anizle", "Episode page len=${epHtml.length}")

        // Step 1: translator buttons — regex like Python
        val translators = mutableListOf<Pair<String, String>>()
        Regex("""translator="([^"]+)"[^>]*data-fansub-name="([^"]*)"""")
            .findAll(epHtml).forEach { m ->
                val url = m.groupValues[1]; if (url.isBlank()) return@forEach
                if (translators.none { it.first == url })
                    translators += url to m.groupValues[2].ifBlank { "Fansub" }
            }
        if (translators.isEmpty()) {
            Regex("""data-fansub-name="([^"]*)"[^>]*translator="([^"]+)"""")
                .findAll(epHtml).forEach { m ->
                    val url = m.groupValues[2]; if (url.isBlank()) return@forEach
                    if (translators.none { it.first == url })
                        translators += url to m.groupValues[1].ifBlank { "Fansub" }
                }
        }
        android.util.Log.d("Anizle", "Step1: ${translators.size} translators")
        if (translators.isEmpty()) return false

        var found = false

        for ((trUrl, fansubName) in translators) {
            android.util.Log.d("Anizle", "Step2: $fansubName -> $trUrl")

            // Step 2: GET translator URL → JSON {data: html} → video buttons
            val trText = try {
                app.get(trUrl,
                    headers = xhrHeaders + mapOf("Referer" to mainUrl)).text
            } catch (e: Exception) {
                android.util.Log.e("Anizle", "Translator fetch error: ${e.message}"); continue
            }
            android.util.Log.d("Anizle", "Step2 resp len=${trText.length}")

            val trHtml = try { JSONObject(trText).optString("data", "") }
                         catch (_: Exception) { "" }
            if (trHtml.isBlank()) {
                android.util.Log.w("Anizle", "Step2: no data field, raw=${trText.take(80)}")
                continue
            }

            // video="URL" data-video-name="Name" — exact Python pattern
            val videos = mutableListOf<Pair<String, String>>()
            Regex("""video="([^"]+)"[^>]*data-video-name="([^"]*)"""")
                .findAll(trHtml).forEach { m -> videos += m.groupValues[1] to m.groupValues[2].ifBlank { "Player" } }
            if (videos.isEmpty()) {
                Regex("""data-video-name="([^"]*)"[^>]*video="([^"]+)"""")
                    .findAll(trHtml).forEach { m -> videos += m.groupValues[2] to m.groupValues[1].ifBlank { "Player" } }
            }
            android.util.Log.d("Anizle", "Step2: ${videos.size} videos")
            for ((videoUrl, videoName) in videos) {
                val videoNameL = videoName.lowercase()

                // Only handle hosts we can extract:
                //   Aincrad → FirePlayer (anizmplayer.com)
                //   GDrive  → Google Drive direct link
                // Skipping everything else avoids N×M /player/ requests that block later fansubs.
                val isAincrad = videoNameL.contains("aincrad")
                val isGdrive  = videoNameL.contains("gdrive") || videoNameL.contains("google")
                if (!isAincrad && !isGdrive) {
                    android.util.Log.d("Anizle", "Step4: skipping unsupported host $videoName")
                    continue
                }

                android.util.Log.d("Anizle", "Step4: processing $videoName -> $videoUrl")

                // Step 3: XHR GET /video/{id} → JSON {player:"<iframe src=...>"}
                // This endpoint is CF-Turnstile protected. We rely on cf_clearance from
                // the pre-warm job launched in load(). With cf_clearance in the cookie jar,
                // a plain GET (no cfKiller) should pass. If still challenged, try cfKiller.
                fun isCfChallenge(h: String) = h.contains("Just a moment", ignoreCase = true) ||
                    h.contains("cf-browser-verification", ignoreCase = true)

                var videoPageHtml = try {
                    app.get(videoUrl, headers = xhrHeaders + mapOf("Referer" to "$mainUrl/")).text
                } catch (e: Exception) {
                    android.util.Log.e("Anizle", "Step3 fetch error: ${e.message}"); continue
                }
                android.util.Log.d("Anizle", "Step3 resp len=${videoPageHtml.length} snippet=${videoPageHtml.take(200)}")

                // If still CF-challenged, try cfKiller on this specific video URL.
                if (isCfChallenge(videoPageHtml)) {
                    android.util.Log.w("Anizle", "Step3: CF challenge, trying cfKiller on $videoUrl")
                    try {
                        videoPageHtml = app.get(videoUrl,
                            headers = xhrHeaders + mapOf("Referer" to "$mainUrl/"),
                            interceptor = cfKiller).text
                        android.util.Log.d("Anizle", "Step3 cfKiller len=${videoPageHtml.length} snippet=${videoPageHtml.take(200)}")
                    } catch (e: Exception) {
                        android.util.Log.e("Anizle", "Step3 cfKiller error: ${e.message}"); continue
                    }
                }
                if (isCfChallenge(videoPageHtml)) {
                    android.util.Log.w("Anizle", "Step3: still CF after cfKiller — giving up on $videoUrl")
                    continue
                }

                // Parse JSON if the endpoint returns {player:"..."} or similar
                val step3Html = try {
                    val obj = org.json.JSONObject(videoPageHtml)
                    obj.optString("player", "").ifBlank { obj.optString("data", "") }
                        .ifBlank { obj.optString("html", "") }
                } catch (_: Exception) { videoPageHtml }

                android.util.Log.d("Anizle", "Step3 html len=${step3Html.length} snippet=${step3Html.take(300)}")

                // Extract numeric player ID for the /player/ path
                val playerId = Regex("""/(?:video|player)/(\d+)""").find(videoUrl)?.groupValues?.get(1)
                if (playerId == null) {
                    android.util.Log.w("Anizle", "Step3: no playerId in $videoUrl"); continue
                }

                // Try to get FirePlayer hash from step3Html first (might be embedded)
                var pageHtml = if (step3Html.contains("FirePlayer", ignoreCase = true) ||
                    step3Html.contains("eval(function(p,a,c,k", ignoreCase = true)) step3Html else ""

                // If not found in step3, try fetching /player/{id} directly (might work with cf_clearance)
                if (pageHtml.isBlank() && isAincrad) {
                    for (base4 in listOf(mainUrl, videoBase)) {
                        val html = try {
                            app.get("$base4/player/$playerId",
                                headers = baseHeaders + mapOf("Referer" to "$base4/")).text
                        } catch (e: Exception) {
                            android.util.Log.e("Anizle", "Player page error ($base4): ${e.message}"); continue
                        }
                        android.util.Log.d("Anizle", "Step4 ($base4): len=${html.length} snippet=${html.take(300)}")
                        if (isCfChallenge(html)) {
                            android.util.Log.w("Anizle", "Step4: CF on $base4 — trying cfKiller")
                            val solved = try {
                                app.get("$base4/player/$playerId",
                                    headers = baseHeaders + mapOf("Referer" to "$base4/"),
                                    interceptor = cfKiller).text
                            } catch (e: Exception) {
                                android.util.Log.e("Anizle", "Step4 cfKiller ($base4): ${e.message}"); continue
                            }
                            android.util.Log.d("Anizle", "Step4 cfKiller ($base4): len=${solved.length} snippet=${solved.take(300)}")
                            if (!isCfChallenge(solved) && (solved.contains("FirePlayer", ignoreCase = true) ||
                                solved.contains("eval(function(p,a,c,k", ignoreCase = true))) {
                                pageHtml = solved; break
                            }
                            continue
                        }
                        if (html.contains("FirePlayer", ignoreCase = true) ||
                            html.contains("eval(function(p,a,c,k", ignoreCase = true)) {
                            pageHtml = html; break
                        }
                        android.util.Log.w("Anizle", "Step4: no FirePlayer in $base4 page (len=${html.length})")
                    }
                    if (pageHtml.isBlank()) continue
                }

                val label = "$fansubName - ${videoName.replace("(Reklamsız)", "").replace("(reklamsız)", "").trim()}"

                // ── GDrive path ──────────────────────────────────────────────
                // step3Html / videoPageHtml may already contain the Drive file ID if the
                // video page embeds it directly. Otherwise fall back to fetching anizle.org/player/.
                if (isGdrive) {
                    fun extractDriveId(html: String): String? =
                        Regex("""drive\.google\.com/file/d/([A-Za-z0-9_-]{25,})""").find(html)?.groupValues?.get(1)
                        ?: Regex("""[?&]id=([A-Za-z0-9_-]{25,})""").find(html)?.groupValues?.get(1)
                        ?: Regex("""docs\.google\.com/[^"'\s]*[?&/]d/([A-Za-z0-9_-]{25,})""").find(html)?.groupValues?.get(1)

                    var fileId: String? = extractDriveId(step3Html).also {
                        if (it != null) android.util.Log.d("Anizle", "GDrive: fileId from step3Html=$it")
                    }

                    // Fallback: fetch anizle.org/player/{id} (might work with cf_clearance in jar)
                    if (fileId == null) {
                        val gPageUrl = "$videoBase/player/$playerId"
                        var gHtml = try {
                            app.get(gPageUrl, headers = baseHeaders + mapOf("Referer" to "$videoBase/")).text
                        } catch (e: Exception) { android.util.Log.e("Anizle", "GDrive page error: ${e.message}"); "" }
                        android.util.Log.d("Anizle", "GDrive page len=${gHtml.length} snippet=${gHtml.take(500)}")
                        if (isCfChallenge(gHtml)) {
                            android.util.Log.w("Anizle", "GDrive: CF on anizle.org, trying cfKiller")
                            gHtml = try { app.get(gPageUrl,
                                headers = baseHeaders + mapOf("Referer" to "$videoBase/"),
                                interceptor = cfKiller).text
                            } catch (e: Exception) { android.util.Log.e("Anizle", "GDrive cfKiller: ${e.message}"); "" }
                            android.util.Log.d("Anizle", "GDrive cfKiller len=${gHtml.length} snippet=${gHtml.take(300)}")
                        }
                        fileId = extractDriveId(gHtml).also {
                            if (it != null) android.util.Log.d("Anizle", "GDrive: fileId from anizle.org=$it")
                        }
                        if (fileId == null) {
                            android.util.Log.w("Anizle", "GDrive: no file ID in any source — skipping")
                            val gIdx = gHtml.indexOf("google")
                            if (gIdx >= 0) android.util.Log.d("Anizle", "GDrive ctx=${gHtml.substring(gIdx.coerceAtLeast(0), (gIdx+300).coerceAtMost(gHtml.length))}")
                            continue
                        }
                    }
                    android.util.Log.d("Anizle", "GDrive: fileId=$fileId")

                    // Direct Google Drive download URL — stable, no session expiry, no third-party CDN.
                    // confirm=t bypasses the "large file" interstitial Google shows for big files.
                    val directUrl = "https://drive.usercontent.google.com/download?id=$fileId&export=download&confirm=t"
                    android.util.Log.d("Anizle", "GDrive: directUrl=$directUrl")
                    callback(newExtractorLink(
                        source = label,
                        name   = label,
                        url    = directUrl,
                        type   = ExtractorLinkType.VIDEO
                    ) {
                        quality = Qualities.Unknown.value
                        referer = "https://drive.google.com/"
                    })
                    found = true
                    continue
                }

                // ── Aincrad / FirePlayer path ────────────────────────────────
                val fireId = extractFireplayerId(pageHtml)
                android.util.Log.d("Anizle", "Step3/4: fireId=$fireId (pageHtml len=${pageHtml.length})")
                fireId ?: continue

                val playerReferer = "$playerBase/player/$fireId"
                val aincradHeaders = mapOf(
                    "User-Agent"       to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/125.0.0.0 Safari/537.36",
                    "X-Requested-With" to "XMLHttpRequest",
                    "Accept"           to "application/json, */*; q=0.01",
                    "Referer"          to playerReferer,
                    "Origin"           to playerBase,
                )

                // Step 5: POST do=getVideo → HLS master.m3u8 for streaming.
                // ExoPlayer handles the separate audio track natively in the player.
                // Downloads should use the GDrive link (registered earlier) which is a direct MP4.
                val streamText = try {
                    app.post(
                        "$playerBase/player/index.php?data=$fireId&do=getVideo",
                        headers = aincradHeaders
                    ).text
                } catch (e: Exception) {
                    android.util.Log.e("Anizle", "getVideo error: ${e.message}"); continue
                }
                android.util.Log.d("Anizle", "Step5 full=${streamText.take(300)}")

                val json = try { JSONObject(streamText) } catch (_: Exception) { continue }

                val securedLink = json.optString("securedLink", "")
                val videoSource = json.optString("videoSource", "")
                android.util.Log.d("Anizle", "Step5 securedLink=$securedLink videoSource=${videoSource.take(80)}")

                if (json.optBoolean("hls", false) && securedLink.isNotBlank()) {
                    android.util.Log.d("Anizle", "Aincrad HLS url=$securedLink")
                    callback(newExtractorLink(source = label, name = label, url = securedLink,
                        type = ExtractorLinkType.M3U8) {
                        quality = Qualities.P1080.value
                        referer = playerReferer
                    })
                    found = true; continue
                }
                if (videoSource.isNotBlank()) {
                    callback(newExtractorLink(source = label, name = label, url = videoSource,
                        type = ExtractorLinkType.VIDEO) { quality = Qualities.Unknown.value })
                    found = true
                }
            } // end for (videoUrl, videoName)
        } // end for (trUrl, fansubName)
        return found
    }

    
    // ── Stream helpers ───────────────────────────────────────────────────────

    // Fetches a master.m3u8, picks the highest-quality sub-playlist, and returns
    // its absolute URL. Returns null if the manifest can't be fetched or parsed.
    private suspend fun resolveM3u8Quality(masterUrl: String): String? {
        val master = try { app.get(masterUrl).text } catch (_: Exception) { return null }
        // Lines starting with "#EXT-X-STREAM-INF" are followed by a playlist URL.
        // Pick the one with the highest BANDWIDTH or RESOLUTION value.
        data class Entry(val bandwidth: Int, val url: String)
        val entries = mutableListOf<Entry>()
        val lines = master.lines()
        for (i in lines.indices) {
            val line = lines[i]
            if (line.startsWith("#EXT-X-STREAM-INF")) {
                val bw = Regex("""BANDWIDTH=(\d+)""").find(line)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                val next = lines.getOrNull(i + 1)?.trim() ?: continue
                if (next.isBlank() || next.startsWith("#")) continue
                val absUrl = when {
                    next.startsWith("http") -> next
                    next.startsWith("/")    -> masterUrl.substringBefore("//").plus("//")
                        .plus(masterUrl.removePrefix("https://").removePrefix("http://").substringBefore("/"))
                        .plus(next)
                    else -> masterUrl.substringBeforeLast("/") + "/" + next
                }
                entries.add(Entry(bw, absUrl))
            }
        }
        return entries.maxByOrNull { it.bandwidth }?.url
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
