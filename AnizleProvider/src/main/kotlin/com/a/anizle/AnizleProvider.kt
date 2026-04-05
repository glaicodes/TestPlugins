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
 *
 * Video link resolution (confirmed from network capture):
 *  Step 1: Episode HTML → translator="URL" attributes
 *  Step 2: GET translator URL (XHR + CSRF) → JSON {data: html} → video="URL" buttons
 *  Step 3: GET /video/{id} (XHR + CSRF) → JSON {player: "<iframe src='anizm.net/player/{id}'>"}
 *  Step 4: GET anizm.net/player/{id} → packed JS → FirePlayer hash (32 hex chars)
 *  Step 5: POST anizmplayer.com/player/index.php?data={hash}&do=getVideo → {hls, securedLink}
 */
class AnizleProvider : MainAPI() {

    override var mainUrl    = "https://anizm.net"
    override var name       = "Anizle"
    override var lang       = "tr"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    private val playerBase = "https://anizmplayer.com"

    private val cfKiller = CloudflareKiller()

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
            var resp = app.get(mainUrl, headers = baseHeaders)
            var html = resp.text
            android.util.Log.d("Anizle", "getSession HTTP ${resp.code} len=${html.length}")

            if (html.contains("Just a moment", ignoreCase = true) ||
                html.contains("cf-browser-verification", ignoreCase = true)) {
                android.util.Log.w("Anizle", "getSession: CF challenge, retrying with cfKiller")
                resp = app.get(mainUrl, headers = baseHeaders, interceptor = cfKiller)
                html = resp.text
                android.util.Log.d("Anizle", "getSession cfKiller HTTP ${resp.code} len=${html.length}")
            }

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

    // Standard XHR headers WITHOUT CSRF — used for translator URL fetches (Step 2).
    private val xhrHeaders get() = mapOf(
        "User-Agent"       to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36",
        "Accept-Language"  to "tr-TR,tr;q=0.9,en;q=0.7",
        "Origin"           to mainUrl,
        "Referer"          to "$mainUrl/",
        "X-Requested-With" to "XMLHttpRequest",
        "Accept"           to "application/json, text/javascript, */*; q=0.01",
    )

    // XHR headers WITH CSRF token — required for /video/{id} endpoint (Step 3).
    // Confirmed from network capture: X-CSRF-TOKEN must be present or the server rejects the request.
    private val xhrHeadersWithCsrf get(): Map<String, String> {
        val base = xhrHeaders.toMutableMap()
        csrfToken?.let { base["X-CSRF-TOKEN"] = it }
        return base
    }


    // ── Search ────────────────────────────────────────────────────────────────
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

        fun toAbsUrl(src: String): String? {
            if (src.isBlank() || src.startsWith("data:")) return null
            return when {
                src.startsWith("http") -> src
                src.startsWith("//")   -> "https:$src"
                src.startsWith("/")    -> "$mainUrl$src"
                else                   -> "$mainUrl/$src"
            }
        }

        fun cleanTitle(raw: String): String =
            raw.replace(Regex("""\s*-\s*Anizm[.\w]*$""", RegexOption.IGNORE_CASE), "").trim()

        val items = doc.select("a.imgWrapperLink, div.posterBlock > a, a[href*=-bolum]")
            .ifEmpty { doc.select("a[href*=-izle]").filter { it.selectFirst("img") != null } }
            .distinctBy { it.attr("href") }
            .mapNotNull { el ->
                val href = el.attr("abs:href").ifBlank { return@mapNotNull null }
                val isEpLink = href.contains("-bolum")

                val img = el.selectFirst("img")
                val poster: String? = img?.let {
                    toAbsUrl(it.attr("src"))
                        ?: toAbsUrl(it.attr("data-src"))
                        ?: toAbsUrl(it.attr("data-original"))
                } ?: run {
                    val posterDiv = el.selectFirst("div.poster")
                    if (posterDiv != null) {
                        val html = posterDiv.html()
                        Regex("""<!--src="([^"]+)"""").find(html)?.groupValues?.get(1)
                            ?.let { toAbsUrl(it) }
                    } else null
                }

                val title: String? = if (isEpLink) {
                    val container = el.parent()
                    val siblingAnimeLink = container
                        ?.select("a[href]:not([href*=-bolum])")
                        ?.firstOrNull { it.attr("abs:href") != href }
                    siblingAnimeLink?.text()?.trim()?.let { cleanTitle(it) }?.ifBlank { null }
                        ?: container?.selectFirst(".animeName, .anime-name, h4, h5, h3, strong")
                            ?.clone()?.also { it.select("span").remove() }
                            ?.text()?.trim()?.let { cleanTitle(it) }?.ifBlank { null }
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
                if (hrefL.contains("fragman") || labelL.contains("fragman")) continue
                if (hrefL.contains("-pv") || labelL.contains(" pv") || labelL == "pv") continue
                val isOva = hrefL.contains("ova") || labelL.contains("ova")
                val ep = newEpisode(href) { name = label }
                if (isOva) ovas.add(ep) else regular.add(ep)
            }
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
    //
    // Confirmed flow from network capture (GET /video/1552459):
    //
    //  Step 1: Episode HTML → translator="URL" attrs
    //  Step 2: GET translator URL (XHR + CSRF) → JSON {data: html} → video="URL" buttons
    //  Step 3: GET /video/{id} with X-CSRF-TOKEN header
    //          → JSON {player: '<iframe src="anizm.net/player/{id}">', status: "success"}
    //          NOTE: Without X-CSRF-TOKEN this endpoint rejects the request.
    //  Step 4: GET anizm.net/player/{id} → page with packed JS
    //          → unpack → FirePlayer("{hash}")
    //  Step 5: POST anizmplayer.com/player/index.php?data={hash}&do=getVideo
    //          → JSON {hls: true, securedLink: "https://...master.m3u8"}
    //
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        getSession() // ensure CSRF token is fresh
        android.util.Log.d("Anizle", "loadLinks: $data")

        val epHtml = try {
            app.get(data, headers = baseHeaders).text
        } catch (e: Exception) {
            android.util.Log.e("Anizle", "Episode page error: ${e.message}")
            return false
        }
        android.util.Log.d("Anizle", "Episode page len=${epHtml.length}")

        // Step 1: translator buttons
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
                app.get(trUrl, headers = xhrHeaders + mapOf("Referer" to mainUrl)).text
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

            // video="URL" data-video-name="Name"
            val videos = mutableListOf<Pair<String, String>>()
            Regex("""video="([^"]+)"[^>]*data-video-name="([^"]*)"""")
                .findAll(trHtml).forEach { m -> videos += m.groupValues[1] to m.groupValues[2].ifBlank { "Player" } }
            if (videos.isEmpty()) {
                Regex("""data-video-name="([^"]*)"[^>]*video="([^"]+)"""")
                    .findAll(trHtml).forEach { m -> videos += m.groupValues[2] to m.groupValues[1].ifBlank { "Player" } }
            }
            if (videos.isEmpty() && trText.isNotBlank()) {
                Regex("""video="([^"]+)"[^>]*data-video-name="([^"]*)"""")
                    .findAll(trText).forEach { m -> videos += m.groupValues[1] to m.groupValues[2].ifBlank { "Player" } }
            }
            if (videos.isEmpty() && trUrl == translators.first().first) {
                Regex("""video="([^"]+)"[^>]*data-video-name="([^"]*)"""")
                    .findAll(epHtml).forEach { m -> videos += m.groupValues[1] to m.groupValues[2].ifBlank { "Player" } }
                if (videos.isEmpty()) {
                    Regex("""data-video-name="([^"]*)"[^>]*video="([^"]+)"""")
                        .findAll(epHtml).forEach { m -> videos += m.groupValues[2] to m.groupValues[1].ifBlank { "Player" } }
                }
                if (videos.isNotEmpty())
                    android.util.Log.d("Anizle", "Step2: using ${videos.size} videos from epHtml")
            }
            android.util.Log.d("Anizle", "Step2: ${videos.size} videos")

            for ((videoUrl, videoName) in videos) {
                val videoNameL = videoName.lowercase()

                val isAincrad = videoNameL.contains("aincrad")
                val isGdrive  = videoNameL.contains("gdrive") || videoNameL.contains("google")
                val isKnownHost = isAincrad || isGdrive ||
                    videoNameL.contains("voe")       || videoNameL.contains("filemoon")  ||
                    videoNameL.contains("uqload")    || videoNameL.contains("vidmoly")   ||
                    videoNameL.contains("sibnet")    || videoNameL.contains("sendvid")   ||
                    videoNameL.contains("doodstream") || videoNameL.contains("ok.ru")   ||
                    videoNameL.contains("odnoklassniki") || videoNameL.contains("sistenn")
                if (!isKnownHost) {
                    android.util.Log.d("Anizle", "Step3: skipping unknown host $videoName")
                    continue
                }

                android.util.Log.d("Anizle", "Step3: processing $videoName -> $videoUrl")

                val label = "$fansubName - ${videoName.replace("(Reklamsız)", "").replace("(reklamsız)", "").trim()}"

                // ── Resolve the player page ──────────────────────────────────
                //
                // Network capture shows /video/{id} returns JSON:
                //   { "player": "<iframe src='https://anizm.net/player/{id}'>", "status": "success" }
                //
                // We must:
                //   1. GET /video/{id} with X-CSRF-TOKEN (required!) → parse JSON["player"] iframe src
                //   2. GET anizm.net/player/{id} → packed JS page → extract FirePlayer hash
                //
                // Path A: Check if hash is already embedded in step2 XHR responses (zero extra requests)
                // Path B: Follow the proper /video/{id} → /player/{id} chain
                //
                var pageHtml = ""

                // ── Path A: scan existing HTML for embedded anizmplayer hash ─
                val hashRe = Regex("""anizmplayer\.com/(?:video|player)/([a-f0-9]{32})""")
                val embeddedHash = hashRe.find(trHtml)?.groupValues?.get(1)
                    ?: hashRe.find(trText)?.groupValues?.get(1)
                    ?: hashRe.find(epHtml)?.groupValues?.get(1)
                if (embeddedHash != null) {
                    android.util.Log.d("Anizle", "Step3 PathA: embedded hash=$embeddedHash")
                    pageHtml = "anizmplayer.com/video/$embeddedHash" // synthetic — contains the hash URL
                }

                // ── Path B: GET /video/{id} with X-CSRF-TOKEN → follow iframe to /player/{id} ─
                if (pageHtml.isBlank()) {
                    android.util.Log.d("Anizle", "Step3 PathB: GET $videoUrl (with CSRF token)")
                    val videoJson = try {
                        app.get(videoUrl, headers = xhrHeadersWithCsrf).text
                    } catch (e: Exception) {
                        android.util.Log.w("Anizle", "Step3 PathB /video fetch error: ${e.message}"); ""
                    }
                    android.util.Log.d("Anizle", "Step3 PathB: len=${videoJson.length} snippet=${videoJson.take(120)}")

                    if (videoJson.isNotBlank()) {
                        // Parse the JSON and extract the iframe src from the "player" field.
                        // e.g. player = "<iframe ... src=\"https://anizm.net/player/1552459\"></iframe>"
                        val playerIframeSrc = try {
                            val obj = JSONObject(videoJson)
                            val playerField = obj.optString("player", "")
                            android.util.Log.d("Anizle", "Step3 PathB: player field = ${playerField.take(120)}")
                            // Extract src="..." from the iframe HTML
                            Regex("""src=["']([^"']+)["']""").find(playerField)?.groupValues?.get(1)
                                ?.replace("\\/", "/") // unescape JSON-encoded slashes
                        } catch (e: Exception) {
                            android.util.Log.w("Anizle", "Step3 PathB JSON parse error: ${e.message}"); null
                        }
                        android.util.Log.d("Anizle", "Step3 PathB: playerIframeSrc=$playerIframeSrc")

                        if (playerIframeSrc != null) {
                            // Fetch the player page — this is the packed JS page with FirePlayer hash.
                            // anizm.net/player/{id} uses the same cf_clearance from getSession().
                            android.util.Log.d("Anizle", "Step3 PathB: fetching player page $playerIframeSrc")
                            val playerPageHtml = try {
                                app.get(
                                    playerIframeSrc,
                                    headers = baseHeaders + mapOf("Referer" to "$mainUrl/")
                                ).text
                            } catch (e: Exception) {
                                android.util.Log.w("Anizle", "Step3 PathB player page error: ${e.message}"); ""
                            }
                            android.util.Log.d("Anizle", "Step3 PathB: player page len=${playerPageHtml.length} hasFirePlayer=${playerPageHtml.contains("FirePlayer", ignoreCase=true)}")

                            if (playerPageHtml.isNotBlank()) {
                                pageHtml = playerPageHtml
                            }
                        }
                    }
                }

                if (pageHtml.isBlank()) {
                    android.util.Log.w("Anizle", "Step3: all paths failed for $videoName — skipping")
                    continue
                }

                // ── GDrive path ──────────────────────────────────────────────
                if (isGdrive) {
                    fun extractDriveId(html: String): String? =
                        Regex("""drive\.google\.com/file/d/([A-Za-z0-9_-]{25,})""").find(html)?.groupValues?.get(1)
                        ?: Regex("""[?&]id=([A-Za-z0-9_-]{25,})""").find(html)?.groupValues?.get(1)

                    val fileId = extractDriveId(pageHtml)
                    if (fileId == null) {
                        android.util.Log.w("Anizle", "GDrive: no file ID in page — skipping")
                        continue
                    }
                    val directUrl = "https://drive.usercontent.google.com/download?id=$fileId&export=download&confirm=t"
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

                // ── Generic loadExtractor path (Voe, FileMoon, UQload, etc.) ─
                if (!isAincrad) {
                    val embedUrl = Regex("""<iframe[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                        .find(pageHtml)?.groupValues?.get(1)
                        ?: Regex("""<source[^>]+src=["']([^"']+\.(?:m3u8|mp4)[^"']*)["']""", RegexOption.IGNORE_CASE)
                            .find(pageHtml)?.groupValues?.get(1)
                    android.util.Log.d("Anizle", "loadExtractor: $videoName embedUrl=$embedUrl")
                    if (embedUrl != null) {
                        loadExtractor(embedUrl, mainUrl, subtitleCallback, callback)
                        found = true
                    }
                    continue
                }

                // ── Aincrad / FirePlayer path ────────────────────────────────
                // pageHtml is the player page from anizm.net/player/{id}
                val fireId = extractFireplayerId(pageHtml)
                android.util.Log.d("Anizle", "Step4: fireId=$fireId (pageHtml len=${pageHtml.length})")
                fireId ?: continue

                val playerReferer = "$playerBase/player/$fireId"
                val aincradHeaders = mapOf(
                    "User-Agent"       to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/125.0.0.0 Safari/537.36",
                    "X-Requested-With" to "XMLHttpRequest",
                    "Accept"           to "application/json, */*; q=0.01",
                    "Referer"          to playerReferer,
                    "Origin"           to playerBase,
                )

                // Step 5: POST do=getVideo → HLS master.m3u8
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

    private suspend fun resolveM3u8Quality(masterUrl: String): String? {
        val master = try { app.get(masterUrl).text } catch (_: Exception) { return null }
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
        // Fast path: hash directly in anizmplayer.com URL
        Regex("""anizmplayer\.com/(?:video|player)/([a-f0-9]{32})""")
            .find(html)?.groupValues?.get(1)?.let { return it }
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
        return Regex("""FirePlayer\(["']([a-f0-9]{32})["']""")
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
