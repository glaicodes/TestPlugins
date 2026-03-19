package com.yourname.anizle

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONArray
import org.json.JSONObject

/**
 * Anizle / Anizm CloudStream 3 Provider
 *
 * Kaynak: https://anizm.pro  (API) / https://anizle.org  (Video)
 *
 * Stream akışı (Python kaynak kodundan çevrildi):
 *  1. Bölüm sayfasından translator butonlarını al  (anizle.org)
 *  2. Translator endpoint → video butonları        (XHR/JSON)
 *  3. Video endpoint → player iframe ID            (XHR/JSON)
 *  4. anizle.org/player/{id} → FirePlayer hash     (packed JS decode)
 *  5. anizmplayer.com getVideo → gerçek M3U8/MP4   (POST/JSON)
 */
class AnizleProvider : MainAPI() {

    // ------------------------------------------------------------------ meta
    override var mainUrl    = "https://anizm.pro"
    override var name       = "Anizle"
    override val lang       = "tr"
    override val hasMainPage = true
    override val hasSearch   = true
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )

    // ------------------------------------------------------------------ urls
    private val apiBase    = "https://anizle.org"
    private val playerBase = "https://anizmplayer.com"

    // ------------------------------------------------------------------ headers
    private val baseHeaders = mapOf(
        "User-Agent"      to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                             "AppleWebKit/537.36 (KHTML, like Gecko) " +
                             "Chrome/120.0.0.0 Safari/537.36",
        "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7",
    )
    private val xhrHeaders = baseHeaders + mapOf(
        "X-Requested-With" to "XMLHttpRequest",
        "Accept"           to "application/json, text/javascript, */*; q=0.01",
    )

    // ------------------------------------------------------------------ cache
    /** Full anime list fetched once from /getAnimeListForSearch */
    private var animeDatabase: List<JSONObject>? = null

    private suspend fun getAnimeDatabase(): List<JSONObject> {
        animeDatabase?.let { return it }
        return try {
            val resp = app.get("$mainUrl/getAnimeListForSearch", headers = baseHeaders, timeout = 120)
            val arr  = JSONArray(resp.text)
            val list = mutableListOf<JSONObject>()
            for (i in 0 until arr.length()) list.add(arr.getJSONObject(i))
            animeDatabase = list
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    // ================================================================== SEARCH
    override suspend fun search(query: String): List<SearchResponse> {
        val db = getAnimeDatabase()
        if (db.isEmpty()) return emptyList()

        data class Scored(val score: Float, val resp: SearchResponse)

        fun sim(q: String, text: String): Float {
            if (text.isBlank()) return 0f
            val ql = q.lowercase()
            val tl = text.lowercase()
            if (ql == tl) return 1f
            if (tl.contains(ql)) return 0.9f
            // Lightweight overlap score
            val matches = ql.count { tl.contains(it) }
            return matches.toFloat() / ql.length.coerceAtLeast(1)
        }

        return db.mapNotNull { anime ->
            val maxScore = listOf(
                sim(query, anime.optString("info_title",         "")),
                sim(query, anime.optString("info_titleoriginal", "")),
                sim(query, anime.optString("info_titleenglish",  "")),
                sim(query, anime.optString("info_othernames",    "")),
                sim(query, anime.optString("info_japanese",      "")),
            ).max()

            if (maxScore <= 0.3f) return@mapNotNull null

            val slug  = anime.optString("info_slug", "")  .ifBlank { return@mapNotNull null }
            val title = anime.optString("info_title", slug).ifBlank { slug }
            val raw   = anime.optString("info_poster", "")
            val poster = when {
                raw.isBlank()         -> null
                raw.startsWith("http") -> raw
                else                  -> "$mainUrl/uploads/img/$raw"
            }
            Scored(maxScore, newAnimeSearchResponse(title, "$mainUrl/$slug", TvType.Anime) {
                posterUrl = poster
            })
        }
            .sortedByDescending { it.score }
            .take(20)
            .map { it.resp }
    }

    // ============================================================== MAIN PAGE
    override val mainPage = mainPageOf(
        "recent"   to "Son Eklenen Animeler",
        "popular"  to "Popüler Animeler",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val db = getAnimeDatabase()
        if (db.isEmpty()) return newHomePageResponse(request.name, emptyList<SearchResponse>())

        // "recent" → last-added by iterating in reverse; "popular" → sort by MAL score
        val sorted = when (request.data) {
            "popular" -> db.sortedByDescending { it.optDouble("info_malpoint", 0.0) }
            else      -> db.reversed()
        }

        val pageSize = 30
        val slice = sorted.drop((page - 1) * pageSize).take(pageSize)

        val items = slice.mapNotNull { anime ->
            val slug  = anime.optString("info_slug",  "").ifBlank { return@mapNotNull null }
            val title = anime.optString("info_title", slug)
            val raw   = anime.optString("info_poster", "")
            val poster = when {
                raw.isBlank()          -> null
                raw.startsWith("http") -> raw
                else                   -> "$mainUrl/uploads/img/$raw"
            }
            newAnimeSearchResponse(title, "$mainUrl/$slug", TvType.Anime) {
                posterUrl = poster
            }
        }
        return newHomePageResponse(request.name, items, hasNext = slice.size == pageSize)
    }

    // ================================================================ LOAD (result page)
    override suspend fun load(url: String): LoadResponse {
        val slug = url.removePrefix("$mainUrl/").trimStart('/')
        val db   = getAnimeDatabase()

        // --- metadata from local database ---
        var title: String  = slug.replace("-", " ").split(" ")
            .joinToString(" ") { it.replaceFirstChar(Char::uppercase) }
        var posterUrl: String? = null
        var plot: String?      = null
        var tags: List<String>? = null
        var year: Int?          = null

        val animeData = db.find { it.optString("info_slug", "") == slug }
        if (animeData != null) {
            title     = animeData.optString("info_title", title).ifBlank { title }
            val raw   = animeData.optString("info_poster", "")
            posterUrl = when {
                raw.isBlank()          -> null
                raw.startsWith("http") -> raw
                else                   -> "$mainUrl/uploads/img/$raw"
            }
            plot = animeData.optString("info_summary", "").ifBlank { null }
            year = animeData.optString("info_year", "").toIntOrNull()

            val cats = animeData.optJSONArray("categories")
            if (cats != null) {
                val tagList = mutableListOf<String>()
                for (i in 0 until cats.length()) {
                    cats.optJSONObject(i)?.optString("tag_title", "")
                        ?.takeIf { it.isNotBlank() }
                        ?.let { tagList.add(it) }
                }
                if (tagList.isNotEmpty()) tags = tagList
            }
        }

        // --- episodes ---
        val episodes = fetchEpisodes(slug)

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = posterUrl
            this.plot      = plot
            this.tags      = tags
            this.year      = year
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    /**
     * Fetch all episodes for [slug] from the anime page HTML.
     *
     * Patterns tried (in priority order):
     *  1. Absolute URL → `href="https://anizm.pro/slug-N-bolum-izle"`
     *  2. data-order attribute → `href="..." data-order="N"`
     *  3. Relative URL fallback → `href="slug-N-bolum-izle"`
     */
    private suspend fun fetchEpisodes(slug: String): List<Episode> {
        return try {
            val html = app.get("$mainUrl/$slug", headers = baseHeaders).text

            data class EpEntry(val num: Int, val epSlug: String, val epTitle: String)

            val seen     = mutableSetOf<Int>()
            val episodes = mutableListOf<EpEntry>()

            fun tryAdd(num: Int, epSlug: String, epTitle: String) {
                if (num !in seen) { seen.add(num); episodes.add(EpEntry(num, epSlug, epTitle)) }
            }

            // Pattern 1: absolute URL
            val abs = Regex("""href="${Regex.escapeReplacement(mainUrl)}/([^"]+?-(\d+)-bolum[^"]*)"""")
            abs.findAll(html).forEach { m ->
                tryAdd(
                    m.groupValues[2].toIntOrNull() ?: return@forEach,
                    m.groupValues[1].trimStart('/'),
                    "${m.groupValues[2]}. Bölüm"
                )
            }

            // Pattern 2: data-order
            if (episodes.isEmpty()) {
                val ord = Regex(
                    """href="(?:${Regex.escapeReplacement(mainUrl)})?/?([^"]+?-bolum[^"]*)"[^>]*data-order="(\d+)"[^>]*>([^<]+)"""
                )
                ord.findAll(html).forEach { m ->
                    tryAdd(
                        m.groupValues[2].toIntOrNull() ?: return@forEach,
                        m.groupValues[1].trimStart('/'),
                        m.groupValues[3].trim()
                    )
                }
            }

            // Pattern 3: relative fallback
            if (episodes.isEmpty()) {
                val rel = Regex("""href="/?([^"]+?-(\d+)-bolum[^"]*)"[^>]*>([^<]*)""")
                rel.findAll(html).forEach { m ->
                    val num  = m.groupValues[2].toIntOrNull() ?: return@forEach
                    val rawSlug = m.groupValues[1].trimStart('/')
                        .removePrefix("http://").removePrefix("https://")
                        .dropWhile { it != '/' }.trimStart('/')   // strip host if any
                    tryAdd(
                        num,
                        rawSlug.ifBlank { m.groupValues[1].trimStart('/') },
                        m.groupValues[3].trim().ifBlank { "$num. Bölüm" }
                    )
                }
            }

            episodes
                .sortedBy { it.num }
                .map { (num, epSlug, epTitle) ->
                    newEpisode(epSlug) {
                        name    = epTitle
                        episode = num
                    }
                }
        } catch (_: Exception) {
            emptyList()
        }
    }

    // ============================================================= LOAD LINKS
    /**
     * [data] is the episode slug (e.g. "one-piece-1080-bolum-izle").
     *
     * Full 5-step chain:
     *  1. Fetch episode page → translator URLs + fansub names
     *  2. GET translator URL  → JSON{data: html} → video URLs + names
     *  3. GET video URL       → JSON{player: html} → player numeric ID
     *  4. GET player page     → packed JS → FirePlayer hash (32 hex chars)
     *  5. POST getVideo       → JSON{hls/securedLink or videoSource}
     */
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        // Build the episode URL — prefer anizle.org for video data
        val episodeUrl = when {
            data.startsWith("http") -> data.replace(mainUrl, apiBase)
            else                    -> "$apiBase/${data.trimStart('/')}"
        }

        // ── Step 1: translator buttons ────────────────────────────────────
        val translators = mutableListOf<Pair<String, String>>() // (url, fansubName)
        try {
            val epHtml = app.get(episodeUrl, headers = baseHeaders).text

            val trPat = Regex("""translator="([^"]+)"[^>]*data-fansub-name="([^"]*)"""")
            trPat.findAll(epHtml).forEach { m ->
                val trUrl  = m.groupValues[1].ifBlank { return@forEach }
                val fansub = m.groupValues[2].ifBlank { "Fansub" }
                if (translators.none { it.first == trUrl }) translators.add(trUrl to fansub)
            }

            // Fallback attribute order
            if (translators.isEmpty()) {
                val trPat2 = Regex("""data-fansub-name="([^"]*)"[^>]*translator="([^"]+)"""")
                trPat2.findAll(epHtml).forEach { m ->
                    val trUrl  = m.groupValues[2].ifBlank { return@forEach }
                    val fansub = m.groupValues[1].ifBlank { "Fansub" }
                    if (translators.none { it.first == trUrl }) translators.add(trUrl to fansub)
                }
            }
        } catch (_: Exception) {
            return false
        }

        if (translators.isEmpty()) return false

        var found = false

        for ((trUrl, fansubName) in translators) {
            // ── Step 2: video list ─────────────────────────────────────────
            val trResp = try {
                app.get(trUrl, headers = xhrHeaders + mapOf("Referer" to apiBase))
            } catch (_: Exception) { continue }

            val trHtml = try {
                JSONObject(trResp.text).optString("data", "")
            } catch (_: Exception) { continue }

            val videoPat = Regex("""video="([^"]+)"[^>]*data-video-name="([^"]*)"""")
            val videos   = mutableListOf<Pair<String, String>>() // (url, name)
            videoPat.findAll(trHtml).forEach { m ->
                videos.add(m.groupValues[1] to m.groupValues[2].ifBlank { "Player" })
            }
            // Fallback attribute order
            if (videos.isEmpty()) {
                val videoPat2 = Regex("""data-video-name="([^"]*)"[^>]*video="([^"]+)"""")
                videoPat2.findAll(trHtml).forEach { m ->
                    videos.add(m.groupValues[2] to m.groupValues[1].ifBlank { "Player" })
                }
            }

            for ((videoUrl, videoName) in videos) {
                // ── Step 3: player iframe ID ───────────────────────────────
                val videoResp = try {
                    app.get(videoUrl, headers = xhrHeaders + mapOf("Referer" to apiBase))
                } catch (_: Exception) { continue }

                val playerHtml = try {
                    JSONObject(videoResp.text).optString("player", "")
                } catch (_: Exception) { continue }

                val playerId = Regex("""/player/(\d+)""").find(playerHtml)
                    ?.groupValues?.get(1) ?: continue

                // ── Step 4: FirePlayer hash ────────────────────────────────
                val playerPage = try {
                    app.get(
                        "$apiBase/player/$playerId",
                        headers = baseHeaders + mapOf("Referer" to "$apiBase/")
                    ).text
                } catch (_: Exception) { continue }

                val fireplayerId = extractFireplayerId(playerPage) ?: continue

                // ── Step 5: real stream URL ────────────────────────────────
                val streamResp = try {
                    app.post(
                        "$playerBase/player/index.php?data=$fireplayerId&do=getVideo",
                        headers = mapOf(
                            "User-Agent"       to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                                                  "AppleWebKit/537.36 (KHTML, like Gecko) " +
                                                  "Chrome/120.0.0.0 Safari/537.36",
                            "X-Requested-With" to "XMLHttpRequest",
                            "Accept"           to "application/json, text/javascript, */*; q=0.01",
                            "Referer"          to "$playerBase/player/$fireplayerId",
                            "Origin"           to playerBase,
                        )
                    )
                } catch (_: Exception) { continue }

                val streamJson = try { JSONObject(streamResp.text) }
                catch (_: Exception) { continue }

                val label = "$fansubName – $videoName"

                // HLS stream (preferred)
                val securedLink = streamJson.optString("securedLink", "")
                if (streamJson.optBoolean("hls", false) && securedLink.isNotBlank()) {
                    callback(
                        ExtractorLink(
                            source   = this.name,
                            name     = label,
                            url      = securedLink,
                            referer  = playerBase,
                            quality  = Qualities.Unknown.value,
                            isM3u8   = true,
                        )
                    )
                    found = true
                    continue
                }

                // Direct video source fallback
                val videoSource = streamJson.optString("videoSource", "")
                if (videoSource.isNotBlank()) {
                    callback(
                        ExtractorLink(
                            source  = this.name,
                            name    = label,
                            url     = videoSource,
                            referer = playerBase,
                            quality = Qualities.Unknown.value,
                            isM3u8  = false,
                        )
                    )
                    found = true
                }
            }
        }

        return found
    }

    // ============================================================= JS HELPERS

    /**
     * Decode Dean Edwards' `eval(function(p,a,c,k,e,d){...` packer format,
     * then extract the 32-char hex FirePlayer ID from the decoded output.
     *
     * Fallback: search the raw HTML for FirePlayer('...') directly.
     */
    private fun extractFireplayerId(html: String): String? {
        // Try packed JS first
        val evalMatch = Regex(
            """eval\(function\(p,a,c,k,e,d\)\{.*?return p\}\('(.*?)',(\d+),(\d+),'([^']+)'\.split\('\|'\),0,\{\}\)\)""",
            setOf(RegexOption.DOT_MATCHES_ALL)
        ).find(html)

        if (evalMatch != null) {
            val p = evalMatch.groupValues[1]
            val a = evalMatch.groupValues[2].toIntOrNull() ?: 0
            val c = evalMatch.groupValues[3].toIntOrNull() ?: 0
            val k = evalMatch.groupValues[4].split("|")
            runCatching {
                val decoded = unpackJs(p, a, c, k)
                Regex("""FirePlayer\s*\(\s*["']([a-f0-9]{32})["']""").find(decoded)
                    ?.groupValues?.get(1)
                    ?.let { return it }
            }
        }

        // Direct fallback (no packer)
        return Regex("""FirePlayer\s*\(["']([a-f0-9]{32})["']""").find(html)
            ?.groupValues?.get(1)
    }

    /**
     * Dean Edwards JavaScript packer decoder.
     *
     * Ported 1-to-1 from the Python [_unpack_js] implementation in anizle.py.
     *
     * @param p  The packed string (first argument of the eval call)
     * @param a  The base (second argument)
     * @param c  The word count (third argument)
     * @param k  The symbol table (fourth argument, already split on '|')
     */
    private fun unpackJs(p: String, a: Int, c: Int, k: List<String>): String {
        /** Convert [num] to base-[base] string using the same alphabet as the packer. */
        fun toBase(num: Int, base: Int): String {
            val head = if (num < base) "" else toBase(num / base, base)
            val rem  = num % base
            val char = when {
                rem > 35 -> (rem + 29).toChar()   // A-Z  (36 → 'a'+29='A' ... not exactly, but matches packer)
                rem > 9  -> (rem + 87).toChar()   // a-z
                else     -> ('0' + rem)
            }
            return head + char
        }

        var idx = c
        val dict = mutableMapOf<String, String>()
        while (idx > 0) {
            idx--
            val key   = toBase(idx, a)
            dict[key] = if (idx < k.size && k[idx].isNotEmpty()) k[idx] else key
        }

        return Regex("""\b\w+\b""").replace(p) { dict[it.value] ?: it.value }
    }
}
